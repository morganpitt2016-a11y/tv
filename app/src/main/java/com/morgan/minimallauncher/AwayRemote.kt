package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The away remote: record something on this TV from anywhere, and have it waiting - TV on, paused at the start -
 * when you get home.
 *
 *  - This TV has its own secret away pass (only its SHA-256 is kept in the cloud). The away page on your phone holds
 *    the pass; the `tv-away` relay checks it before anything reaches this TV.
 *  - Every half hour the TV publishes a small TV guide (your favourites and the main channels, the next day or so)
 *    and what it's set to record.
 *  - A record request arrives in the TV's command mailbox (see [Diag]) and is scheduled like any other recording.
 *  - "Ready when I'm home": when your iPhone joins the home Wi-Fi, a Shortcut opens /api/arrive on the TV, which wakes
 *    it and opens that recording at the start, paused.
 */
object AwayRemote {
    private const val RELAY = "https://ligqohydmupgahmlxqvy.supabase.co/functions/v1/tv-away"
    const val PAGE = "https://morganpitt2016-a11y.github.io/tv/away/"
    private const val EVERY_MS = 30 * 60_000L
    private val thread = HandlerThread("away").apply { start() }
    private val h = Handler(thread.looper)
    @Volatile private var started = false

    fun token(c: Context): String {
        Prefs.string(c, "away_token")?.takeIf { it.length >= 32 }?.let { return it }
        val rnd = java.security.SecureRandom()
        val t = (1..40).map { "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"[rnd.nextInt(55)] }.joinToString("")
        Prefs.setString(c, "away_token", t)
        return t
    }

    /** The link that sets a phone up (the pass is after the #, so it never leaves the phone except to the relay). */
    fun setupLink(c: Context): String = "$PAGE#d=${Diag.deviceId(c)}&t=${token(c)}&n=${java.net.URLEncoder.encode(tvName(c), "UTF-8").replace("+", "%20")}"

    fun tvName(c: Context) = (try { android.provider.Settings.Global.getString(c.contentResolver, "device_name") } catch (e: Exception) { null })?.takeIf { it.isNotBlank() } ?: "TV"

    /** Register the pass and keep the guide fresh (only once someone has set up an away remote). */
    fun start(c: Context) {
        val app = c.applicationContext
        if (started) return
        started = true
        h.post(object : Runnable {
            override fun run() {
                try { register(app); publish(app); fetchShields(app) } catch (_: Exception) {}
                h.postDelayed(this, EVERY_MS)
            }
        })
    }

    /** The home remote's "Set up away remote": turn it on, register, publish, and hand back the link. */
    fun enable(c: Context): String {
        val app = c.applicationContext
        Prefs.setBool(app, "away_on", true)
        h.post { try { register(app); publish(app) } catch (_: Exception) {} }
        return setupLink(app)
    }

