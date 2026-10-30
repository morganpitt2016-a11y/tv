package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Master accounts on this TV (e.g. "Morgan's Home", "Mum's Home"). Each account
 * has its own people (Person 1, Person 2…), and each person their own dock,
 * favourites, wallpaper, history and My List. An account can have a passcode.
 * Everything is stored on the TV - nothing online.
 */
object Accounts {

    data class Account(val id: String, val name: String, val passcodeHash: String?, val colour: Int)

    private const val PREFS = "launcher_accounts"
    /** The first account keeps the original profile storage, so nothing set up before is lost. */
    private const val LEGACY = "a0"

    /** Calm Apple-like gradients for avatars and account cards. */
    val GRADIENTS = listOf(
        intArrayOf(0xFF5E5CE6.toInt(), 0xFF8E8CF8.toInt()), intArrayOf(0xFFFF6482.toInt(), 0xFFFF9F8A.toInt()),
        intArrayOf(0xFF30B0C7.toInt(), 0xFF64D2FF.toInt()), intArrayOf(0xFF34C759.toInt(), 0xFF8EE6A0.toInt()),
        intArrayOf(0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt()), intArrayOf(0xFFAF52DE.toInt(), 0xFFDA8FFF.toInt()),
        intArrayOf(0xFF8E8E93.toInt(), 0xFFC7C7CC.toInt()), intArrayOf(0xFF0A84FF.toInt(), 0xFF64D2FF.toInt()),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Account> {
        val raw = prefs(c).getString("accounts", null)
        if (raw == null) {
            // Upgrading from before accounts existed: keep the existing people as the first account.
            val old = c.getSharedPreferences("launcher_profiles", Context.MODE_PRIVATE)
            if (old.all.isNotEmpty()) {
                val a = Account(LEGACY, "Home", null, 0)
                save(c, listOf(a)); setActive(c, a.id)
                return listOf(a)
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Account(it.getString("id"), it.getString("name"), it.optString("pass").ifBlank { null }, it.optInt("colour"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("pass", it.passcodeHash ?: "").put("colour", it.colour)) }
        prefs(c).edit().putString("accounts", arr.toString()).apply()
    }

    fun needsSetup(c: Context) = all(c).isEmpty()

    fun active(c: Context): Account? {
        val list = all(c)
        val id = prefs(c).getString("active", null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setActive(c: Context, id: String) {
        prefs(c).edit().putString("active", id).apply()
    }

    fun create(c: Context, name: String, passcode: String?, firstPerson: String): Account {
        val list = all(c)
        val id = if (list.isEmpty() && !c.getSharedPreferences("launcher_profiles", Context.MODE_PRIVATE).all.isNotEmpty()) LEGACY
            else "a" + System.currentTimeMillis().toString(36)
        val a = Account(id, name.ifBlank { "Home" }, passcode?.takeIf { it.isNotBlank() }?.let(::hash), list.size % GRADIENTS.size)
        save(c, list + a)
        setActive(c, a.id)
        // Start the account with its first person.
        c.getSharedPreferences(profilesPrefs(c), Context.MODE_PRIVATE).edit().putString("profiles", firstPerson.ifBlank { "Person 1" }).apply()
        return a
    }

    fun rename(c: Context, id: String, name: String) = save(c, all(c).map { if (it.id == id) it.copy(name = name) else it })

    fun setPasscode(c: Context, id: String, passcode: String?) =
        save(c, all(c).map { if (it.id == id) it.copy(passcodeHash = passcode?.takeIf { p -> p.isNotBlank() }?.let(::hash)) else it })

    fun remove(c: Context, id: String) {
        val left = all(c).filter { it.id != id }
        save(c, left)
        c.getSharedPreferences(prefsNameFor(id), Context.MODE_PRIVATE).edit().clear().apply()
        if (active(c)?.id == id || prefs(c).getString("active", null) == id) left.firstOrNull()?.let { setActive(c, it.id) }
    }

    fun checkPasscode(a: Account, entered: String) = a.passcodeHash == null || a.passcodeHash == hash(entered)

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(("mtv:" + s.trim()).toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------- Storage for the active account ----------

    private fun prefsNameFor(id: String) = if (id == LEGACY) "launcher_profiles" else "launcher_profiles_$id"

    /** Where the active account keeps its people. */
    fun profilesPrefs(c: Context) = prefsNameFor(active(c)?.id ?: LEGACY)

    /** A key for per-person data that can't clash between accounts ("Person 1" in two homes). */
    fun scoped(c: Context, profile: String): String {
        val id = active(c)?.id ?: LEGACY
        return if (id == LEGACY) profile else "$id:$profile"
    }

    // ---------- "Who's watching?" ----------

    /** Ask again the next time the home screen shows (set at start-up and when the TV wakes). */
    @Volatile var askWho = true
}
