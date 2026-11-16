package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What was being watched in the launcher's player, and how far - the "Continue Watching" rows. */
object ResumeStore {
    data class Item(
        val key: String, val kind: String,            // film | episode | live
        val title: String, val series: String?, val season: Int, val episode: Int,
        val url: String, val image: String?, val posSec: Int, val durSec: Int, val at: Long, val liveId: Int = -1,
    )

    private fun key(c: Context) = ProfileManager.scope(c, "resume_v1")

    fun keyOf(kind: String, name: String) = kind + ":" + name.lowercase().replace(Regex("[^a-z0-9]+"), "")

    @Volatile private var memoRaw: String? = null
    @Volatile private var memo: List<Item> = emptyList()

    /** Parsed once and reused until it changes (it's read for every card on screen). */
    @Synchronized fun all(c: Context): List<Item> {
        val raw = Prefs.string(c, key(c)) ?: "[]"
        if (raw === memoRaw || raw == memoRaw) return memo
        return parse(raw).also { memo = it; memoRaw = raw }
    }

    private fun parse(raw: String): List<Item> = try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getString("k"), it.getString("kind"), it.getString("t"), it.optString("s").ifEmpty { null }, it.optInt("se"), it.optInt("ep"),
                it.getString("u"), it.optString("i").ifEmpty { null }, it.optInt("p"), it.optInt("d"), it.optLong("at"), it.optInt("lid", -1))
        }.sortedByDescending { it.at }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(c: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("k", it.key).put("kind", it.kind).put("t", it.title).put("s", it.series ?: "").put("se", it.season).put("ep", it.episode)
                .put("u", it.url).put("i", it.image ?: "").put("p", it.posSec).put("d", it.durSec).put("at", it.at).put("lid", it.liveId))
        }
        Prefs.setString(c, key(c), a.toString())
    }

    fun upsert(c: Context, item: Item) {
        val rest = all(c).filter { it.key != item.key }
        val live = rest.filter { it.kind == "live" }
        val others = rest.filter { it.kind != "live" }
        // Channels are just a short "recently watched" list.
        val newLive = if (item.kind == "live") (listOf(item) + live).take(12) else live
        val newOthers = if (item.kind != "live") (listOf(item) + others).take(28) else others
        save(c, newOthers + newLive)
    }

    fun remove(c: Context, key: String) = save(c, all(c).filter { it.key != key })

    /** Forget the channels you've watched (Recently Watched), for every profile on this TV. Returns how many went. */
    @Synchronized fun clearLiveEverywhere(c: Context): Int {
        val prefs = c.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        var removed = 0
        for ((k, v) in prefs.all) {
            if (!k.startsWith("resume_v1") || v !is String) continue
            val a = try { JSONArray(v) } catch (e: Exception) { continue }
            val keep = JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("kind") == "live") removed++ else keep.put(o) }
            prefs.edit().putString(k, keep.toString()).apply()
        }
        return removed
    }

    fun forTab(c: Context, tab: String): List<Item> = all(c).filter {
        when (tab) { "films" -> it.kind == "film"; "shows" -> it.kind == "episode"; "live" -> it.kind == "live"; else -> it.kind != "live" }
    }.sortedByDescending { likely(it) }.take(15)

    /**
     * How likely you are to want this next (like Apple's Up Next): something half-watched in the last couple of
     * days beats a new episode, which beats something you left weeks ago. Shows you watch most get a lift.
     */
    private fun likely(i: Item): Double {
        val hours = (System.currentTimeMillis() - i.at) / 3_600_000.0
        var score = 100.0 / (1.0 + hours / 24.0)                    // fades over days
        if (i.durSec > 0 && i.posSec > 60) {
            val done = i.posSec.toDouble() / i.durSec
            score *= if (done in 0.05..0.92) 1.6 else 0.6           // part-way through: very likely; nearly done: less
        } else if (i.kind == "episode") score *= 1.3                 // next episode ready to go
        val evening = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 18
        if (evening && i.kind == "film") score *= 1.15              // films more likely at night
        return score
    }
}
