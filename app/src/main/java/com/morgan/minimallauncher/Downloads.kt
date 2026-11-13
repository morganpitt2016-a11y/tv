package com.morgan.minimallauncher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Download for Later": saves a Dodgy Box film or episode onto the TV, so it plays smoothly even when
 * the internet or Dodgy Box is struggling. Kept in the app's own storage (no permissions needed).
 */
object Downloads {
    data class Item(val dmId: Long, val title: String, val series: String?, val season: Int, val episode: Int, val file: String, val image: String?, val at: Long) {
        val label get() = if (series != null) "$series · S$season E$episode" else title
    }

    private const val KEY = "downloads"
    private const val MIN_FREE = 2L * 1024 * 1024 * 1024   // keep 2 GB free for the TV itself

    private fun dir(c: Context) = (c.getExternalFilesDir("downloads") ?: File(c.filesDir, "downloads")).apply { mkdirs() }

    fun all(c: Context): List<Item> = try {
        JSONArray(Prefs.string(c, KEY) ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getLong("id"), it.getString("title"), it.optString("series").ifBlank { null }, it.optInt("s"), it.optInt("e"),
                it.getString("file"), it.optString("image").ifBlank { null }, it.optLong("at"))
        } }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, list: List<Item>) = Prefs.setString(c, KEY, JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.dmId).put("title", it.title).put("series", it.series ?: "").put("s", it.season).put("e", it.episode)
            .put("file", it.file).put("image", it.image ?: "").put("at", it.at)) }
    }.toString())

    fun freeBytes(c: Context) = try { StatFs(dir(c).path).availableBytes } catch (e: Exception) { 0L }

    /** Starts a download. Returns a message to show. */
    fun start(c: Context, url: String, title: String, series: String? = null, season: Int = 0, episode: Int = 0, image: String? = null): String {
        if (all(c).any { it.title == title && it.series == series && it.season == season && it.episode == episode }) return "Already downloaded (or downloading)"
        if (freeBytes(c) < MIN_FREE) return "Not enough space on the TV - delete a download or an app first"
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").take(4)
        val name = (series?.let { "$it S${season}E$episode" } ?: title).replace(Regex("[^A-Za-z0-9 ._-]"), "").take(80) + "." + ext
        val file = File(dir(c), name)
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("User-Agent", Xtream.PLAYER_AGENT)
            .setTitle(series?.let { "$it S$season E$episode" } ?: title)
            .setDestinationInExternalFilesDir(c, "downloads", name)
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        save(c, all(c) + Item(id, title, series, season, episode, file.absolutePath, image, System.currentTimeMillis()))
        return "Downloading ${series?.let { "S$season E$episode" } ?: title} - it’ll be in My List → Downloads"
    }

    /** "Downloaded" / "Downloading 42%" / "Failed". */
    fun status(c: Context, it: Item): Pair<String, Boolean> {
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(it.dmId))?.use { cur ->
            if (cur.moveToFirst()) {
                val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> "Downloaded" to true
                    DownloadManager.STATUS_FAILED -> "Download failed - delete it and try again" to false
                    DownloadManager.STATUS_PAUSED -> "Waiting for the connection…" to false
                    else -> (if (total > 0) "Downloading ${got * 100 / total}%" else "Downloading…") to false
                }
            }
        }
        return if (File(it.file).length() > 0) "Downloaded" to true else "Download missing" to false
    }

    fun delete(c: Context, it: Item) {
        try { (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(it.dmId) } catch (e: Exception) {}
        File(it.file).delete()
        save(c, all(c).filterNot { x -> x.dmId == it.dmId })
    }
}