    private fun call(body: JSONObject): JSONObject {
        val conn = URL(RELAY).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        } finally { conn.disconnect() }
    }

    private fun register(c: Context) {
        val r = call(JSONObject().put("op", "register").put("device", Diag.deviceId(c)).put("token", token(c)))
        if (r.optBoolean("ok")) Prefs.setBool(c, "away_ok", true) else Diag.send(c, "away", "away pass not registered: ${r.optString("error")}")
    }

    /** A small guide for the away page: favourites and the main channels, about the next day. */
    fun publish(c: Context) {
        val app = c.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        var list: List<LiveGuide.Channel>? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { LiveGuide.load(app) { l -> list = l; latch.countDown() } }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        val all = list ?: return
        // Plain-English recording rules: set anything new that matches.
        try { RecordingRules.apply(app, all) } catch (_: Exception) {}
        if (!Prefs.bool(app, "away_on", false)) return
        val main = listOf("BBC One", "BBC Two", "ITV1", "Channel 4", "Channel 5", "ITV2", "BBC Three", "E4", "BBC Four", "ITV3", "ITV4", "Dave",
            "Film4", "More4", "5 USA", "Sky Sports Main Event", "Sky Sports Premier League", "Sky Sports Action", "Sky Sports Football",
            "TNT Sports 1", "TNT Sports 2", "Premier Sports 1", "Sky Showcase", "Sky Max", "Sky Atlantic", "Sky Cinema Premiere", "Sky News", "BBC News")
        val picked = LinkedHashMap<String, LiveGuide.Channel>()
        try { val favs = Xtream.favourites(app); Xtream.live(app).filter { it.id in favs }.forEach { ch -> LiveGuide.guideFor(ch.name)?.let { picked.putIfAbsent(it.name, it) } } } catch (_: Exception) {}
        val norm = { s: String -> TextMatch.normalise(s) }
        for (n in main) all.firstOrNull { norm(it.name) == norm(n) }?.let { picked.putIfAbsent(it.name, it) }
        val now = System.currentTimeMillis()
        val chans = JSONArray()
        for (ch in picked.values.take(40)) {
            val ps = JSONArray()
            ch.programmes.filter { it.stop > now && it.start < now + 30 * 3600_000L }.take(48).forEach { p ->
                ps.put(JSONArray().put(p.title.take(80)).put(p.start).put(p.stop))
            }
            if (ps.length() > 0) chans.put(JSONObject().put("n", ch.name).put("l", ch.logo ?: "").put("p", ps))
        }
        val sched = JSONArray()
        TiviRecorder.list(app).forEach { sched.put(JSONObject().put("t", it.title).put("c", it.channel).put("s", it.start)) }
        val rules = JSONArray(); RecordingRules.list(app).forEach { rules.put(JSONObject().put("id", it.id).put("text", it.text)) }
        val guide = JSONObject().put("tv", tvName(app)).put("at", now).put("channels", chans).put("scheduled", sched).put("rules", rules)
            .put("ready", Prefs.string(app, "away_ready")?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject.NULL)
        call(JSONObject().put("op", "guide").put("device", Diag.deviceId(app)).put("token", token(app)).put("guide", guide))
    }

    /** A record request (or a new rule, or a rule to delete) from the away page, through the command mailbox. */
    fun record(c: Context, json: String) {
        val app = c.applicationContext
        val r = JSONObject(json)
        if (r.has("rule")) { h.post { try { RecordingRules.add(app, r.getString("rule")); publish(app) } catch (_: Exception) {} }; return }
        if (r.has("unrule")) { RecordingRules.remove(app, r.getString("unrule")); h.post { try { publish(app) } catch (_: Exception) {} }; return }
        val name = r.optString("channel"); val start = r.optLong("start"); val stop = r.optLong("stop"); val title = r.optString("title")
        val run = {
            val ch = LiveGuide.channelFor(name) ?: LiveGuide.Channel("", name, null, emptyList())
            val p = ch.programmes.firstOrNull { kotlin.math.abs(it.start - start) < 60_000 } ?: LiveGuide.Programme(title, "", start, stop, null, "")
            if (p.stop <= System.currentTimeMillis()) Diag.send(app, "away", "too late to record \"$title\" (it's finished)")
            else {
                // Already on: record from now. Later: schedule it like any other recording.
                if (p.start <= System.currentTimeMillis()) Recordings.start(app, ch.name, p.title, p.stop, p.image)
                else TiviRecorder.schedule(app, ch, p)
                if (r.optBoolean("ready")) Prefs.setString(app, "away_ready", JSONObject().put("title", p.title).put("channel", ch.name).put("start", p.start).toString())
                Diag.send(app, "away", "recording \"${p.title}\" on ${ch.name}${if (r.optBoolean("ready")) " - ready when you're home" else ""}")
            }
            h.post { try { publish(app) } catch (_: Exception) {} }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { LiveGuide.load(app) { run() } }
    }

    // ---------- Spoiler Shield, shared by the family's TVs ----------

    fun shareShield(c: Context, e: Shield.Entry) {
        val app = c.applicationContext
        h.post { try { call(JSONObject().put("op", "shield").put("device", Diag.deviceId(app)).put("token", token(app))
            .put("id", e.id).put("title", e.title).put("terms", JSONArray(e.terms)).put("until", e.until)) } catch (_: Exception) {} }
    }

    private fun fetchShields(c: Context) {
        val r = call(JSONObject().put("op", "shields").put("device", Diag.deviceId(c)).put("token", token(c)))
        val a = r.optJSONArray("shields") ?: return
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val terms = o.optJSONArray("terms")?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty()
            Shield.add(c, Shield.Entry(o.getString("id"), o.optString("title"), terms, o.optLong("until")))
        }
    }

    /** Home again (the iPhone joined the Wi-Fi): wake the TV and open the waiting recording at the start, paused. */
    fun arrive(c: Context): String {
        val app = c.applicationContext
        val want = Prefs.string(app, "away_ready")?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return "nothing waiting"
        val rec = Recordings.list(app).filter { it.title == want.optString("title") && java.io.File(it.file).exists() }
            .minByOrNull { kotlin.math.abs(it.start - want.optLong("start")) } ?: return "the recording isn't there yet"
        @Suppress("DEPRECATION")
        try {
            app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "minimal:arrive").acquire(15_000)
        } catch (_: Exception) {}
        Prefs.setString(app, "away_ready", null)
        PlayerActivity.startPaused = true
        PlayerActivity.play(app, android.net.Uri.fromFile(java.io.File(rec.file)).toString(), rec.title, live = false,
            subtitle = rec.channel, eyebrow = "RECORDING", fromStart = true, image = rec.image)
        Diag.send(app, "away", "home: opened \"${rec.title}\" paused at the start")
        h.post { try { publish(app) } catch (_: Exception) {} }
        return "ok"
    }
}
