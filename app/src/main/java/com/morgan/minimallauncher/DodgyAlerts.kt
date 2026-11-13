package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Remind Me": films and shows you want that aren't on Dodgy Box yet. Checked in the background (with the
 * new-episode check); the home screen says when one arrives, and it moves to the top of My Stuff.
 */
object DodgyAlerts {
    data class Want(val id: String, val name: String, val year: Int, val type: String, val poster: String?)

    private fun key(c: Context) = ProfileManager.scope(c, "dodgy_wants")

    fun all(c: Context): List<Want> = try {
        JSONArray(Prefs.string(c, key(c)) ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Want(it.getString("id"), it.getString("n"), it.optInt("y"), it.optString("t", "MOVIE"), it.optString("p").ifBlank { null })
        } }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, l: List<Want>) = Prefs.setString(c, key(c), JSONArray().apply {
        l.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("y", it.year).put("t", it.type).put("p", it.poster ?: "")) }
    }.toString())

    fun has(c: Context, t: WatchCatalog.Title) = all(c).any { it.id == t.id }

    /** Adds or removes; true if now wanted. */
    fun toggle(c: Context, t: WatchCatalog.Title): Boolean {
        val l = all(c)
        return if (l.any { it.id == t.id }) { save(c, l.filterNot { it.id == t.id }); false }
        else { save(c, l + Want(t.id, t.name, t.year, t.type, t.poster)); true }
    }

    /** Is it on Dodgy Box now? (call off the main thread) */
    fun available(c: Context, name: String, year: Int, type: String) = try {
        if (type == "SHOW") Xtream.findSeries(c, name, year) != null else Xtream.findVod(c, name, year) != null
    } catch (e: Exception) { false }

    /** Background check (off the main thread). */
    fun check(c: Context) {
        if (!Xtream.isSetUp(c) || Xtream.isExpired(c)) return
        val l = all(c)
        val arrived = l.filter { available(c, it.name, it.year, it.type) }
        if (arrived.isEmpty()) return
        save(c, l - arrived.toSet())
        Prefs.setString(c, ProfileManager.scope(c, "dodgy_arrived"), arrived.joinToString(", ") { it.name })
    }

    /** On the home screen: say once what's arrived. */
    fun mention(a: Activity) {
        val k = ProfileManager.scope(a, "dodgy_arrived")
        val note = Prefs.string(a, k) ?: return
        Prefs.setString(a, k, null)
        GlassUi.toast(a, "Now on Dodgy Box: $note")
    }
}
