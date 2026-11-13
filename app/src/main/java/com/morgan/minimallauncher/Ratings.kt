package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONObject

/** Thumbs ratings per profile: -1 not for me, 1 like, 2 love. Loved titles drive "Because You Loved…"; disliked ones are hidden. */
object Ratings {
    private fun key(c: Context) = ProfileManager.scope(c, "ratings")
    private fun norm(t: String) = TextMatch.vodKey(t)
    private fun all(c: Context) = try { JSONObject(Prefs.string(c, key(c)) ?: "{}") } catch (e: Exception) { JSONObject() }

    fun get(c: Context, title: String): Int? = all(c).optJSONObject(norm(title))?.optInt("r")

    fun set(c: Context, title: String, r: Int) {
        val o = all(c)
        o.put(norm(title), JSONObject().put("r", r).put("name", title).put("at", System.currentTimeMillis()))
        Prefs.setString(c, key(c), o.toString())
    }

    /** Loved titles, newest first. */
    fun loved(c: Context): List<String> {
        val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { it.optInt("r") == 2 }
            .sortedByDescending { it.optLong("at") }.map { it.optString("name") }.toList()
    }

    fun disliked(c: Context, title: String) = get(c, title) == -1
}
