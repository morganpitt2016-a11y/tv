package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONObject

/** Thumbs ratings per profile: -1 not for me, 1 like, 2 love. Loved titles drive "Because You Loved…"; disliked ones are hidden. */
object Ratings {
    private fun key(c: Context) = ProfileManager.scope(c, "ratings")
    private fun norm(t: String) = TextMatch.vodKey(t)
    @Volatile private var memoRaw: String? = null
    @Volatile private var memo = JSONObject()
    private fun all(c: Context): JSONObject {
        val raw = Prefs.string(c, key(c)) ?: "{}"
        if (raw === memoRaw || raw == memoRaw) return memo
        return (try { JSONObject(raw) } catch (e: Exception) { JSONObject() }).also { memo = it; memoRaw = raw }
    }

    fun get(c: Context, title: String): Int? = all(c).optJSONObject(norm(title))?.optInt("r")

    fun set(c: Context, title: String, r: Int) {
        val o = all(c)
        val copy = JSONObject(o.toString())
        copy.put(norm(title), JSONObject().put("r", r).put("name", title).put("at", System.currentTimeMillis()))
        Prefs.setString(c, key(c), copy.toString())
    }

    /** Loved titles, newest first. */
    fun loved(c: Context): List<String> {
        val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { it.optInt("r") == 2 }
            .sortedByDescending { it.optLong("at") }.map { it.optString("name") }.toList()
    }

    fun disliked(c: Context, title: String) = get(c, title) == -1
}
