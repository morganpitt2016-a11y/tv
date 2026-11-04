package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "Share with Claude": while this is switched on, the TV sends its tuning log and a snapshot of
 * TiviMate's screen (nothing from other apps, never any passwords or logins) to a private online
 * mailbox, and checks it every few seconds for a request such as "try tuning to ITV1" so Claude
 * can see what really happens. The mailbox key below is a public, write-only key: it can add
 * messages and read the open requests, and nothing else (the database refuses everything
 * else). Turn it off in Settings and nothing is sent or checked.
 */
object Diag {
    private const val BASE = "https://ligqohydmupgahmlxqvy.supabase.co/rest/v1"
    private const val KEY = "sb_publishable_q0N-x_5djDRRq3ZHLfGAqQ_8_4_939Y"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var looping = false

    fun enabled(c: Context) = Prefs.bool(c, "diag_on", false)

    /** A short code for this TV, so messages can be told apart. */
    @Synchronized fun deviceId(c: Context): String {
        Prefs.string(c, "diag_id")?.let { return it }
        val id = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        Prefs.setString(c, "diag_id", id)
        return id
    }

    fun setEnabled(c: Context, on: Boolean) {
        Prefs.setBool(c, "diag_on", on)
        if (on) { start(c); send(c, "hello", info(c)) }
    }

    /** Start checking for requests (if it's switched on). Safe to call again. */
    fun start(c: Context) {
        if (!enabled(c) || looping) return
        looping = true
        val app = c.applicationContext
        thread(isDaemon = true, name = "diag") {
            try {
                while (enabled(app)) {
                    try { poll(app) } catch (e: Exception) {}
                    Thread.sleep(5000)
                }
            } catch (e: InterruptedException) {
            } finally { looping = false }
        }
    }

    /** Send something to the mailbox (only if it's switched on). */
    fun send(c: Context, kind: String, body: String) {
        if (!enabled(c)) return
        val app = c.applicationContext
        thread(isDaemon = true) { try { post(app, kind, body) } catch (e: Exception) {} }
    }

    // ---------- Talking to the mailbox ----------

