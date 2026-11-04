package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Recordings" that are really on Dodgy Box already: recording a programme that the library has simply saves it
 * here (an episode, a whole series, or a film), so it is ready to watch at once - like a series link that has
 * already recorded everything. Kept per person.
 */
object LibraryRecordings {
    data class Item(val kind: String, val name: String, val season: Int, val episode: Int, val image: String?, val channel: String, val at: Long) {
        val key get() = "$kind|${name.lowercase()}|$season|$episode"
    }

    private fun k(c: Context) = ProfileManager.scope(c, "lib_recs")

    fun all(c: Context): List<Item> = try {
        val a = JSONArray(Prefs.string(c, k(c)) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getString("k"), it.getString("n"), it.optInt("s"), it.optInt("e"), it.optString("i").ifEmpty { null }, it.optString("ch"), it.optLong("at"))
        }.sortedByDescending { it.at }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("k", it.kind).put("n", it.name).put("s", it.season).put("e", it.episode).put("i", it.image ?: "").put("ch", it.channel).put("at", it.at)) }
        Prefs.setString(c, k(c), a.toString())
    }

    fun add(c: Context, item: Item) = save(c, listOf(item) + all(c).filter { it.key != item.key })
    fun remove(c: Context, item: Item) = save(c, all(c).filter { it.key != item.key })

    /** "New: Deal or No Deal" -> "Deal or No Deal"; drops "(2024)" and similar. */
    fun cleanTitle(t: String) = t.replace(Regex("^(new|live|premiere|brand new|final)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()

    private val stop = setOf("the", "and", "for", "with", "his", "her", "their", "are", "was", "has", "have", "but", "into", "its", "this",
        "that", "they", "she", "them", "who", "from", "after", "when", "while", "about", "out", "new", "also", "one", "two", "where", "will",
        "can", "not", "all", "him", "now", "than", "then", "there", "what", "which", "more", "own", "back", "get", "gets", "series", "episode")

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 2 && it !in stop }.toSet()

    /**
     * Which library episode a broadcast is, when the guide doesn't say: compares the guide's description with
     * each episode's description (shared distinctive words), looks for the episode's own title in the guide
     * text, and uses "17/25"-style episode numbers. Returns null unless one episode is a clear match.
     */
    fun matchEpisode(p: LiveGuide.Programme, eps: List<Xtream.Episode>): Xtream.Episode? {
        seasonEpisode(p)?.let { (s, e) -> eps.firstOrNull { it.season == s && it.number == e }?.let { return it } }
        val text = (p.episode + " " + p.desc).trim()
        if (text.isBlank() || eps.isEmpty()) return null
        val pw = words(text)
        val num = Regex("^\\s*(\\d{1,3})\\s*/\\s*\\d{1,3}").find(p.desc)?.groupValues?.get(1)?.toIntOrNull()
        fun score(e: Xtream.Episode): Float {
            val ew = words(e.plot)
            val shared = pw.intersect(ew).size
            var sc = if (ew.isEmpty() || pw.isEmpty()) 0f else shared.toFloat() / minOf(pw.size, ew.size)
            if (shared >= 5) sc += 0.3f
            val t = e.title.trim()
            if (t.length > 3 && !t.startsWith("Episode", true) && text.contains(t, ignoreCase = true)) sc += 1f
            if (num != null && e.number == num) sc += 0.35f
            return sc
        }
        val ranked = eps.map { it to score(it) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        // A clear winner only: good enough on its own, and ahead of the next best.
        return best.first.takeIf { best.second >= 0.45f && best.second - second >= 0.15f }
    }

    /** Series and episode from the guide's text, e.g. "S5 E3", "Series 2, Episode 4", "S01E04". */
    fun seasonEpisode(p: LiveGuide.Programme): Pair<Int, Int>? {
        val text = p.episode + " " + p.desc
        Regex("S(\\d{1,2})\\s*,?\\s*E[p]?\\s*(\\d{1,3})", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        Regex("Series\\s+(\\d{1,2})\\s*,?\\s*Episode\\s+(\\d{1,3})", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }
}
