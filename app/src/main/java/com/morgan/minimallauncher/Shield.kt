package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spoiler Shield: when a match is being recorded, every TV in the family stops showing its score - no pop-ups, no
 * result cards - until someone has watched the recording (or three days pass).
 *
 * Shields are shared through the away relay (each TV adds the ones it records and picks up everyone else's).
 */
object Shield {
    data class Entry(val id: String, val title: String, val terms: List<String>, val until: Long)

    private const val KEY = "shields"
    private val SPORT = Regex("(?i)rugby|football|premier league|match of the day|cricket|f1|grand prix|boxing|darts|snooker|tennis|golf|six nations|world cup|champions league|fa cup|super league|nfl|nba")

    fun list(c: Context): List<Entry> = try {
        val a = JSONArray(Prefs.string(c, KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Entry(o.getString("id"), o.optString("title"), o.optJSONArray("terms")?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty(), o.optLong("until"))
        }.filter { it.until > System.currentTimeMillis() }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, l: List<Entry>) = Prefs.setString(c, KEY, JSONArray().apply {
        l.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("terms", JSONArray(it.terms)).put("until", it.until)) }
    }.toString())

    /** The teams in a programme title: "Six Nations: England v France" -> England, France. */
    fun teams(title: String): List<String> {
        val t = title.substringAfterLast(':').replace(Regex("(?i)\\b(live|highlights|rugby|football)\\b"), " ")
        val parts = t.split(Regex("(?i)\\s+(v|vs|versus)\\.?\\s+"))
        if (parts.size != 2) return emptyList()
        return parts.map { it.replace(Regex("[^A-Za-z .'&-]"), " ").trim() }.filter { it.length >= 3 }
    }

    /** A recording of a match is coming: shield its score here, and on the family's other TVs. */
    fun protect(c: Context, title: String, stop: Long) {
        if (!SPORT.containsMatchIn(title) && teams(title).isEmpty()) return
        val terms = teams(title).ifEmpty { return }
        val e = Entry("${title.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(60)}-${stop / 60_000}", title, terms, stop + 3 * 86_400_000L)
        add(c, e)
        AwayRemote.shareShield(c, e)
    }

    fun add(c: Context, e: Entry) { save(c, list(c).filter { it.id != e.id } + e) }

    /** Watched it: the score can be shown again (everywhere). */
    fun release(c: Context, title: String) {
        val gone = list(c).filter { it.title.equals(title, true) }
        if (gone.isEmpty()) return
        save(c, list(c) - gone.toSet())
        gone.forEach { AwayRemote.shareShield(c, it.copy(until = System.currentTimeMillis())) }
    }

    /** Should this score card stay hidden? */
    fun hides(c: Context, card: LiveScores.Card): Boolean {
        val l = list(c); if (l.isEmpty()) return false
        val abbrs = listOf(card.homeAbbr, card.awayAbbr).map { it.uppercase() }
        val path = card.path.lowercase()
        return l.any { e -> e.terms.any { t ->
            val k = t.uppercase().replace(" ", "")
            abbrs.any { a -> a.isNotEmpty() && (k.startsWith(a) || a == k.take(3)) } || path.contains(t.lowercase().replace(" ", "-"))
        } }
    }
}
