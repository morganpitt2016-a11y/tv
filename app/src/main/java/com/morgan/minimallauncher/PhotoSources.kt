package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Pictures for the Aerial and Ambient screens, fetched once and cached on
 * the TV (refreshed weekly):
 *  - Aerial categories: Wikimedia Commons "Featured pictures" (freely
 *    licensed, credited on screen).
 *  - Artwork: public-domain paintings from the Art Institute of Chicago.
 * Until a download finishes (or with no internet) the bundled photos are used.
 */
object PhotoSources {

    data class Pic(val file: File, val place: String, val credit: String)

    class Category(val id: String, val title: String, val query: String)

    val aerialCategories = listOf(
        Category("mountains", "Mountains", "mountain landscape"),
        Category("coast", "Coastlines", "coast cliffs sea"),
        Category("forests", "Forests", "forest trees landscape"),
        Category("lakes", "Lakes", "lake reflection"),
        Category("ice", "Ice & Snow", "glacier iceberg snow landscape"),
        Category("deserts", "Deserts", "desert dunes"),
        Category("cities", "Cities at Night", "city skyline night"),
        Category("skies", "Night Skies", "milky way night sky"),
    )

    private const val MAX_PICS = 14
    private const val MAX_AGE = 7L * 24 * 3600 * 1000
    private val main = Handler(Looper.getMainLooper())
    private val busy = mutableSetOf<String>()

    /** Cached pictures right away (maybe empty); [updated] fires later if new ones were downloaded. */
    fun aerial(c: Context, categoryId: String, updated: (List<Pic>) -> Unit): List<Pic> {
        val cat = aerialCategories.firstOrNull { it.id == categoryId } ?: aerialCategories.first()
        return cached(c, "aerial_${cat.id}") { dir -> fetchCommons(cat.query, dir) }.also { refreshIfOld(c, "aerial_${cat.id}", it, updated) { dir -> fetchCommons(cat.query, dir) } }
    }

    fun artwork(c: Context, updated: (List<Pic>) -> Unit): List<Pic> =
        cached(c, "art") { dir -> fetchArt(dir) }.also { refreshIfOld(c, "art", it, updated) { dir -> fetchArt(dir) } }

    // ---------- Cache ----------

    private fun dir(c: Context, key: String) = File(c.cacheDir, "pics/$key").apply { mkdirs() }

    private fun cached(c: Context, key: String, @Suppress("UNUSED_PARAMETER") fetch: (File) -> List<Pic>): List<Pic> = try {
        val d = dir(c, key)
        val index = JSONArray(File(d, "index.json").readText())
        (0 until index.length()).map { index.getJSONObject(it) }
            .map { Pic(File(d, it.getString("f")), it.getString("p"), it.getString("c")) }
            .filter { it.file.exists() && it.file.length() > 0 }
    } catch (e: Exception) { emptyList() }

    private fun refreshIfOld(c: Context, key: String, have: List<Pic>, updated: (List<Pic>) -> Unit, fetch: (File) -> List<Pic>) {
        val d = dir(c, key)
        val index = File(d, "index.json")
        val fresh = index.exists() && System.currentTimeMillis() - index.lastModified() < MAX_AGE && have.size >= 4
        if (fresh) return
        synchronized(busy) { if (!busy.add(key)) return }
        Thread {
            try {
                val pics = fetch(d)
                if (pics.size >= 3) {
                    val json = JSONArray()
                    pics.forEach { json.put(JSONObject().put("f", it.file.name).put("p", it.place).put("c", it.credit)) }
                    index.writeText(json.toString())
                    // Remove pictures no longer listed.
                    val keep = pics.map { it.file.name }.toSet() + "index.json"
                    d.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                    main.post { updated(pics) }
                }
            } catch (e: Exception) {
            } finally {
                synchronized(busy) { busy.remove(key) }
            }
        }.start()
    }

    // ---------- Wikimedia Commons ----------

    private fun fetchCommons(query: String, d: File): List<Pic> {
        val params = mapOf(
            "action" to "query", "format" to "json", "generator" to "search",
            "gsrnamespace" to "6", "gsrlimit" to "50",
            "gsrsearch" to "incategory:Featured_pictures_on_Wikimedia_Commons $query filetype:bitmap",
            "prop" to "imageinfo", "iiprop" to "url|size|extmetadata", "iiurlwidth" to "1920",
        )
        val url = "https://commons.wikimedia.org/w/api.php?" + params.entries.joinToString("&") {
            it.key + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val pages = JSONObject(Net.get(url)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val list = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }.toList()
        val out = mutableListOf<Pic>()
        for (p in list) {
            if (out.size >= MAX_PICS) break
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = ii.optInt("width"); val h = ii.optInt("height")
            if (w < 1920 || h == 0 || w.toFloat() / h !in 1.35f..2.3f) continue
            val thumb = ii.optString("thumburl")
            if (thumb.isBlank()) continue
            val meta = ii.optJSONObject("extmetadata")
            val name = meta?.optJSONObject("ObjectName")?.optString("value")?.let(::plain)?.takeIf { it.isNotBlank() }
                ?: p.optString("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            val artist = meta?.optJSONObject("Artist")?.optString("value")?.let(::plain).orEmpty()
            val licence = meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            val file = File(d, "c${p.optLong("pageid")}.jpg")
            if (!file.exists() && !Net.download(thumb, file)) continue
            out += Pic(file, name.take(80), listOf(artist.take(60), licence, "Wikimedia Commons").filter { it.isNotBlank() }.joinToString(" · "))
        }
        return out
    }

    // ---------- Art Institute of Chicago (public domain) ----------

    private fun fetchArt(d: File): List<Pic> {
        val url = "https://api.artic.edu/api/v1/artworks/search?q=" + URLEncoder.encode("landscape", "UTF-8") +
            "&query%5Bterm%5D%5Bis_public_domain%5D=true&limit=60&fields=id,title,artist_title,date_display,image_id,thumbnail"
        val data = JSONObject(Net.get(url, mapOf("AIC-User-Agent" to Net.USER_AGENT))).optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Pic>()
        for (i in 0 until data.length()) {
            if (out.size >= MAX_PICS) break
            val a = data.getJSONObject(i)
            val imageId = a.optString("image_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val t = a.optJSONObject("thumbnail")
            val w = t?.optInt("width") ?: 0; val h = t?.optInt("height") ?: 0
            if (h == 0 || w.toFloat() / h < 1.2f) continue // landscape-shaped works fill a TV best
            val file = File(d, "a$imageId.jpg")
            if (!file.exists() && !Net.download("https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg", file)) continue
            val title = a.optString("title")
            val artist = a.optString("artist_title").takeIf { it != "null" }.orEmpty()
            val date = a.optString("date_display").takeIf { it != "null" }.orEmpty()
            out += Pic(file, title, listOf(artist, date, "Art Institute of Chicago").filter { it.isNotBlank() }.joinToString(" · "))
        }
        return out
    }

    private fun plain(html: String) = html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&#039;", "'")
        .replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()
}
