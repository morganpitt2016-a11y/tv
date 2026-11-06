package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The activation lock: a new install asks for the owner's code before it does anything.
 * The code is set once from the owner's TV and only a scrambled copy is kept online,
 * so it's never in the app, on GitHub or anywhere readable.
 */
object Activation {
    private const val BASE = "https://ligqohydmupgahmlxqvy.supabase.co/rest/v1/rpc"
    private const val KEY = "sb_publishable_q0N-x_5djDRRq3ZHLfGAqQ_8_4_939Y"

    fun isActivated(c: Context): Boolean {
        if (Prefs.bool(c, "activated", false)) return true
        // TVs that were already set up before the lock existed stay unlocked.
        if (!Accounts.needsSetup(c)) { Prefs.setBool(c, "activated", true); return true }
        return false
    }

    /** Call at the top of a screen: shows the lock instead if this TV isn't activated. Returns true if it's locked. */
    fun guard(a: Activity): Boolean {
        if (isActivated(a)) return false
        a.startActivity(Intent(a, ActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        a.finish()
        return true
    }

    private fun hash(salt: String, code: String) =
        MessageDigest.getInstance("SHA-256").digest("tv-activation:$salt:${code.trim().uppercase()}".toByteArray()).joinToString("") { "%02x".format(it) }

    /** The salt, or null if no code has been set yet (call off the main thread). */
    fun salt(): String? = rpc("activation_salt", JSONObject()).trim().removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "null" }

    /** Checks a typed code (off the main thread). */
    fun check(c: Context, code: String): Boolean {
        val salt = salt() ?: return true.also { Prefs.setBool(c, "activated", true) }   // no code set yet: nothing to lock
        val ok = rpc("activation_check", JSONObject().put("p_hash", hash(salt, code))).trim() == "true"
        if (ok) Prefs.setBool(c, "activated", true)
        return ok
    }

    /** Sets the code for the first time (off the main thread). False if one was already set. */
    fun set(code: String): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return rpc("activation_set", JSONObject().put("p_salt", salt).put("p_hash", hash(salt, code))).trim() == "true"
    }

    private fun rpc(fn: String, body: JSONObject): String {
        val conn = URL("$BASE/$fn").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 20_000
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

/** The lock screen on a new install: type the owner's code to carry on. */
class ActivationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        GlassUi.installBackdrop(root)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(GlassUi.text(this, "Minimal Launcher", 30f, R.font.inter_semibold).apply { gravity = Gravity.CENTER })
        col.addView(GlassUi.text(this, "This TV needs the owner’s activation code.", 15f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.post { ask() }
    }

    private fun ask() {
        if (isDestroyed) return
        val d = GlassDialog(this).title("Activation Code").message("Enter the code to use this launcher.").input("Code", secret = true)
        d.button("Unlock", GlassDialog.Style.BOLD) {
            val code = d.inputText
            GlassUi.toast(this, "Checking…")
            Thread {
                val r = try { if (Activation.check(this, code)) 1 else 0 } catch (e: Exception) { -1 }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    when (r) {
                        1 -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() }
                        0 -> { GlassUi.toast(this, "That code isn’t right"); ask() }
                        else -> { GlassUi.toast(this, "Couldn’t check - connect to the internet and try again"); ask() }
                    }
                }
            }.start()
        }
        d.show().setOnCancelListener { root().postDelayed({ ask() }, 400) }
    }

    private fun root() = window.decorView

    @Deprecated("Back can't skip the lock")
    override fun onBackPressed() { /* stays locked */ }
}
