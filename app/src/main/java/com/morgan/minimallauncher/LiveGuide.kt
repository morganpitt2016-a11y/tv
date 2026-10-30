package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Live TV guide for UK free-to-air channels, from the public Freeview
 * EPG (github.com/dp247/Freeview-EPG, rebuilt every 8 hours). The 20 MB
 * XML file is streamed and boiled down to the next ~12 hours of TV
 * channels, kept on the TV and refreshed every few hours.
 *
 * Programmes play in TiviMate (it can't be told which channel to open,
 * so it opens on your last channel) or, for BBC / ITV / Channel 4 / 5,
 * in their own apps.
 */
object LiveGuide {
    private const val EPG_URL = "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml"
    /** The same guide, pre-shrunk to ~1 MB of JSON every few hours by .github/workflows/guide.yml. */
    private const val SMALL_URL = "https://raw.githubusercontent.com/morganpitt2016-a11y/tv/guide-data/guide.json"
    private const val MAX_AGE = 4 * 3600_000L
    const val TIVIMATE = "ar.tvplayer.tv"
    private val main = Handler(Looper.getMainLooper())

    data class Programme(val title: String, val desc: String, val start: Long, val stop: Long, val image: String?, val episode: String) {
        fun isOn(now: Long = System.currentTimeMillis()) = now in start until stop
        fun progress(now: Long = System.currentTimeMillis()) = ((now - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    data class Channel(val id: String, val name: String, val logo: String?, val programmes: List<Programme>) {
        fun now(t: Long = System.currentTimeMillis()) = programmes.firstOrNull { it.isOn(t) }
        fun upcoming(t: Long = System.currentTimeMillis()) = programmes.filter { it.stop > t }
    }

    @Volatile private var memory: List<Channel>? = null
    @Volatile private var loading = false

    private fun cacheFile(c: Context) = File(c.filesDir, if (customUrl(c) != null) "guide_custom.json" else "guide.json")

    /** Your own guide link (e.g. the EPG link in TiviMate › Settings › EPG), so names match TiviMate's channels. */
    fun customUrl(c: Context): String? = Prefs.string(c, "epg_url")?.takeIf { it.isNotBlank() }

    fun setCustomUrl(c: Context, url: String?) {
        Prefs.setString(c, "epg_url", url?.trim()?.takeIf { it.isNotBlank() })
        memory = null
        File(c.filesDir, "guide_custom.json").delete()
    }

    /** The guide, from memory/disk straight away if fresh enough, else downloaded. Main-thread callback. */
    fun load(c: Context, done: (List<Channel>?) -> Unit) {
        val app = c.applicationContext
        val f = cacheFile(app)
        memory?.let { if (System.currentTimeMillis() - f.lastModified() < MAX_AGE) { done(it); return } }
        Thread {
            var result: List<Channel>? = null
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) result = readCache(f)
            if (result == null && !loading) {
                loading = true
                try {
                    val custom = customUrl(app)
                    result = if (custom != null) downloadXml(custom, custom = true) else download()
                    writeCache(f, result)
                } catch (e: Exception) {
                    result = if (f.exists()) readCache(f) else null
                } finally { loading = false }
            }
            if (result != null) memory = result
            main.post { done(result?.let { trimmed(it) }) }
        }.start()
    }

    /** Drop finished programmes and channels with nothing left. */
    private fun trimmed(list: List<Channel>): List<Channel> {
        val now = System.currentTimeMillis()
        return list.map { it.copy(programmes = it.programmes.filter { p -> p.stop > now }) }.filter { it.programmes.isNotEmpty() }
    }

    // ---------- Download + parse ----------

    private fun download(): List<Channel> {
        // Fast path: the small pre-built file; fall back to the full XML.
        try {
            val json = JSONObject(Net.get(SMALL_URL, timeoutMs = 30_000))
            val arr = json.getJSONArray("channels")
            val list = parseJsonChannels(arr)
            if (list.isNotEmpty()) return order(dedupeRegions(list.filter { !isRadio(it.id, it.name) }.map { it.copy(name = cleanName(it.name)) }))
        } catch (e: Exception) {}
        return downloadXml()
    }

    private fun parseJsonChannels(arr: JSONArray): List<Channel> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val ps = o.getJSONArray("p")
        Channel(o.getString("id"), o.getString("name"), o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
            (0 until ps.length()).map { j ->
                val p = ps.getJSONObject(j)
                Programme(p.optString("t"), p.optString("d"), p.getLong("s"), p.getLong("e"),
                    p.optString("i").takeIf { it.isNotBlank() && it != "null" }, p.optString("n"))
            })
    }

