package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Our own subtitles, for films and episodes whose files don't have any.
 *
 * Looked for in order, cheapest first:
 *  1. this TV's own copy (anything it has fetched before);
 *  2. the shared library (every one of your TVs adds what it fetches - so each film is only ever downloaded once);
 *  3. OpenSubtitles.com - free: 20 a day with a free account (5 without). Matched to the exact file where it can
 *     (the "movie hash" is worked out from a few KB at the start and end of the file - only the hash is sent, never
 *     the file's address or your Dodgy Box login), otherwise the most-used subtitles for that title and episode.
 *
 * The OpenSubtitles key and account are typed in on the TV (Settings › Video and Audio) and stay there.
 */
object OurSubtitles {
    data class Line(val start: Long, val end: Long, val text: String)

    private const val API = "https://api.opensubtitles.com/api/v1"
    private const val UA = "MinimalTV v1.0"
    private const val LIB = "https://ligqohydmupgahmlxqvy.supabase.co/rest/v1/subtitle_library"
    private const val LIBKEY = "sb_publishable_q0N-x_5djDRRq3ZHLfGAqQ_8_4_939Y"

    fun apiKey(c: Context) = Prefs.string(c, "os_api_key")?.takeIf { it.isNotBlank() }
    fun isSetUp(c: Context) = apiKey(c) != null

    fun key(title: String, year: Int, season: Int, episode: Int, lang: String): String {
        val t = title.lowercase().replace(Regex("\\s*[(\\[](19|20)\\d\\d[)\\]]\\s*$"), "").replace(Regex("[^a-z0-9]+"), "")
        return if (season > 0) "$t-s${season}e$episode-$lang" else "$t-${if (year > 0) year else 0}-$lang"
    }

    /** Subtitles for this film / episode, or null (with a reason in [lastNote]). Call off the main thread. */
    @Volatile var lastNote = ""
    fun find(c: Context, url: String, title: String, year: Int, season: Int, episode: Int, lang: String = "en"): List<Line>? {
        val k = key(title, year, season, episode, lang)
        val f = File(c.filesDir, "subs/$k.srt")
        if (f.exists()) { lastNote = "on this TV"; return parse(f.readText()) }
        libGet(k)?.let { save(f, it); lastNote = "from your library"; return parse(it) }
        val api = apiKey(c) ?: run { lastNote = "OpenSubtitles isn't set up"; return null }
        // The exact-file hash needs two short reads from the provider - only for direct files, never for Dodgy Box
        // (it limits connections and could cut off the film you're watching).
        val hash = if (url.contains("/movie/") || url.contains("/series/")) null else try { movieHash(url) } catch (e: Exception) { null }
        val q = sortedMapOf<String, String>()
        q["languages"] = lang
        if (hash != null) q["moviehash"] = hash
        q["query"] = title.replace(Regex("\\s*\\((19|20)\\d\\d\\)\\s*$"), "").lowercase()
        if (season > 0) { q["season_number"] = "$season"; q["episode_number"] = "$episode"; q["type"] = "episode" }
        else { q["type"] = "movie"; if (year > 0) q["year"] = "$year" }
        val search = try {
            get("$API/subtitles?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }, api)
        } catch (e: Exception) { lastNote = "OpenSubtitles search failed (${e.message})"; return null }
        val data = JSONObject(search).optJSONArray("data") ?: run { lastNote = "none found"; return null }
        var best: JSONObject? = null; var bestScore = -1.0
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            if (a.optBoolean("machine_translated") || a.optBoolean("ai_translated")) continue
            if ((a.optJSONArray("files")?.length() ?: 0) == 0) continue
            val score = (if (a.optBoolean("moviehash_match")) 1e9 else 0.0) + a.optDouble("download_count", 0.0) + (if (a.optBoolean("from_trusted")) 5000.0 else 0.0)
            if (score > bestScore) { bestScore = score; best = a }
        }
        val pick = best ?: run { lastNote = "none found for \"$title\""; return null }
        val fileId = pick.getJSONArray("files").getJSONObject(0).getLong("file_id")
        val link = try {
            val auth = token(c, api)
            JSONObject(post("$API/download", JSONObject().put("file_id", fileId).toString(), api, auth)).getString("link")
        } catch (e: Exception) { lastNote = "OpenSubtitles download refused (${e.message}) - the free daily limit may be used up"; return null }
        val srt = try { Net.get(link) } catch (e: Exception) { lastNote = "download failed"; return null }
        val lines = parse(srt)
        if (lines.size < 20) { lastNote = "the subtitles found were empty"; return null }
        save(f, srt)
        libPut(k, lang, srt)
        lastNote = "from OpenSubtitles" + if (pick.optBoolean("moviehash_match")) " (matched to this exact file)" else ""
        return lines
    }

    // ---------- OpenSubtitles ----------

    private fun token(c: Context, api: String): String? {
        val user = Prefs.string(c, "os_user")?.takeIf { it.isNotBlank() } ?: return null
        val pass = Prefs.string(c, "os_pass") ?: return null
        val at = Prefs.string(c, "os_token_at")?.toLongOrNull() ?: 0L
        Prefs.string(c, "os_token")?.takeIf { System.currentTimeMillis() - at < 20 * 3_600_000L }?.let { return it }
        return try {
            val t = JSONObject(post("$API/login", JSONObject().put("username", user).put("password", pass).toString(), api, null)).getString("token")
            Prefs.setString(c, "os_token", t); Prefs.setString(c, "os_token_at", System.currentTimeMillis().toString()); t
        } catch (e: Exception) { null }
    }

    private fun open(url: String, api: String?): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 15_000
        setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
        api?.let { setRequestProperty("Api-Key", it) }
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    }

    private fun get(url: String, api: String) = read(open(url, api))

    private fun post(url: String, body: String, api: String, auth: String?): String {
        val conn = open(url, api)
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        auth?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return read(conn)
    }

    /** OpenSubtitles' hash: the file size plus the first and last 64 KB summed as 64-bit numbers. */
    private fun movieHash(url: String): String? {
        val chunk = 65536
        fun range(from: Long, to: Long): Pair<ByteArray, Long> {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 10_000
            conn.setRequestProperty("Range", "bytes=$from-$to")
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            try {
                if (conn.responseCode != 206) throw java.io.IOException("no ranges")
                val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw java.io.IOException("no size")
                val buf = ByteArray(chunk); var n = 0
                conn.inputStream.use { s -> while (n < chunk) { val k = s.read(buf, n, chunk - n); if (k < 0) break; n += k } }
                return buf.copyOf(n) to total
            } finally { conn.disconnect() }
        }
        val (head, size) = range(0, chunk - 1L)
        if (size < chunk * 2) return null
        val (tail, _) = range(size - chunk, size - 1)
        var h = size
        for (b in listOf(head, tail)) {
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 8) h += bb.long
        }
        return "%016x".format(h)
    }

    // ---------- The shared library ----------

    private fun libGet(k: String): String? = try {
        val conn = URL("$LIB?key=eq.${URLEncoder.encode(k, "UTF-8")}&select=srt").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 12_000
        conn.setRequestProperty("apikey", LIBKEY); conn.setRequestProperty("User-Agent", Net.USER_AGENT)
        val a = org.json.JSONArray(read(conn))
        if (a.length() > 0) a.getJSONObject(0).getString("srt") else null
    } catch (e: Exception) { null }

    private fun libPut(k: String, lang: String, srt: String) {
        try {
            val conn = URL(LIB).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 12_000; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("apikey", LIBKEY); conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Prefer", "return=minimal,resolution=ignore-duplicates")
            conn.outputStream.use { it.write(JSONObject().put("key", k).put("lang", lang).put("srt", srt.take(590_000)).put("source", "opensubtitles").toString().toByteArray()) }
            read(conn)
        } catch (_: Exception) {}
    }

    private fun save(f: File, srt: String) { try { f.parentFile?.mkdirs(); f.writeText(srt) } catch (_: Exception) {} }

    // ---------- SRT ----------

    private val TIME = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")

    fun parse(srt: String): List<Line> {
        val out = ArrayList<Line>()
        for (block in srt.replace("\r", "").removePrefix("﻿").split(Regex("\n\\s*\n"))) {
            val ls = block.trim().lines()
            val ti = ls.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = TIME.find(ls[ti])!!.groupValues
            fun ms(o: Int) = m[o].toLong() * 3_600_000 + m[o + 1].toLong() * 60_000 + m[o + 2].toLong() * 1000 + m[o + 3].padEnd(3, '0').take(3).toLong()
            val text = ls.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]*>|\\{\\\\[^}]*\\}"), "").trim()
            if (text.isNotEmpty()) out += Line(ms(1), ms(5), text)
        }
        return out.sortedBy { it.start }
    }
}
