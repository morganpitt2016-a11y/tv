package com.morgan.minimallauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Copy to Another TV": packs this TV's setup (profiles, lists, settings, Dodgy Box login), locks it
 * with a short code, and leaves it in a drop-box online for up to an hour. The other TV types the
 * code, collects it (which removes it from the drop-box) and becomes an exact copy.
 * The drop-box only ever holds the locked copy; without the code nobody can read it.
 */
object Transfer {
    private const val BASE = "https://ligqohydmupgahmlxqvy.supabase.co/rest/v1/rpc"
    private const val KEY = "sb_publishable_q0N-x_5djDRRq3ZHLfGAqQ_8_4_939Y"
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Settings that belong to one TV only and are never copied. */
    private val SKIP_KEYS = listOf("diag_id", "diag_on", "remote_key", "upd_last", "upd_pending")
    private const val MAX_FILE = 3 * 1024 * 1024
    private const val MAX_TOTAL = 8 * 1024 * 1024

    /** Pack, lock and upload. Returns the code to type on the other TV, e.g. "K7QM-3XPA". */
    fun send(c: Context): String {
        val app = c.applicationContext
        val code = (1..8).map { LETTERS[SecureRandom().nextInt(LETTERS.length)] }.joinToString("")
        val blob = lock(code, pack(app))
        rpc("transfer_put", JSONObject().put("p_id", idFor(code)).put("p_blob", blob))
        return code.substring(0, 4) + "-" + code.substring(4)
    }

    /** Download and unlock. Throws with a friendly reason. Call [apply] with the result. */
    fun receive(code: String): JSONObject {
        val clean = code.uppercase().filter { it in LETTERS }
        if (clean.length != 8) throw Exception("The code is 8 letters and numbers, like K7QM-3XPA")
        val text = rpc("transfer_take", JSONObject().put("p_id", idFor(clean))).trim()
        if (text.isEmpty() || text == "null") throw Exception("That code wasn’t found. Codes work once, for an hour - make a new one on the other TV.")
        val blob = JSONObject("{\"v\":$text}").getString("v")
        return try { unlock(clean, blob) } catch (e: Exception) { throw Exception("That code didn’t unlock the copy - check it and try again") }
    }

    /** Replace this TV's setup with the copy, then restart the launcher. */
    fun apply(c: Context, data: JSONObject) {
        val app = c.applicationContext
        val keepPrefs = readPrefs(app)   // this TV's own one-off settings
        val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        val prefs = data.getJSONObject("prefs")
        prefsDir.mkdirs()
        prefsDir.listFiles()?.forEach { it.delete() }
        for (name in prefs.keys()) {
            if (name.contains('/') || !name.endsWith(".xml")) continue
            var xml = prefs.getString(name)
            if (name == "launcher_settings.xml") xml = withKept(xml, keepPrefs)
            File(prefsDir, name).writeText(xml)
        }
        val files = data.getJSONObject("files")
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val root = app.filesDir.canonicalPath
        for (rel in files.keys()) {
            val f = File(app.filesDir, rel)
            if (!f.canonicalPath.startsWith("$root/")) continue
            f.parentFile?.mkdirs()
            f.writeBytes(Base64.decode(files.getString(rel), Base64.NO_WRAP))
        }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Come straight back, then stop now so nothing in memory writes over the copy.
        val open = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 1200, open)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------- Packing ----------

    private fun pack(app: Context): ByteArray {
        val prefs = JSONObject()
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".xml")) prefs.put(f.name, strip(f.readText()))
        }
        val files = JSONObject()
        var total = 0L
        app.filesDir.walkTopDown().filter { it.isFile }.sortedBy { it.length() }.forEach { f ->
            val len = f.length()
            if (len > MAX_FILE || total + len > MAX_TOTAL || f.name.startsWith("guide")) return@forEach
            total += len
            files.put(f.relativeTo(app.filesDir).path, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val json = JSONObject().put("v", 1).put("prefs", prefs).put("files", files).toString()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        return out.toByteArray()
    }

    private fun entry(key: String) = Regex("""\s*<(\w+) name="${Regex.escape(key)}"(?:[^>]*/>|[^>]*>[\s\S]*?</\1>)""")

    private fun strip(xml: String): String = SKIP_KEYS.fold(xml) { x, k -> x.replace(entry(k), "") }

    private fun readPrefs(app: Context): List<String> {
        val f = File(File(app.applicationInfo.dataDir, "shared_prefs"), "launcher_settings.xml")
        val xml = try { f.readText() } catch (e: Exception) { return emptyList() }
        return SKIP_KEYS.mapNotNull { k -> entry(k).find(xml)?.value?.trim() }
    }

    private fun withKept(xml: String, kept: List<String>): String {
        if (kept.isEmpty()) return xml
        val at = xml.lastIndexOf("</map>")
        if (at < 0) return xml
        return xml.substring(0, at) + kept.joinToString("") { "    $it\n" } + xml.substring(at)
    }

    // ---------- Locking ----------

    private fun idFor(code: String): String =
        MessageDigest.getInstance("SHA-256").digest("tv-transfer:$code".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun lock(code: String, plain: ByteArray): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(128, iv))
        return Base64.encodeToString(salt + iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unlock(code: String, blob: String): JSONObject {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val salt = all.copyOfRange(0, 16); val iv = all.copyOfRange(16, 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code, salt), GCMParameterSpec(128, iv))
        val zipped = cipher.doFinal(all, 28, all.size - 28)
        val json = GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }.toString(Charsets.UTF_8)
        return JSONObject(json)
    }

    // ---------- Drop-box ----------

    private fun rpc(fn: String, body: JSONObject): String {
        val conn = URL("$BASE/$fn").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception("Couldn’t reach the copy service (error $code) - check the internet and try again")
            return text
        } finally { conn.disconnect() }
    }
}