    private fun downloadXml(url: String = EPG_URL, custom: Boolean = false): List<Channel> {
        var current = URL(url)
        // Follow redirects ourselves (including http <-> https, which Android won't).
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(current, conn.getHeaderField("Location") ?: throw java.io.IOException("Bad redirect"))
                    return@repeat
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                // Guides are often gzipped (.xml.gz): spot it from the first two bytes.
                val raw = java.io.BufferedInputStream(conn.inputStream, 64 * 1024)
                raw.mark(4)
                val gz = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                val input = if (gz) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
                return parse(input, custom)
            } finally { conn.disconnect() }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun parse(input: java.io.InputStream, custom: Boolean = false): List<Channel> {
        val now = System.currentTimeMillis()
        val from = now - 30 * 60_000L
        val to = now + 14 * 3600_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val names = LinkedHashMap<String, Pair<String, String?>>()
        val progs = HashMap<String, MutableList<Programme>>()

        val xp = Xml.newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(input.buffered(), null)
        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (xp.name) {
                    "channel" -> {
                        val id = xp.getAttributeValue(null, "id")
                        var name = id; var logo: String? = null
                        while (true) {
                            val e = xp.next()
                            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xp.name == "channel")) break
                            if (e != XmlPullParser.START_TAG) continue
                            when (xp.name) {
                                "display-name" -> name = xp.nextText().trim()
                                "icon" -> logo = xp.getAttributeValue(null, "src")
                            }
                        }
                        names[id] = name to logo
                    }
                    "programme" -> {
                        val ch = xp.getAttributeValue(null, "channel")
                        val start = try { fmt.parse(xp.getAttributeValue(null, "start"))!!.time } catch (e: Exception) { 0L }
                        val stop = try { fmt.parse(xp.getAttributeValue(null, "stop"))!!.time } catch (e: Exception) { 0L }
                        val keep = stop > from && start < to && !isRadio(ch, names[ch]?.first)
                        var title = ""; var desc = ""; var image: String? = null; var ep = ""
                        while (true) {
                            val e = xp.next()
                            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xp.name == "programme")) break
                            if (!keep || e != XmlPullParser.START_TAG) continue
                            when (xp.name) {
                                "title" -> title = xp.nextText().trim()
                                "desc" -> desc = xp.nextText().trim()
                                "icon" -> image = xp.getAttributeValue(null, "src")
                                "episode-num" -> if (xp.getAttributeValue(null, "system") == "onscreen") ep = xp.nextText().trim()
                            }
                        }
                        if (keep && title.isNotBlank()) progs.getOrPut(ch) { mutableListOf() } += Programme(title, desc, start, stop, image, ep)
                    }
                }
            }
            ev = xp.next()
        }
        val channels = names.mapNotNull { (id, nl) ->
            val p = progs[id]?.sortedBy { it.start } ?: return@mapNotNull null
            if (isRadio(id, nl.first)) null else Channel(id, if (custom) nl.first.replace("&amp;", "&").trim() else cleanName(nl.first), nl.second, p)
        }
        // Your own guide: keep its channel names and order exactly, like TiviMate.
        return if (custom) channels else order(dedupeRegions(channels))
    }

    private fun isRadio(id: String?, name: String?) =
        id?.startsWith("BBCR") == true || name?.contains("Radio", true) == true ||
            name?.contains("Asian Network", true) == true || name?.contains(" FM", false) == true

    private fun cleanName(n: String) = n.replace("&amp;", "&").removeSuffix(" HD").trim()

    /** BBC One, ITV1 etc. come in many regional versions: keep one of each (London if there). */
    private fun dedupeRegions(list: List<Channel>): List<Channel> {
        val families = listOf("BBC One", "BBC Two", "ITV1", "ITV 1", "STV", "UTV")
        val seen = HashMap<String, Channel>()
        val out = mutableListOf<Channel>()
        for (ch in list) {
            val fam = families.firstOrNull { ch.name.startsWith(it) && !ch.name.contains("+1") }
            if (fam == null) { out += ch; continue }
            val existing = seen[fam]
            if (existing == null) { seen[fam] = ch; out += ch }
            else if (ch.name.contains("London") && !existing.name.contains("London")) {
                out[out.indexOf(existing)] = ch.copy(name = fam); seen[fam] = ch
            }
        }
        return out.map { ch -> families.firstOrNull { ch.name.startsWith(it) && !ch.name.contains("+1") }?.let { ch.copy(name = it) } ?: ch }
    }

    /** Main channels first, in Freeview order. */
    private fun order(list: List<Channel>): List<Channel> {
        val top = listOf("BBC One", "BBC Two", "ITV1", "ITV 1", "Channel 4", "5", "ITV2", "BBC Three", "BBC Four", "E4", "More4", "Film4",
            "ITV3", "ITV4", "Dave", "Drama", "5USA", "5 USA", "Yesterday", "Quest", "Really", "BBC News", "Sky News", "GB News", "CBBC", "CBeebies")
        fun rank(c: Channel): Int {
            val i = top.indexOfFirst { c.name.equals(it, true) }
            return if (i >= 0) i else 1000 + (if (c.name.contains("+1")) 500 else 0)
        }
        return list.sortedWith(compareBy({ rank(it) }, { it.name }))
    }

    // ---------- Cache ----------

    private fun writeCache(f: File, list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) {
            val ps = JSONArray()
            c.programmes.forEach { p ->
                ps.put(JSONObject().put("t", p.title).put("d", p.desc.take(400)).put("s", p.start).put("e", p.stop).put("i", p.image).put("n", p.episode))
            }
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("logo", c.logo).put("p", ps))
        }
        f.writeText(arr.toString())
    }

    private fun readCache(f: File): List<Channel>? = try {
        parseJsonChannels(JSONArray(f.readText()))
    } catch (e: Exception) { null }

    // ---------- Watching ----------

    /** The broadcaster's own app for a channel, if it has one installed. */
    fun broadcasterApp(c: Context, channel: Channel): Pair<String, String>? {
        val n = channel.name.lowercase()
        val options = when {
            n.startsWith("bbc") || n.startsWith("cbbc") || n.startsWith("cbeebies") -> listOf("uk.co.bbc.iplayer" to "BBC iPlayer")
            n.startsWith("itv") || n.startsWith("stv") || n.startsWith("utv") -> listOf("air.ITVMobilePlayer" to "ITVX", "com.itv.loki" to "ITVX", "com.stv.android.player" to "STV Player")
            n.startsWith("channel 4") || n in listOf("e4", "more4", "film4", "4seven") || n.startsWith("e4") || n.startsWith("more4") || n.startsWith("film4") ->
                listOf("com.channel4.ondemand" to "Channel 4")
            n == "5" || n.startsWith("5") -> listOf("com.channel5.my5" to "My5", "com.channel5.androidtv" to "My5")
            n.startsWith("dave") || n.startsWith("drama") || n.startsWith("yesterday") -> listOf("uk.co.uktv.dave" to "U")
            else -> emptyList()
        }
        return options.firstOrNull { (pkg, _) -> launchIntent(c, pkg) != null }
    }

    fun hasTiviMate(c: Context) = launchIntent(c, TIVIMATE) != null

    /** Opens TiviMate (it resumes on the last channel you watched). */
    fun openTiviMate(activity: Activity, channel: Channel?, then: ((Boolean) -> Unit)? = null) {
        val intent = launchIntent(activity, TIVIMATE)
        if (intent == null) {
            GlassUi.toast(activity, "TiviMate isn’t installed")
            return
        }
        try {
            // Once the picker knows the channel list, resume TiviMate (fast) - it restarts it only
            // if TiviMate comes back on another screen. The first time, start it fresh on live TV.
            // Cover the screen first, so TiviMate's guide is never seen.
            if (channel != null && TiviTuner.canTune()) TiviTuner.showCover(channel.name)
            val fresh = channel != null && !TiviTuner.hasLearned(activity)
            val flags = if (fresh) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(Intent(intent).addFlags(flags))
        } catch (e: Exception) { GlassUi.toast(activity, "TiviMate couldn’t be opened"); return }
        if (channel == null) return
        TiviTuner.returnToGuide = true
        val app = activity.applicationContext
        // A system toast, so it shows on top of TiviMate.
        fun say(t: String) = android.widget.Toast.makeText(app, t, android.widget.Toast.LENGTH_LONG).show()
        if (TiviTuner.canTune()) {
            val restart = {
                try { app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) } catch (e: Exception) {}
            }
            TiviTuner.tune(channel.name, restart) { ok ->
                if (!ok) say("Couldn’t find ${channel.name} in TiviMate - choose it from the list")
                then?.invoke(ok)
            }
        } else {
            say("Choose ${channel.name} in TiviMate")
        }
    }

    fun openApp(activity: Activity, pkg: String) {
        val intent = launchIntent(activity, pkg) ?: return
        try { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    fun launchIntent(c: Context, pkg: String): Intent? =
        c.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: c.packageManager.getLaunchIntentForPackage(pkg)
}
