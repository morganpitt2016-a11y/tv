package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context

/**
 * New-episode alerts: every hour the launcher looks at the series you've been watching on
 * Dodgy Box. When a newer episode appears, it goes to the front of Continue Watching as
 * "Up next" and the home screen mentions it once.
 */
object NewEpisodes {
    private fun newestKey(c: Context, series: String) = ProfileManager.scope(c, "newest_" + TextMatch.vodKey(series))

    /** Check now (call off the main thread). */
    fun check(c: Context) {
        if (!Xtream.isSetUp(c) || Xtream.isExpired(c)) return
        val followed = ResumeStore.all(c).filter { it.kind == "episode" }.mapNotNull { it.series ?: it.title }.distinct().take(12)
        val found = mutableListOf<String>()
        for (name in followed) {
            val s = try { Xtream.findSeries(c, name, 0) } catch (e: Exception) { null } ?: continue
            val eps = try { Xtream.episodes(c, s) } catch (e: Exception) { emptyList() }
            val newest = eps.maxWithOrNull(compareBy({ it.season }, { it.number })) ?: continue
            val code = newest.season * 1000 + newest.number
            val known = Prefs.int(c, newestKey(c, name), 0)
            Prefs.setInt(c, newestKey(c, name), code)
            if (known == 0 || code <= known) continue                     // first look, or nothing new
            // Only if you're up to date (or close): no point nagging about season 1 when you're on it.
            val last = WatchHistory.last(c, name)
            val lastCode = last?.let { it.season * 1000 + it.episode } ?: 0
            if (lastCode < known - 1) continue
            val next = eps.filter { it.season * 1000 + it.number > lastCode }.minWithOrNull(compareBy({ it.season }, { it.number })) ?: newest
            ResumeStore.upsert(c, ResumeStore.Item(ResumeStore.keyOf("episode", name), "episode", name, name, next.season, next.number,
                Xtream.episodeUrl(c, next), s.cover ?: next.image, 0, 0, System.currentTimeMillis()))
            found += "$name S${newest.season} E${newest.number}"
        }
        if (found.isNotEmpty()) Prefs.setString(c, ProfileManager.scope(c, "new_eps_note"), found.joinToString(", "))
    }

    /** On the home screen: say once what's new. */
    fun mention(a: Activity) {
        val key = ProfileManager.scope(a, "new_eps_note")
        val note = Prefs.string(a, key) ?: return
        Prefs.setString(a, key, null)
        GlassUi.toast(a, "New episode: $note - it’s in Continue Watching")
    }
}
