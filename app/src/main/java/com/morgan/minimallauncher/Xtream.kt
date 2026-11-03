package com.morgan.minimallauncher

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Your TV provider's own line-up, through its Xtream Codes login (the same login
 * TiviMate uses). The login is typed on the TV and kept only on the TV.
 * Lists are cached on the TV for half a day; nothing is sent anywhere but the provider.
 */
object Xtream {

    data class Live(val id: Int, val num: Int, val name: String, val icon: String?, val category: String)
    data class Vod(val id: Int, val name: String, val ext: String, val icon: String?, val category: String)
    data class Series(val id: Int, val name: String, val cover: String?, val category: String)
    data class Episode(val id: String, val season: Int, val number: Int, val title: String, val ext: String)

    // ---------- Login ----------

    fun server(c: Context) = Prefs.string(c, "xt_server")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }
    private fun user(c: Context) = Prefs.string(c, "xt_user")?.trim().orEmpty()
    private fun pass(c: Context) = Prefs.string(c, "xt_pass")?.trim().orEmpty()

    fun isSetUp(c: Context) = server(c) != null && user(c).isNotEmpty() && pass(c).isNotEmpty()

    fun save(c: Context, server: String, user: String, pass: String) {
        Prefs.setString(c, "xt_server", server.trim())
        Prefs.setString(c, "xt_user", user.trim())
        Prefs.setString(c, "xt_pass", pass.trim())
        clearCache(c)
    }

    /**
     * Many providers use the same server, username and password in the guide (XMLTV) link as for streaming.
     * With the owner's say-so, try signing in with the ones in the saved guide link, entirely on this TV.
     * Returns a status line (never the details themselves), or throws with the reason.
     */
    fun signInFromGuideLink(c: Context): String {
        val link = LiveGuide.customUrl(c) ?: throw Exception("No guide link is saved on this TV")
        val uri = android.net.Uri.parse(link)
        val user = uri.getQueryParameter("username") ?: throw Exception("The guide link has no username in it")
        val pass = uri.getQueryParameter("password") ?: throw Exception("The guide link has no password in it")
        val host = uri.host ?: throw Exception("The guide link has no server in it")
        val server = (uri.scheme ?: "http") + "://" + host + (if (uri.port > 0) ":${uri.port}" else "")
        val before = Triple(Prefs.string(c, "xt_server"), Prefs.string(c, "xt_user"), Prefs.string(c, "xt_pass"))
        save(c, server, user, pass)
        return try { check(c) } catch (e: Exception) {
            Prefs.setString(c, "xt_server", before.first); Prefs.setString(c, "xt_user", before.second); Prefs.setString(c, "xt_pass", before.third)
            clearCache(c)
            throw e
        }
    }

    /** A quiet reminder when the subscription is about to run out (at most once a day). */
    fun expiryNotice(activity: android.app.Activity) {
        if (!isSetUp(activity)) return
        val app = activity.applicationContext
        val today = (System.currentTimeMillis() / 86_400_000L).toString()
        if (Prefs.string(app, "xt_notice_day") == today) return
        Thread {
            try { check(app) } catch (e: Exception) { return@Thread }
            val exp = Prefs.string(app, "xt_exp")?.toLongOrNull() ?: return@Thread
            val days = ((exp - System.currentTimeMillis()) / 86_400_000L).toInt()
            if (days <= 7) {
                Prefs.setString(app, "xt_notice_day", today)
                activity.runOnUiThread {
                    if (!activity.isDestroyed) GlassUi.toast(activity, when {
                        days < 0 -> "Your Dodgy Box subscription has ended - ask your seller to renew it"
                        days == 0 -> "Your Dodgy Box subscription ends today"
                        days == 1 -> "Your Dodgy Box subscription ends tomorrow"
                        else -> "Your Dodgy Box subscription ends in $days days"
                    })
                }
            }
        }.start()
    }

    fun signOut(c: Context) {
        listOf("xt_server", "xt_user", "xt_pass").forEach { Prefs.setString(c, it, null) }
        clearCache(c)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(c: Context, action: String? = null, extra: String = ""): String =
        "${server(c)}/player_api.php?username=${enc(user(c))}&password=${enc(pass(c))}" +
            (action?.let { "&action=$it" } ?: "") + extra

    /** Checks the login. Returns a short status ("Active until 3 Mar 2027") or throws with the reason. */
    fun check(c: Context): String {
        val o = org.json.JSONObject(Net.get(api(c), timeoutMs = 15_000))
        val info = o.optJSONObject("user_info") ?: throw Exception("The server didn’t accept the login")
        if (info.optInt("auth", 1) == 0) throw Exception("Wrong username or password")
        val status = info.optString("status", "Active")
        val exp = info.optString("exp_date").toLongOrNull()
        if (exp != null) Prefs.setString(c, "xt_exp", (exp * 1000).toString())
        val until = exp?.let { " until " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(it * 1000)) }.orEmpty()
        return status + until
    }

    // ---------- Stream links ----------

    fun liveUrl(c: Context, id: Int, hls: Boolean = false) = "${server(c)}/live/${enc(user(c))}/${enc(pass(c))}/$id.${if (hls) "m3u8" else "ts"}"
    fun vodUrl(c: Context, v: Vod) = "${server(c)}/movie/${enc(user(c))}/${enc(pass(c))}/${v.id}.${v.ext}"
    fun episodeUrl(c: Context, e: Episode) = "${server(c)}/series/${enc(user(c))}/${enc(pass(c))}/${e.id}.${e.ext}"

    // ---------- Lists (blocking; call off the main thread) ----------

    @Volatile private var liveMem: List<Live>? = null
    @Volatile private var vodMem: List<Vod>? = null
    @Volatile private var seriesMem: List<Series>? = null

    private fun dir(c: Context) = File(c.cacheDir, "xtream").apply { mkdirs() }

    /** Load the lists in the background so the first film or channel doesn't wait for a big download. */
    fun warm(c: Context) {
        if (!isSetUp(c)) return
        val app = c.applicationContext
        Thread {
            try { live(app) } catch (e: Exception) {}
            try { vod(app) } catch (e: Exception) {}
            try { series(app) } catch (e: Exception) {}
        }.start()
    }

    fun clearCache(c: Context) {
        liveMem = null; vodMem = null; seriesMem = null; catMem = null
        dir(c).listFiles()?.forEach { it.delete() }
    }

    private const val MAGIC = 0x58544931

    /**
     * Provider lists can be huge (a hundred thousand films), so the first read downloads and parses the JSON
     * once and keeps a compact binary copy; every later start reads that in a fraction of the time, and an
     * old copy is refreshed quietly in the background.
     */
    private fun <T> list(
        c: Context, action: String, enc: (java.io.DataOutputStream, T) -> Unit, dec: (java.io.DataInputStream) -> T,
        extra: String = "", cacheName: String = action, parse: (JsonReader) -> T?,
    ): List<T> {
        val bin = File(dir(c), "$cacheName.bin")
        fun write(items: List<T>) {
            if (items.isEmpty()) return
            val tmp = File(bin.path + ".part")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { o ->
                o.writeInt(MAGIC); o.writeInt(items.size); items.forEach { enc(o, it) }
            }
            tmp.renameTo(bin)
        }
        if (bin.exists()) {
            val items = try {
                java.io.DataInputStream(java.io.BufferedInputStream(bin.inputStream(), 64 * 1024)).use { i ->
                    if (i.readInt() != MAGIC) throw java.io.IOException("format")
                    List(i.readInt()) { dec(i) }
                }
            } catch (e: Exception) { bin.delete(); null }
            if (!items.isNullOrEmpty()) {
                if (System.currentTimeMillis() - bin.lastModified() > 12 * 3600_000L) {
                    bin.setLastModified(System.currentTimeMillis())      // one refresh at a time
                    Thread { try { write(listJson(c, action, extra, cacheName, parse, force = true)) } catch (e: Exception) {} }.start()
                }
                return items
            }
        }
        return listJson(c, action, extra, cacheName, parse, force = false).also { write(it) }
    }

    private fun <T> listJson(c: Context, action: String, extra: String, cacheName: String, parse: (JsonReader) -> T?, force: Boolean): List<T> {
        val f = File(dir(c), "$cacheName.json")
        if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) {
            val tmp = File(dir(c), "$cacheName.tmp")
            val conn = URL(api(c, action, extra)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            try { conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } } } finally { conn.disconnect() }
            tmp.renameTo(f)
        }
        val out = ArrayList<T>()
        JsonReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) { f.delete(); return out }
            r.beginArray()
            while (r.hasNext()) parse(r)?.let { out += it }
            r.endArray()
        }
        if (out.isEmpty()) f.delete()   // an empty answer is probably an error - ask again next time
        f.delete()                      // the binary copy replaces it; keeps the cache small
        return out
    }

    /** Reads one JSON object as a flat map of its simple values. */
    private fun obj(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING -> m[k] = r.nextString()
                JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private fun icon(s: String?) = s?.takeIf { it.startsWith("http") }

    @Volatile private var catMem: Map<String, String>? = null

    /** Channel group names ("UK ~ Entertainment") by category id. */
    fun liveCategories(c: Context): Map<String, String> = catMem ?: try {
        val a = org.json.JSONArray(Net.get(api(c, "get_live_categories"), timeoutMs = 15_000))
        (0 until a.length()).associate { val o = a.getJSONObject(it); o.optString("category_id") to o.optString("category_name") }
    } catch (e: Exception) { emptyMap() }.also { if (it.isNotEmpty()) catMem = it }

    private fun java.io.DataOutputStream.str(x: String?) = writeUTF((x ?: "").take(600))

    fun live(c: Context): List<Live> = liveMem ?: list(c, "get_live_streams",
        { o, v: Live -> o.writeInt(v.id); o.writeInt(v.num); o.str(v.name); o.str(v.icon); o.str(v.category) },
        { i -> Live(i.readInt(), i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Live(id, m["num"]?.toIntOrNull() ?: 0, m["name"].orEmpty().trim(), icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { liveMem = it }

    /** One group's channels (small and quick), instead of the whole line-up. */
    fun liveInCategory(c: Context, categoryId: String): List<Live> =
        liveMem?.filter { it.category == categoryId }?.takeIf { it.isNotEmpty() } ?: list(c, "get_live_streams",
            { o, v: Live -> o.writeInt(v.id); o.writeInt(v.num); o.str(v.name); o.str(v.icon); o.str(v.category) },
            { i -> Live(i.readInt(), i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) },
            extra = "&category_id=$categoryId", cacheName = "live_cat_$categoryId") { r ->
            val m = obj(r)
            val id = m["stream_id"]?.toIntOrNull() ?: return@list null
            Live(id, m["num"]?.toIntOrNull() ?: 0, m["name"].orEmpty().trim(), icon(m["stream_icon"]), m["category_id"].orEmpty().ifEmpty { categoryId })
        }

    private val qualityNoise = Regex("(?i)\\b(uhd|fhd|hd|sd|4k|hevc|h\\.?265|1080p?|720p?|50fps|60fps)\\b|\\(f\\)|\\[[^\\]]*\\]")
    private fun qualityRank(n: String): Int = when {
        Regex("(?i)\\b(uhd|4k)\\b").containsMatchIn(n) -> 4
        Regex("(?i)\\bfhd\\b|1080").containsMatchIn(n) -> 3
        Regex("(?i)\\bhd\\b|720").containsMatchIn(n) -> 2
        Regex("(?i)\\bsd\\b").containsMatchIn(n) -> 0
        else -> 1
    }

    /** One entry per channel: the highest-definition version (UHD, then FHD, HD, SD), in channel-number order. */
    fun best(list: List<Live>): List<Live> =
        list.groupBy { TextMatch.normalise(qualityNoise.replace(it.name, " ")).ifEmpty { it.name.lowercase() } }.values
            .map { g -> g.sortedWith(compareByDescending<Live> { qualityRank(it.name) }.thenBy { it.num }).first() }
            .sortedBy { it.num }

    /** A group's channels, one per station. */
    fun liveGroup(c: Context, categoryId: String): List<Live> = best(liveInCategory(c, categoryId))

    // Favourites (channel ids), kept on the TV.
    fun favourites(c: Context): Set<Int> = (Prefs.string(c, "fav_live") ?: "").split(",").mapNotNull { it.toIntOrNull() }.toSet()
    fun toggleFavourite(c: Context, id: Int): Boolean {
        val f = favourites(c).toMutableSet()
        val added = if (id in f) { f.remove(id); false } else { f.add(id); true }
        Prefs.setString(c, "fav_live", f.joinToString(","))
        return added
    }
    fun liveById(c: Context, ids: Set<Int>): List<Live> = try { live(c).filter { it.id in ids } } catch (e: Exception) { emptyList() }

    fun vod(c: Context): List<Vod> = vodMem ?: list(c, "get_vod_streams",
        { o, v: Vod -> o.writeInt(v.id); o.str(v.name); o.str(v.ext); o.str(v.icon); o.str(v.category) },
        { i -> Vod(i.readInt(), i.readUTF(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Vod(id, m["name"].orEmpty().trim(), m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4", icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { vodMem = it }

    fun series(c: Context): List<Series> = seriesMem ?: list(c, "get_series",
        { o, v: Series -> o.writeInt(v.id); o.str(v.name); o.str(v.cover); o.str(v.category) },
        { i -> Series(i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["series_id"]?.toIntOrNull() ?: return@list null
        Series(id, m["name"].orEmpty().trim(), icon(m["cover"]), m["category_id"].orEmpty())
    }.also { seriesMem = it }

    private val episodeMem = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<Episode>>>()

    /** Look the series up and fetch its episodes in the background, ready for when Watch is pressed. */
    fun prefetchSeries(c: Context, title: String, year: Int) {
        Thread { try { findSeries(c, title, year)?.let { episodes(c, it) } } catch (e: Exception) {} }.start()
    }

    fun episodes(c: Context, s: Series): List<Episode> {
        episodeMem[s.id]?.let { (at, list) -> if (System.currentTimeMillis() - at < 30 * 60_000L) return list }
        return fetchEpisodes(c, s).also { if (it.isNotEmpty()) episodeMem[s.id] = System.currentTimeMillis() to it }
    }

    private fun fetchEpisodes(c: Context, s: Series): List<Episode> {
        val o = org.json.JSONObject(Net.get(api(c, "get_series_info", "&series_id=${s.id}"), timeoutMs = 20_000))
        val eps = o.optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (season in eps.keys()) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out += Episode(e.optString("id"), e.optInt("season", season.toIntOrNull() ?: 1), e.optInt("episode_num", i + 1),
                    e.optString("title"), e.optString("container_extension").ifBlank { "mp4" })
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    // ---------- Matching ----------

    /** A provider channel for a guide channel name ("BBC One" → "UK: BBC ONE FHD"). */
    fun findLive(c: Context, name: String): Live? {
        val wanted = TiviTuner.normalise(name)
        val all = live(c)
        val keyed = all.map { it to TiviTuner.normalise(it.name.replace(Regex("^[A-Za-z]{2,3}\\s*[:|\\-]\\s*"), "")) }
        // Exact name first; prefer the best quality (FHD/HD over SD) when there are several.
        val exact = keyed.filter { it.second == wanted }.map { it.first }
        if (exact.isNotEmpty()) return exact.maxByOrNull { quality(it.name) }
        return keyed.filter { (_, k) -> Regex("(^| )" + Regex.escape(wanted) + "( |$)").containsMatchIn(k) && !k.contains("+1") == !wanted.contains("+1") && k.length <= wanted.length + 10 }
            .map { it.first }.maxByOrNull { quality(it.name) }
    }

    private fun quality(n: String): Int {
        val s = n.lowercase()
        return when { "uhd" in s || "4k" in s -> 2; "fhd" in s -> 4; "hd" in s -> 3; "sd" in s -> 0; else -> 1 }
    }

    private fun yearOf(n: String) = Regex("\\b(19|20)\\d{2}\\b").find(n)?.value?.toIntOrNull()

    fun findVod(c: Context, title: String, year: Int): Vod? {
        val wanted = TiviTuner.vodKey(title)
        val hits = vod(c).filter { TiviTuner.vodKey(it.name) == wanted }
        return hits.firstOrNull { year > 0 && yearOf(it.name) == year } ?: hits.firstOrNull { yearOf(it.name) == null || year <= 0 } ?: hits.firstOrNull()
    }

    fun findSeries(c: Context, title: String, year: Int): Series? {
        val wanted = TiviTuner.vodKey(title)
        val hits = series(c).filter { TiviTuner.vodKey(it.name) == wanted }
        return hits.firstOrNull { year > 0 && yearOf(it.name) == year } ?: hits.firstOrNull()
    }
}
