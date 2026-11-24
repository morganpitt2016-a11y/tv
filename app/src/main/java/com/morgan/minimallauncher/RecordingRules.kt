package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recording rules in plain English: "record every England rugby match", "anything with Attenborough", "the new
 * series of Traitors". Claude (Haiku, quick and cheap) turns the sentence into words to look for; without a Claude
 * key the sentence's own key words are used. Every time the guide refreshes, anything in the next day and a half
 * that matches is set to record - on whatever channel and time it turns up.
 */
object RecordingRules {
    data class Rule(val id: String, val text: String, val all: List<String>, val any: List<String>, val not: List<String>)

    private const val KEY = "rec_rules"
    private val STOP = setOf("record", "every", "all", "the", "a", "an", "of", "on", "match", "matches", "game", "games", "this", "season", "with",
        "anything", "any", "new", "series", "episode", "episodes", "show", "shows", "please", "me", "for", "and", "in", "it", "that", "whenever", "when", "is", "s")

    fun list(c: Context): List<Rule> = try {
        val a = JSONArray(Prefs.string(c, KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun l(k: String) = o.optJSONArray(k)?.let { t -> (0 until t.length()).map { t.getString(it).lowercase() } }.orEmpty()
            Rule(o.getString("id"), o.optString("text"), l("all"), l("any"), l("not"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, l: List<Rule>) = Prefs.setString(c, KEY, JSONArray().apply {
        l.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("all", JSONArray(it.all)).put("any", JSONArray(it.any)).put("not", JSONArray(it.not))) }
    }.toString())

    fun remove(c: Context, id: String) = save(c, list(c).filter { it.id != id })

    /** Blocking (call off the main thread): understand the sentence, save the rule, and apply it to the guide now. */
    fun add(c: Context, text: String): Rule {
        val app = c.applicationContext
        val rule = understand(app, text.trim())
        save(app, list(app) + rule)
        Diag.send(app, "rules", "new rule \"${rule.text}\": all=${rule.all} any=${rule.any} not=${rule.not}")
        return rule
    }

    private fun understand(c: Context, text: String): Rule {
        val id = System.currentTimeMillis().toString(36)
        try {
            val chat = ClaudeChat(c, "You turn a TV recording request into JSON for matching UK TV guide listings (title and description). " +
                "Reply with ONLY a JSON object: {\"all\": [words that must all appear], \"any\": [words of which at least one must appear, or empty], " +
                "\"not\": [words that rule a listing out]}. Lowercase single words or short phrases. Include obvious alternatives in \"any\" " +
                "(e.g. rugby -> \"rugby\", \"six nations\"). Exclude repeats/previews with \"not\" when sensible (e.g. \"highlights\" only if they asked for live matches).", quick = true)
            val r = chat.send(text)
            if (r is ClaudeChat.Reply.Text) {
                val j = JSONObject(r.text.substringAfter('{', "").let { "{" + it.substringBeforeLast('}') + "}" })
                fun l(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).lowercase().trim() }.filter { it.isNotEmpty() } }.orEmpty()
                if (l("all").isNotEmpty() || l("any").isNotEmpty()) return Rule(id, text, l("all"), l("any"), l("not"))
            }
        } catch (_: Exception) {}
        // No Claude: the sentence's own key words, all of which must appear.
        val words = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.length > 1 && it !in STOP }
        return Rule(id, text, words, emptyList(), emptyList())
    }

    fun matches(r: Rule, title: String, desc: String): Boolean {
        val t = "$title $desc".lowercase()
        if (r.all.isEmpty() && r.any.isEmpty()) return false
        return r.all.all { t.contains(it) } && (r.any.isEmpty() || r.any.any { t.contains(it) }) && r.not.none { t.contains(it) }
    }

    /** Schedule everything in the guide (next ~36 h) that a rule asks for. */
    fun apply(c: Context, guide: List<LiveGuide.Channel>) {
        val rules = list(c); if (rules.isEmpty()) return
        val now = System.currentTimeMillis()
        var n = 0
        for (ch in guide) for (p in ch.programmes) {
            if (p.stop <= now || p.start > now + 36 * 3600_000L) continue
            if (rules.none { matches(it, p.title, p.desc) }) continue
            if (TiviRecorder.isScheduled(c, ch, p)) continue
            if (p.start <= now) continue                 // already on: leave it (no half recordings)
            TiviRecorder.schedule(c, ch, p); n++
        }
        if (n > 0) Diag.send(c, "rules", "rules set $n new recordings")
    }
}
