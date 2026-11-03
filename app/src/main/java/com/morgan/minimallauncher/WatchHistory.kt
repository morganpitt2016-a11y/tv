package com.morgan.minimallauncher

import android.content.Context

/** Which episode of a series was watched last, and how far into a film or episode, for "Continue / Start over". */
object WatchHistory {
    data class Last(val season: Int, val episode: Int)

    private fun key(title: String) = "wh_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "")

    fun record(c: Context, title: String, season: Int, episode: Int) =
        Prefs.setString(c, key(title), "$season,$episode")

    fun last(c: Context, title: String): Last? = Prefs.string(c, key(title))?.split(",")?.let {
        if (it.size == 2) Last(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null) else null
    }

    /** Films played through TiviMate (it keeps the position itself and offers Resume / Play from beginning). */
    fun markFilm(c: Context, title: String) = Prefs.setBool(c, key(title) + "_film", true)
    fun playedFilm(c: Context, title: String) = Prefs.bool(c, key(title) + "_film", false)

    private fun posKey(url: String) = "pos_" + (url.hashCode().toLong() and 0xffffffffL)

    /** Seconds into [url] where playback stopped (0 if finished or never started). */
    fun seconds(c: Context, url: String): Int = Prefs.int(c, posKey(url), 0)

    fun forget(c: Context, url: String) = Prefs.setInt(c, posKey(url), 0)

    fun clock(seconds: Int): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
