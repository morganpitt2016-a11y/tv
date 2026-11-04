package com.morgan.minimallauncher

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The provider's own programme listings for a channel (Xtream "short EPG": what's on now and the next few
 * programmes). Fetched a few channels at a time as they scroll into view, and kept for 20 minutes.
 */
object ProviderEpg {
    private val mem = ConcurrentHashMap<Int, Pair<Long, LiveGuide.Channel?>>()
    private val pool = Executors.newFixedThreadPool(5)
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()

    fun cached(id: Int): LiveGuide.Channel? = mem[id]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000L }?.second

    /** Calls [done] (on a background thread) with the listings, or null when the provider has none. */
    fun fetch(c: Context, ch: Xtream.Live, done: (LiveGuide.Channel?) -> Unit) {
        mem[ch.id]?.let { (at, g) -> if (System.currentTimeMillis() - at < 20 * 60_000L) { done(g); return } }
        if (!inFlight.add(ch.id)) return
        val app = c.applicationContext
        pool.execute {
            val g = try { load(app, ch) } catch (e: Exception) { null }
            mem[ch.id] = System.currentTimeMillis() to g
            inFlight.remove(ch.id)
            done(g)
        }
    }

    private fun text(s: String): String {
        if (s.isBlank()) return ""
        val decoded = try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { null }
        return if (decoded != null && decoded.none { it == '�' || (it.code < 32 && it != '\n') }) decoded.trim() else s.trim()
    }

    private fun load(c: Context, ch: Xtream.Live): LiveGuide.Channel? {
        val o = JSONObject(Xtream.shortEpg(c, ch.id, 16))
        val arr = o.optJSONArray("epg_listings") ?: return null
        val out = ArrayList<LiveGuide.Programme>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val start = e.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val stop = e.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: continue
            val title = text(e.optString("title"))
            if (title.isBlank()) continue
            out += LiveGuide.Programme(title, text(e.optString("description")), start, stop, null, "")
        }
        if (out.isEmpty()) return null
        // One programme at a time: where two overlap, keep the first (the provider sometimes lists duplicates).
        val clean = ArrayList<LiveGuide.Programme>()
        for (p in out.sortedBy { it.start }) if (clean.isEmpty() || p.start >= clean.last().stop) clean += p
        return LiveGuide.Channel(ch.id.toString(), ch.name, ch.icon, clean)
    }
}
