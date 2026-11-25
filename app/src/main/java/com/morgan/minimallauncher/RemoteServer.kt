package com.morgan.minimallauncher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web server on the TV that serves the iPhone remote (assets/remote.html)
 * to phones on the same Wi-Fi. Every request must carry the pairing key
 * from the QR code shown in Settings, so other devices on the network
 * can't control the TV.
 */
class RemoteServer(private val context: Context) {

    companion object {
        const val PORT = 8765
        private const val MAX_UPLOAD = 25 * 1024 * 1024
        private val HOST_OK = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$|^localhost(:\\d+)?$|^\\[[0-9a-fA-F:.]+\\](:\\d+)?$")
        private val slots = java.util.concurrent.Semaphore(16)

        fun key(c: Context): String {
            Prefs.string(c, "remote_key")?.let { return it }
            val rnd = java.security.SecureRandom()   // unguessable, not just random-looking
            val k = (1..16).map { "abcdefghjkmnpqrstuvwxyz23456789".let { a -> a[rnd.nextInt(a.length)] } }.joinToString("")
            Prefs.setString(c, "remote_key", k)
            return k
        }

        /** The TV's address on the home network - the Wi-Fi or Ethernet one, never the VPN's (a phone can't reach that). */
        fun ip(c: Context): String? = try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            fun v4(n: android.net.Network) = cm.getLinkProperties(n)?.linkAddresses
                ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            @Suppress("DEPRECATION")
            val local = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let { caps ->
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
                } == true && v4(n) != null
            }
            local?.let { v4(it) } ?: cm.activeNetwork?.let { v4(it) }
        } catch (e: Exception) { null }

        fun url(c: Context): String? = ip(c)?.let { "http://$it:$PORT/?k=${key(c)}" }
    }

    private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (socket != null) return
        val s = try { ServerSocket(PORT) } catch (e: Exception) { return }
        socket = s
        thread(name = "remote-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                // At most 16 at once: a flood of connections can't run the TV out of memory.
                if (!slots.tryAcquire()) { try { client.close() } catch (_: Exception) {}; continue }
                thread(isDaemon = true) { try { handle(client) } finally { slots.release() } }
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }

    // ---------- HTTP ----------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            // Safari now tries a secure (https) connection first and only falls back to plain http when that fails.
            // This server only speaks http, so it used to sit waiting on the encrypted greeting and the page hung on
            // black for ever. Refuse it at once (a reset) so Safari falls straight back to http.
            input.mark(1)
            val first = try { input.read() } catch (e: Exception) { -1 }
            if (first == 0x16 || first < 0) { try { sock.setSoLinger(true, 0) } catch (_: Exception) {}; return }
            input.reset()
            val out = sock.getOutputStream()
            val req = try { read(input) } catch (e: Exception) { null } ?: return
            // A web page can't trick a phone's browser into talking to the TV: only requests addressed to an IP.
            val host = req.headers["host"]
            if (host != null && !HOST_OK.matches(host)) { send(out, 403, "text/plain", ByteArray(0)); return }
            try {
                route(req, out)
            } catch (e: Exception) {
                send(out, 500, "application/json", JSONObject().put("error", e.javaClass.simpleName).toString().toByteArray())
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_UPLOAD) return null
        // Only photo uploads are big, and only from a paired phone: anything else over 64 KB is refused before reading.
        if (len > 65_536) {
            val k = parts[1].substringAfter('?', "").split('&').firstOrNull { it.startsWith("k=") }?.let { URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if ((headers["x-key"] ?: k) != key(context)) return null
        }
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Request(parts[0], target.substringBefore('?'), query, headers, body)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, o: Any) = send(out, 200, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun route(req: Request, out: OutputStream) {
        val key = key(context)
        val authorised = req.query["k"] == key || req.headers["x-key"] == key
        // Pairing needs no key: the code on the TV is the proof you're in the room.
        if (req.path == "/api/pair/request") {
            // Too many wrong codes recently: pairing is locked for an hour (no code is made or shown).
            if (Pairing.request().isEmpty()) { json(out, JSONObject().put("ok", false).put("locked", true)); return }
            main.post { start(Intent(context, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            json(out, JSONObject().put("ok", true)); return
        }
        if (req.path == "/api/pair/confirm") {
            val entered = try { JSONObject(String(req.body, Charsets.UTF_8)).optString("code") } catch (e: Exception) { "" }
            val r = Pairing.confirm(entered)
            json(out, if (r == -1) JSONObject().put("ok", true).put("key", key) else JSONObject().put("ok", false).put("left", r))
            return
        }
        // The home-screen icon for the iPhone (no key needed - it's just the app's icon).
        if (req.path == "/icon.png") {
            val d = try { context.packageManager.getApplicationIcon(context.packageName) } catch (e: Exception) { null }
            if (d == null) { send(out, 404, "text/plain", ByteArray(0)); return }
            val bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp); cv.drawColor(0xFF000000.toInt())
            d.setBounds(18, 18, 162, 162); d.draw(cv)
            send(out, 200, "image/png", ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
            return
        }
        if (req.path == "/" || req.path == "/index.html") {
            if (!authorised) {
                send(out, 200, "text/html; charset=utf-8", context.assets.open("pair.html").use { it.readBytes() })
                return
            }
            send(out, 200, "text/html; charset=utf-8", context.assets.open("remote.html").use { it.readBytes() })
            return
        }
        if (!authorised) { send(out, 401, "application/json", "{\"error\":\"pair\"}".toByteArray()); return }
        val body = if (req.body.isNotEmpty() && req.headers["content-type"]?.startsWith("application/json") == true)
            JSONObject(String(req.body, Charsets.UTF_8)) else JSONObject()

        when (req.path) {
            "/api/state" -> json(out, JSONObject()
                .put("profile", ProfileManager.getActiveProfile(context))
                // Which TV this is (its name in the TV's settings), so a phone always shows what it's controlling.
                .put("tv", (try { android.provider.Settings.Global.getString(context.contentResolver, "device_name") } catch (e: Exception) { null })?.takeIf { it.isNotBlank() } ?: "TV")
                .put("fullControl", HomeRedirectService.instance != null)
                .put("android", android.os.Build.VERSION.SDK_INT)
                .put("city", Weather.getCity(context) ?: "")
                .put("calendar", CalendarFeed.getUrl(context) != null)
                .put("epg", LiveGuide.customUrl(context) != null)
                .put("photos", WallpaperLibrary.album(context).size)
                .put("ip", ip(context) ?: "")
                .put("selfEnable", SelfEnable.canSelfEnable(context)))
            "/api/tunelog" -> send(out, 200, "text/plain; charset=utf-8", TiviTuner.logText().toByteArray())
            "/api/screen" -> {
                var dump = "Accessibility service isn't running."
                val latch = java.util.concurrent.CountDownLatch(1)
                main.post { dump = try { TiviTuner.dumpScreen() } catch (e: Exception) { "Error: ${e.javaClass.simpleName}" }; latch.countDown() }
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                send(out, 200, "text/plain; charset=utf-8", dump.toByteArray())
            }
            "/api/key" -> json(out, JSONObject().put("ok", press(body.optString("key"))))
            "/api/text" -> json(out, JSONObject().put("ok", type(body.optString("text"), body.optBoolean("replace", false), body.optBoolean("enter", false))))
            "/api/apps" -> {
                val arr = JSONArray()
                AppListHelper.loadLaunchableTvApps(context).forEach { arr.put(JSONObject().put("pkg", it.packageName).put("label", it.label)) }
                json(out, arr)
            }
            "/api/icon" -> {
                val app = AppListHelper.loadLaunchableTvApps(context).firstOrNull { it.packageName == req.query["pkg"] }
                if (app == null) { send(out, 404, "text/plain", ByteArray(0)); return }
                val bmp = TileRenderer.render(app.icon, 144)
                val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                send(out, 200, "image/png", png)
            }
            "/api/launch" -> {
                val app = AppListHelper.loadLaunchableTvApps(context).firstOrNull { it.packageName == body.optString("pkg") }
                val ok = app?.launchIntent?.let { intent ->
                    RecentAppsTracker.recordOpened(context, app.packageName)
                    start(intent)
                } ?: false
                json(out, JSONObject().put("ok", ok))
            }
            "/api/mode" -> {
                val ok = when (val m = body.optString("mode")) {
                    AmbientActivity.AMBIENT, AmbientActivity.AERIAL, AmbientActivity.SLEEP ->
                        start(Intent(context, AmbientActivity::class.java).putExtra("mode", m))
                    "home" -> start(Intent(context, MainActivity::class.java))
                    "cinema" -> start(Intent(context, MainActivity::class.java).putExtra(EXTRA_CINEMA, true))
                    "claude" -> start(Intent(context, ClaudeActivity::class.java))
                    else -> false
                }
                json(out, JSONObject().put("ok", ok))
            }
            "/api/settings" -> {
                if (body.has("city")) Weather.setCity(context, body.optString("city").ifBlank { null })
                if (body.has("calendar")) CalendarFeed.setUrl(context, body.optString("calendar").ifBlank { null })
                if (body.has("epg")) LiveGuide.setCustomUrl(context, body.optString("epg").ifBlank { null })
                json(out, JSONObject().put("ok", true))
            }
            "/api/upload" -> {
                val name = (req.query["name"] ?: "photo.jpg").replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
                val file = File(WallpaperLibrary.albumDir(context), "${System.currentTimeMillis()}_$name")
                file.writeBytes(req.body)
                // Keep only real pictures.
                if (WallpaperLibrary.decodeFile(file, 64) == null) { file.delete(); json(out, JSONObject().put("ok", false)); return }
                json(out, JSONObject().put("ok", true).put("count", WallpaperLibrary.album(context).size))
            }
            "/api/profiles" -> json(out, JSONObject()
                .put("profiles", JSONArray(ProfileManager.getProfiles(context)))
                .put("active", ProfileManager.getActiveProfile(context)))
            "/api/avatar" -> {
                val who = req.query["p"]?.takeIf { it in ProfileManager.getProfiles(context) } ?: ProfileManager.getActiveProfile(context)
                json(out, JSONObject().put("ok", Avatars.savePhoto(context, who, req.body)))
            }
            // ---- Cast from the iPhone: find something in your Dodgy Box and play it on the TV ----
            "/api/lib/search" -> {
                val q = req.query["q"].orEmpty().trim()
                val o = JSONObject()
                if (q.isEmpty() || !Xtream.isSetUp(context)) { json(out, o.put("films", JSONArray()).put("series", JSONArray()).put("channels", JSONArray())); return }
                val (films, shows) = try { Xtream.searchLibrary(context, q, 25) } catch (e: Exception) { emptyList<Xtream.Vod>() to emptyList() }
                val want = TextMatch.normalise(q)
                val chans = try { Xtream.best(Xtream.live(context)).filter { TextMatch.normalise(it.name).contains(want) }.take(15) } catch (e: Exception) { emptyList() }
                o.put("films", JSONArray().apply { films.forEach { put(JSONObject().put("id", it.id).put("name", Xtream.clean(it.name)).put("icon", it.icon ?: "")) } })
                o.put("series", JSONArray().apply { shows.forEach { put(JSONObject().put("id", it.id).put("name", Xtream.clean(it.name)).put("icon", it.cover ?: "")) } })
                o.put("channels", JSONArray().apply { chans.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon ?: "")) } })
                json(out, o)
            }
            "/api/play" -> {
                val c = context
                val ok = try {
                    when (body.optString("kind")) {
                        "film" -> Xtream.vod(c).firstOrNull { it.id == body.optInt("id") }?.let { v ->
                            PlayerActivity.play(c, Xtream.vodUrl(c, v), Xtream.clean(v.name), live = false, eyebrow = "FILM", image = v.icon); true } ?: false
                        "series" -> Xtream.series(c).firstOrNull { it.id == body.optInt("id") }?.let { s ->
                            start(Intent(c, EpisodesActivity::class.java).putExtra(EpisodesActivity.EXTRA_NAME, Xtream.clean(s.name)).putExtra(EpisodesActivity.EXTRA_IMAGE, s.cover)) } ?: false
                        "live" -> Xtream.live(c).firstOrNull { it.id == body.optInt("id") }?.let { ch ->
                            PlayerActivity.play(c, Xtream.liveUrl(c, ch.id), ch.name, live = true, subtitle = LiveGuide.nowNext(c, ch.name), eyebrow = "LIVE",
                                alt = Xtream.liveUrl(c, ch.id, hls = true), liveId = ch.id, liveCat = ch.category); true } ?: false
                        "url" -> body.optString("url").trim().takeIf { it.startsWith("http") }?.let { u ->
                            PlayerActivity.play(c, u, body.optString("name").ifBlank { "From your iPhone" }, live = false, eyebrow = "FROM IPHONE"); true } ?: false
                        else -> false
                    }
                } catch (e: Exception) { false }
                json(out, JSONObject().put("ok", ok))
            }
            // ---- Now Playing: what's on, scrub, play/pause, subtitles and audio - plus whether the TV wants typing ----
            "/api/now" -> {
                val want = HomeRedirectService.instance?.let { s -> onMain { s.typingWanted() } } ?: (false to "")
                val o = if (req.body.isNotEmpty()) JSONObject().put("ok", onMain { PlayerActivity.onScreen?.get()?.remoteControl(body) } == true)
                    else onMain { PlayerActivity.onScreen?.get()?.takeIf { !it.isFinishing }?.remoteState() } ?: JSONObject().put("active", false)
                json(out, o.put("typing", want.first).put("hint", want.second))
            }
            // ---- Lights: everything in Settings › Magic Hue Lights, plus Night Mode ----
            "/api/lights" -> {
                val c = context
                if (body.has("set")) {
                    when (body.optString("set")) {
                        "on" -> MagicHue.setSync(c, body.optBoolean("value"))
                        "style" -> Prefs.setInt(c, "lights_style", body.optInt("value").coerceIn(0, 2))
                        "bright" -> Prefs.setInt(c, "lights_bright", body.optInt("value").coerceIn(0, 2))
                        "wallpaper" -> Prefs.setBool(c, "wallpaper_lights", body.optBoolean("value"))
                        "fourd" -> Prefs.setBool(c, "fourd_mode", body.optBoolean("value"))
                        "cinemaroom" -> Prefs.setBool(c, "cinema_room", body.optBoolean("value"))
                        "night" -> { val v = body.optBoolean("value"); onMain { NightDim.set(c, v) } }
                        "calibrate" -> start(Intent(c, CalibrateActivity::class.java))
                    }
                }
                json(out, JSONObject().put("found", MagicHue.ip(c) != null).put("on", MagicHue.syncOn(c))
                    .put("style", Prefs.int(c, "lights_style", 1)).put("bright", Prefs.int(c, "lights_bright", 1))
                    .put("wallpaper", Prefs.bool(c, "wallpaper_lights", true)).put("fourd", Prefs.bool(c, "fourd_mode", true))
                    .put("cinemaroom", Prefs.bool(c, "cinema_room", true)).put("night", NightDim.isOn(c)).put("down", RoomLights.isDown(c)))
            }
            // ---- Live TV on the phone: your favourites and the main channels, with what's on now ----
            "/api/live" -> {
                val c = context
                val arr = JSONArray()
                if (Xtream.isSetUp(c)) try {
                    val all = Xtream.best(Xtream.live(c))
                    val favs = Xtream.favourites(c)
                    val main = listOf("BBC One", "BBC Two", "ITV1", "Channel 4", "Channel 5", "ITV2", "BBC Three", "E4", "Sky News", "BBC News",
                        "Dave", "ITV3", "ITV4", "Film4", "More4", "5 USA", "Sky Sports Main Event", "Sky Sports Premier League", "TNT Sports 1", "Sky Cinema Premiere")
                    fun norm(s: String) = TextMatch.normalise(s)
                    val picked = LinkedHashMap<Int, Xtream.Live>()
                    all.filter { it.id in favs }.forEach { picked[it.id] = it }
                    for (n in main) all.firstOrNull { norm(it.name) == norm(n) || norm(it.name).endsWith(norm(n)) }?.let { picked.putIfAbsent(it.id, it) }
                    for (ch in picked.values.take(40)) arr.put(JSONObject().put("id", ch.id).put("name", ch.name).put("icon", ch.icon ?: "")
                        .put("fav", ch.id in favs).put("now", try { LiveGuide.nowNext(c, ch.name) ?: "" } catch (e: Exception) { "" }))
                } catch (e: Exception) {}
                json(out, JSONObject().put("channels", arr))
            }
            // ---- Recording rules in plain English ----
            "/api/rules" -> {
                val c = context
                when {
                    body.has("add") -> RecordingRules.add(c, body.optString("add").take(200))
                    body.has("remove") -> RecordingRules.remove(c, body.optString("remove"))
                }
                if (body.has("add")) Thread { try { AwayRemote.publish(c) } catch (_: Exception) {} }.start()
                json(out, JSONObject().put("rules", JSONArray().apply { RecordingRules.list(c).forEach { put(JSONObject().put("id", it.id).put("text", it.text)
                    .put("looks", (it.all + it.any.take(3)).joinToString(", "))) } }))
            }
            // ---- Away from home ----
            "/api/away/setup" -> json(out, JSONObject().put("url", AwayRemote.enable(context))
                .put("arrive", "http://${ip(context) ?: "TV-IP"}:$PORT/api/arrive?k=$key"))
            // The iPhone Shortcut runs this when the phone joins the home Wi-Fi.
            "/api/arrive" -> json(out, JSONObject().put("result", onMain { AwayRemote.arrive(context) } ?: "busy"))
            "/api/photos/clear" -> {
                WallpaperLibrary.albumDir(context).listFiles()?.forEach { it.delete() }
                json(out, JSONObject().put("ok", true))
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    /** Runs [block] on the TV's main thread and waits (up to 2 s) for the answer. */
    private fun <T> onMain(block: () -> T): T? {
        var r: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { r = try { block() } catch (e: Exception) { null }; latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return r
    }

    // ---------- Actions ----------

    private fun start(intent: Intent): Boolean = try {
        // Another app: open it like its icon would. One of our own screens (the pairing code, players): just open it -
        // with "like an icon" Fire TV only brought the launcher forward and the code screen never appeared.
        if (intent.component?.packageName == context.packageName) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    } catch (e: Exception) { false }

    private fun press(key: String): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        when (key) {
            // Volume goes to the soundbar/TV over HDMI, the way the real remote's volume buttons do: a real volume key
            // press (the system passes it on over HDMI-CEC). The Chromecast's own volume is fixed with HDMI audio, so
            // just turning that (as before) did nothing.
            "volup", "voldown", "mute" -> {
                val code = when (key) { "volup" -> KeyEvent.KEYCODE_VOLUME_UP; "voldown" -> KeyEvent.KEYCODE_VOLUME_DOWN; else -> KeyEvent.KEYCODE_VOLUME_MUTE }
                val pressed = try { Instrumentation().sendKeyDownUpSync(code); true } catch (e: Exception) { false }
                if (!pressed) {
                    val dir = when (key) { "volup" -> AudioManager.ADJUST_RAISE; "voldown" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                    audio.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }
                return true
            }
            "playpause", "next", "previous" -> {
                val code = when (key) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return true
            }
        }
        // Anywhere on the TV, through the accessibility service…
        val service = HomeRedirectService.instance
        if (service != null) {
            var ok = false
            val done = java.util.concurrent.CountDownLatch(1)
            main.post { ok = try { service.press(key) } catch (e: Exception) { false }; done.countDown() }
            done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (ok) return true
        }
        if (key == "home") return start(Intent(context, MainActivity::class.java))
        // …or, without it, inside Minimal Launcher's own screens.
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "select" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            else -> return false
        }
        if (Foreground.activity == null) return false
        return try { Instrumentation().sendKeyDownUpSync(code); true } catch (e: Exception) { false }
    }

    private fun type(text: String, replace: Boolean, enter: Boolean): Boolean {
        val service = HomeRedirectService.instance
        var ok = false
        if (service != null && text.isNotEmpty()) {
            val done = java.util.concurrent.CountDownLatch(1)
            main.post { ok = try { service.type(text, replace) } catch (e: Exception) { false }; done.countDown() }
            done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (!ok && Foreground.activity != null) {
            ok = try {
                if (text.isNotEmpty()) Instrumentation().sendStringSync(text)
                true
            } catch (e: Exception) { false }
        }
        if (enter) {
            try { Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER) } catch (e: Exception) {}
            if (!ok) ok = true
        }
        return ok
    }
}

const val EXTRA_CINEMA = "cinema"

/** Remembers which of the launcher's screens is in front (for the remote's fallback). */
object Foreground : Application.ActivityLifecycleCallbacks {
    private var ref: WeakReference<Activity>? = null
    val activity: Activity? get() = ref?.get()
    override fun onActivityResumed(a: Activity) { ref = WeakReference(a) }
    override fun onActivityPaused(a: Activity) { if (ref?.get() === a) ref = null }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // If the launcher ever crashes, keep what went wrong so it can be fixed (sent next time, only with Share with Claude on).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { getSharedPreferences("launcher_settings", Context.MODE_PRIVATE).edit()
                .putString("last_crash", "Build ${try { Updater.installedBuild(this) } catch (_: Exception) { 0 }} · ${t.name}\n" + android.util.Log.getStackTraceString(e).take(6000)).commit() } catch (_: Exception) {}
            // A background task going wrong (a download, a check, a lookup) shouldn't take the whole launcher down with it:
            // it's reported straight away and the app carries on. Only a fault on the main screen closes it.
            if (t !== android.os.Looper.getMainLooper().thread && e !is OutOfMemoryError) {
                try { Diag.send(this, "crash", "Background (kept running) · ${t.name}\n" + android.util.Log.getStackTraceString(e).take(4000)) } catch (_: Throwable) {}
                try { getSharedPreferences("launcher_settings", Context.MODE_PRIVATE).edit().remove("last_crash").apply() } catch (_: Exception) {}
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(t, e)
        }
        registerActivityLifecycleCallbacks(Foreground)
        // Focus Highlight follows focus in every screen.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) { FocusRing.install(applicationContext, a.window) }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // Keep the cached app list in step with installs, removals and updates.
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = AppListHelper.invalidate()
        }, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        // Warm the list off the main thread so the home screen opens fast.
        Thread {
            try {
                val apps = AppListHelper.loadLaunchableTvApps(this)
                // Draw the home screen's app tiles now, so they're ready before the home screen asks for them.
                val d = resources.displayMetrics.density
                fun px(v: Int) = (v * d + 0.5f).toInt()
                RecentAppsTracker.getRecent(this, apps, limit = 3).forEach { AppTiles.forApp(this, it.app, px(100), px(60)) }
                val byPkg = apps.associateBy { it.packageName }
                ProfileManager.getDockOrder(this).mapNotNull { byPkg[it] }.ifEmpty { apps.take(6) }.take(9)
                    .forEach { AppTiles.forApp(this, it, px(92), px((92 / AppTiles.RATIO).toInt())) }
            } catch (e: Exception) {}
        }.start()
        // Waking the TV from standby asks "Who's watching?" again.
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { Accounts.askWho = true }
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_ON), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // Scheduled TiviMate recordings survive a restart.
        try { TiviRecorder.rearm(this) } catch (e: Exception) {}
        Diag.start(this)
        Prefs.string(this, "last_crash")?.let { Diag.send(this, "crash", it); Prefs.setString(this, "last_crash", null) }
        try { Xtream.warm(this) } catch (e: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Updater.maybeUpdate(this) }, 25_000)      // films, series and channels ready before you ask
        // A few seconds after start (not during it), get the TV guide and the streaming services
        // ready, so Live TV and Watch open instantly instead of loading.
        Handler(Looper.getMainLooper()).postDelayed({
            try { LiveGuide.load(this) {} } catch (e: Exception) {}
            Thread { try { WatchCatalog.featuredProviders(this) } catch (e: Exception) {} }.start()
        }, 5000)
    }
}
