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

    fun clearCache(c: Context) {
        liveMem = null; vodMem = null; seriesMem = null
        dir(c).listFiles()?.forEach { it.delete() }
    }

    /** Downloads a list once (kept for 12 hours), then reads it as a stream - provider lists can be huge. */
    private fun <T> list(c: Context, action: String, parse: (JsonReader) -> T?): List<T> {
        val f = File(dir(c), "$action.json")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) {
            val tmp = File(dir(c), "$action.tmp")
            val conn = URL(api(c, action)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            try { conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } } } finally { conn.disconnect() }
            tmp.renameTo(f)
        }
        val out = ArrayList<T>()
        JsonReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out
            r.beginArray()
            while (r.hasNext()) parse(r)?.let { out += it }
            r.endArray()
        }
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

    fun live(c: Context): List<Live> = liveMem ?: list(c, "get_live_streams") { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Live(id, m["num"]?.toIntOrNull() ?: 0, m["name"].orEmpty().trim(), icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { liveMem = it }

    fun vod(c: Context): List<Vod> = vodMem ?: list(c, "get_vod_streams") { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Vod(id, m["name"].orEmpty().trim(), m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4", icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { vodMem = it }

    fun series(c: Context): List<Series> = seriesMem ?: list(c, "get_series") { r ->
        val m = obj(r)
        val id = m["series_id"]?.toIntOrNull() ?: return@list null
        Series(id, m["name"].orEmpty().trim(), icon(m["cover"]), m["category_id"].orEmpty())
    }.also { seriesMem = it }

    fun episodes(c: Context, s: Series): List<Episode> {
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
