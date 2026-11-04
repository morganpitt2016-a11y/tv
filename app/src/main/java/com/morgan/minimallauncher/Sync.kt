package com.morgan.minimallauncher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Profiles that follow you between TVs: each person's My List, Continue Watching, favourites,
 * places in films and so on are kept in step on every TV that shares the same sync code.
 * What goes online is locked with that code, so nobody without it can read it.
 */
object Sync {
    private const val BASE = "https://ligqohydmupgahmlxqvy.supabase.co/rest/v1/rpc"
    private const val KEY = "sb_publishable_q0N-x_5djDRRq3ZHLfGAqQ_8_4_939Y"
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun code(c: Context): String? = Prefs.string(c, "sync_code")
    fun isOn(c: Context) = code(c) != null

    /** A new code for the first TV. */
    fun start(c: Context): String {
        val code = (1..12).map { LETTERS[SecureRandom().nextInt(LETTERS.length)] }.joinToString("")
        Prefs.setString(c, "sync_code", code)
        return pretty(code)
    }

    /** Another TV joins with the code shown on the first. */
    fun join(c: Context, typed: String): Boolean {
        val code = typed.uppercase().filter { it in LETTERS }
        if (code.length != 12) return false
        Prefs.setString(c, "sync_code", code)
        ProfileManager.getProfiles(c).forEach { Prefs.setString(c, "sync_base_$it", null) }
        return true
    }

    fun stop(c: Context) = Prefs.setString(c, "sync_code", null)

    fun pretty(code: String) = code.chunked(4).joinToString("-")

    // ---------- What belongs to a person ----------

    private fun prefs(c: Context) = c.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    /** Everything stored for this person (keys ending "@<person>"), as JSON values with their type. */
    private fun snapshot(c: Context, profile: String): JSONObject {
        val tail = "@" + Accounts.scoped(c, profile)
        val out = JSONObject()
        for ((k, v) in prefs(c).all) {
            if (!k.endsWith(tail) || v == null) continue
            val name = k.removeSuffix(tail)
            out.put(name, JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }).put("v", v.toString()))
        }
        return out
    }

    private fun write(c: Context, profile: String, data: JSONObject) {
        val tail = "@" + Accounts.scoped(c, profile)
        val e = prefs(c).edit()
        val keep = data.keys().asSequence().toSet()
        for (k in prefs(c).all.keys) if (k.endsWith(tail) && k.removeSuffix(tail) !in keep) e.remove(k)
        for (name in keep) {
            val o = data.getJSONObject(name); val v = o.getString("v"); val k = name + tail
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, v.toBoolean()); "i" -> e.putInt(k, v.toInt()); "l" -> e.putLong(k, v.toLong())
                "f" -> e.putFloat(k, v.toFloat()); else -> e.putString(k, v)
            }
        }
        e.apply()
    }

    // ---------- Keeping in step ----------

    @Volatile private var busy = false
    @Volatile private var lastRun = 0L

    /** Called every so often: send this TV's changes and take the other TV's. */
    fun tick(c: Context, force: Boolean = false) {
        val code = code(c) ?: return
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastRun < 90_000L)) return
        busy = true; lastRun = now
        val app = c.applicationContext
        Thread {
            try { for (p in ProfileManager.getProfiles(app)) syncProfile(app, code, p) } catch (e: Exception) {}
            busy = false
        }.start()
    }

    private fun syncProfile(c: Context, code: String, profile: String) {
        val id = sha("tv-sync:$code:$profile")
        val local = snapshot(c, profile)
        val base = Prefs.string(c, "sync_base_$profile")?.let { JSONObject(it) } ?: JSONObject()
        val remote = try { fetch(code, id) } catch (e: Exception) { return }
        val merged = if (remote == null) local else merge(base, local, remote)
        if (!same(merged, local)) write(c, profile, merged)
        if (remote == null || !same(merged, remote)) put(code, id, merged)
        Prefs.setString(c, "sync_base_$profile", merged.toString())
    }

    /** Three-way merge per item: whoever changed it wins; both changed -> lists join, otherwise the other TV's. */
    private fun merge(base: JSONObject, local: JSONObject, remote: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = (local.keys().asSequence() + remote.keys().asSequence() + base.keys().asSequence()).toSet()
        for (k in keys) {
            val b = base.optJSONObject(k)?.toString(); val l = local.optJSONObject(k); val r = remote.optJSONObject(k)
            val pick: JSONObject? = when {
                l?.toString() == b -> r                   // only the other TV changed it (or deleted it)
                r?.toString() == b -> l                   // only this TV changed it
                l != null && r != null && k.startsWith("resume_v") -> joinResume(l, r)
                else -> r ?: l
            }
            if (pick != null) out.put(k, pick)
        }
        return out
    }

    /** Continue Watching from both TVs: one entry per title, the most recent kept. */
    private fun joinResume(a: JSONObject, b: JSONObject): JSONObject = try {
        val all = JSONArray(a.getString("v")).let { x -> (0 until x.length()).map { x.getJSONObject(it) } } +
            JSONArray(b.getString("v")).let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
        val best = all.groupBy { it.optString("k", it.toString()) }.values.map { g -> g.maxByOrNull { it.optLong("at") }!! }
            .sortedByDescending { it.optLong("at") }.take(40)
        JSONObject().put("t", "s").put("v", JSONArray(best).toString())
    } catch (e: Exception) { b }

    private fun same(a: JSONObject, b: JSONObject): Boolean {
        val ka = a.keys().asSequence().toSet(); val kb = b.keys().asSequence().toSet()
        return ka == kb && ka.all { a.getJSONObject(it).toString() == b.getJSONObject(it).toString() }
    }

    // ---------- Online, locked with the code ----------

    private fun fetch(code: String, id: String): JSONObject? {
        val text = rpc("sync_get", JSONObject().put("p_id", id)).trim()
        if (text.isEmpty() || text == "null") return null
        val o = JSONObject(text)
        return JSONObject(unlock(code, o.getString("blob")))
    }

    private fun put(code: String, id: String, data: JSONObject) {
        rpc("sync_put", JSONObject().put("p_id", id).put("p_blob", lock(code, data.toString())).put("p_ts", System.currentTimeMillis()))
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Volatile private var keyFor: Pair<String, SecretKeySpec>? = null
    private fun key(code: String): SecretKeySpec {
        keyFor?.takeIf { it.first == code }?.let { return it.second }
        val spec = PBEKeySpec(code.toCharArray(), "tv-sync-v1:$code".toByteArray(), 60_000, 256)
        val k = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        keyFor = code to k
        return k
    }

    private fun lock(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun unlock(code: String, blob: String): String {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all, 0, 12))
        return String(cipher.doFinal(all, 12, all.size - 12))
    }

    private fun rpc(fn: String, body: JSONObject): String {
        val conn = URL("$BASE/$fn").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return text
        } finally { conn.disconnect() }
    }
}
