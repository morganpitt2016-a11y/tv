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

        fun key(c: Context): String {
            Prefs.string(c, "remote_key")?.let { return it }
            val k = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
            Prefs.setString(c, "remote_key", k)
            return k
        }

        fun ip(c: Context): String? = try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
                ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
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
                thread(isDaemon = true) { handle(client) }
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
            val out = sock.getOutputStream()
            val req = try { read(input) } catch (e: Exception) { null } ?: return
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
        if (req.path == "/" || req.path == "/index.html") {
            if (!authorised) {
                send(out, 401, "text/html; charset=utf-8", ("<html><body style='font:17px -apple-system;background:#000;color:#fff;padding:40px'>" +
                    "Scan the QR code in Minimal Launcher › Settings › iPhone Remote to connect.</body></html>").toByteArray())
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
                .put("fullControl", HomeRedirectService.instance != null)
                .put("android", android.os.Build.VERSION.SDK_INT)
                .put("city", Weather.getCity(context) ?: "")
                .put("calendar", CalendarFeed.getUrl(context) != null)
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
            "/api/photos/clear" -> {
                WallpaperLibrary.albumDir(context).listFiles()?.forEach { it.delete() }
                json(out, JSONObject().put("ok", true))
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    // ---------- Actions ----------

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    } catch (e: Exception) { false }

    private fun press(key: String): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        when (key) {
            "volup" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true }
            "voldown" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true }
            "mute" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return true }
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
        registerActivityLifecycleCallbacks(Foreground)
    }
}