    private fun call(method: String, path: String, json: String?, extra: Map<String, String> = emptyMap()): String {
        val conn = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return text
        } finally { conn.disconnect() }
    }

    private fun post(app: Context, kind: String, body: String) {
        val row = JSONObject().put("device", deviceId(app)).put("kind", kind).put("body", body.take(190_000))
        call("POST", "tv_diag", row.toString(), mapOf("Prefer" to "return=minimal"))
    }

    private fun poll(app: Context) {
        val id = deviceId(app)
        val text = call("GET", "tv_cmd?select=id,cmd&done=eq.false&or=(device.eq.$id,device.eq.all)&order=id.asc&limit=5", null)
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Tick it off first, so a request that crashes can't repeat forever.
            try { call("POST", "rpc/tv_cmd_done", JSONObject().put("cmd_id", o.getLong("id")).toString()) } catch (e: Exception) { continue }
            try { run(app, o.getString("cmd")) } catch (e: Exception) { send(app, "error", "${o.getString("cmd")}: ${e.javaClass.simpleName} ${e.message}") }
        }
    }

    // ---------- What I can ask for ----------

    private fun onMain(block: () -> String): String {
        var out = ""
        val latch = CountDownLatch(1)
        main.post { out = try { block() } catch (e: Exception) { "Error: ${e.javaClass.simpleName}" }; latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    private fun run(app: Context, cmd: String) {
        when {
            cmd == "info" -> send(app, "info", info(app))
            cmd == "tunelog" -> send(app, "tunelog", TiviTuner.logText())
            cmd == "perf" -> send(app, "perf", Perf.text())
            cmd == "screen" -> send(app, "screen", onMain { TiviTuner.tiviScreen() })
            cmd == "speedtest" -> main.post { Perf.runSpeedTest(app) { send(app, "speedtest", it) } }
            cmd == "xtream_from_guide" -> send(app, "xtream", try { "Signed in from the guide link: " + Xtream.signInFromGuideLink(app) } catch (e: Exception) { "Couldn't sign in from the guide link: ${e.message}" })
            cmd.startsWith("xfilm:") -> thread(isDaemon = true) {
                val title = cmd.removePrefix("xfilm:").trim()
                val t0 = android.os.SystemClock.uptimeMillis()
                val v = try { Xtream.findVod(app, title, 0) } catch (e: Exception) { null }
                val ms = android.os.SystemClock.uptimeMillis() - t0
                if (v == null) send(app, "xfilm", "\"$title\" not found in the provider's films ($ms ms)")
                else {
                    send(app, "xfilm", "Found \"$title\" in $ms ms - starting the player")
                    main.post { PlayerActivity.play(app, Xtream.vodUrl(app, v), title, live = false, eyebrow = "FILM") }
                }
            }
            cmd == "update" -> Updater.maybeUpdate(app, force = true, asked = true) { send(app, "update", it) }
            cmd == "xcheck" -> thread(isDaemon = true) {
                // The result only - never the server, username or password.
                fun clean(e: Exception) = "${e.javaClass.simpleName}: ${e.message.orEmpty().replace(Regex("https?://\\S+"), "<link>").take(200)}"
                val login = try { "OK - " + Xtream.check(app) } catch (e: Exception) { clean(e) }
                val cats = try {
                    val raw = Xtream.gated { Net.get(Xtream.apiForDiag(app, "get_live_categories"), mapOf("User-Agent" to Xtream.PLAYER_AGENT), timeoutMs = 15_000) }
                    val t = raw.trim()
                    "${t.length} chars, starts with '${t.take(1)}', groups=${try { org.json.JSONArray(t).length() } catch (e: Exception) { -1 }}"
                } catch (e: Exception) { clean(e) }
                send(app, "xcheck", "Login: $login\nLive groups: $cats")
            }
            cmd == "livecats" -> thread(isDaemon = true) {
                val t0 = android.os.SystemClock.uptimeMillis()
                val cats = try { Xtream.liveCategories(app) } catch (e: Exception) { emptyMap() }
                val t1 = android.os.SystemClock.uptimeMillis()
                val first = cats.entries.firstOrNull { it.value.contains("entertainment", true) } ?: cats.entries.firstOrNull()
                val chans = if (first == null) emptyList() else try { Xtream.liveInCategory(app, first.key) } catch (e: Exception) { emptyList() }
                val t2 = android.os.SystemClock.uptimeMillis()
                send(app, "livecats", "Groups: ${cats.size} in ${t1 - t0} ms\nNames: ${cats.values.take(40)}\n" +
                    "First group \"${first?.value}\": ${chans.size} channels in ${t2 - t1} ms\nSample: ${chans.take(8).map { it.name }}")
            }
            cmd.startsWith("epg:") -> thread(isDaemon = true) {
                val name = cmd.removePrefix("epg:").trim()
                val cats = try { Xtream.liveCategories(app) } catch (e: Exception) { emptyMap() }
                val ch = cats.keys.firstNotNullOfOrNull { id -> try { Xtream.liveInCategory(app, id).firstOrNull { it.name.contains(name, true) } } catch (e: Exception) { null } }
                if (ch == null) { send(app, "epg", "No channel matching \"$name\""); return@thread }
                val t0 = android.os.SystemClock.uptimeMillis()
                val raw = try { Xtream.shortEpg(app, ch.id, 8) } catch (e: Exception) { "error ${e.javaClass.simpleName} ${e.message}" }
                val g = try { ProviderEpg.cached(ch.id) ?: run { var r: LiveGuide.Channel? = null; val l = java.util.concurrent.CountDownLatch(1); ProviderEpg.fetch(app, ch) { r = it; l.countDown() }; l.await(10, java.util.concurrent.TimeUnit.SECONDS); r } } catch (e: Exception) { null }
                send(app, "epg", "${ch.name} (id ${ch.id}): ${g?.programmes?.size ?: 0} programmes in ${android.os.SystemClock.uptimeMillis() - t0} ms\n" +
                    (g?.programmes?.take(4)?.joinToString("\n") { "${java.util.Date(it.start)} ${it.title}" } ?: "raw: ${raw.take(400)}"))
            }
            cmd == "ui" -> send(app, "ui", onMain { HomeRedirectService.instance?.uiSummary() ?: "(accessibility service not running)" })
            cmd.startsWith("do:") -> thread(isDaemon = true) {
                // e.g. do:down,down,ok,wait  - presses the remote's buttons in turn, then reports what is on screen.
                val svc = HomeRedirectService.instance
                for (step in cmd.removePrefix("do:").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
                    if (step == "wait") { Thread.sleep(1500); continue }
                    val key = when (step) { "ok", "select", "enter" -> "select"; else -> step }
                    main.post { svc?.press(key) }
                    Thread.sleep(550)
                }
                Thread.sleep(700)
                send(app, "ui", onMain { svc?.uiSummary() ?: "(accessibility service not running)" })
            }
            cmd == "audio" -> send(app, "audio", TiviTuner.audioReport(app))
            cmd == "recs" -> send(app, "recs", TiviRecorder.describe(app))
            cmd == "clearrec" || cmd.startsWith("clearrec:") -> {
                val n = TiviRecorder.clear(app, cmd.substringAfter(":", "").trim().ifEmpty { null })
                send(app, "recs", "Cleared $n scheduled recordings and the matching series rules.\n" + TiviRecorder.describe(app))
            }
            cmd == "home" -> main.post { HomeRedirectService.instance?.press("home") }
            cmd == "back" -> main.post { HomeRedirectService.instance?.press("back") }
            cmd.startsWith("tune:") -> tune(app, cmd.removePrefix("tune:").trim())
            cmd.startsWith("film:") -> main.post { TiviTuner.playVod(app, cmd.removePrefix("film:").trim(), 0, false) }
            else -> send(app, "error", "I don't know the request \"$cmd\"")
        }
    }

    /** Pick a channel exactly as Live TV does; the result is sent when it finishes. */
    private fun tune(app: Context, channel: String) {
        val intent = LiveGuide.launchIntent(app, LiveGuide.TIVIMATE)
        if (intent == null) { send(app, "tune", "TiviMate isn't installed"); return }
        main.post {
            if (!TiviTuner.canTune()) { send(app, "tune", "The accessibility service isn't running, so nothing can be picked"); return@post }
            try { app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            val restart = { try { app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) } catch (e: Exception) {} }
            TiviTuner.tune(channel, restart) { }          // the result goes to the mailbox by itself
        }
    }

    private fun info(app: Context): String {
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" }
        val tivi = try { app.packageManager.getPackageInfo(LiveGuide.TIVIMATE, 0).versionName } catch (e: Exception) { "not installed" }
        return listOf(
            "Device code: ${deviceId(app)}",
            "Model: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Launcher version: $version",
            "TiviMate: $tivi",
            "Accessibility service running: ${HomeRedirectService.instance != null}",
            "Can pick channels: ${TiviTuner.canTune()}",
            "Provider login set up: ${Xtream.isSetUp(app)}",
        ).joinToString("\n")
    }
}
