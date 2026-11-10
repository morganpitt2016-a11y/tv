package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every TV running the launcher checks in now and then (its code, name, model and version).
 * From a Master profile, with the activation code, the owner sees them all and can cut any of
 * them off - that TV then shows a locked screen until it's let back in.
 */
object Fleet {
    data class Device(val id: String, val name: String, val model: String, val build: Int, val blocked: Boolean, val lastSeen: Long)

    fun isBlocked(c: Context) = Prefs.bool(c, "tv_blocked", false)

    private fun nameFor(c: Context): String =
        Accounts.active(c)?.name?.takeIf { it.isNotBlank() } ?: Prefs.string(c, "remote_name") ?: android.os.Build.MODEL

    @Volatile private var lastCheck = 0L

    /** Check in (at most every 10 minutes, or now if [force]): learns whether this TV is still allowed. */
    fun checkin(c: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 10 * 60_000L) return
        lastCheck = now
        val app = c.applicationContext
        Thread {
            try {
                val blocked = Activation.rpc("device_checkin", JSONObject().put("p_id", Diag.deviceId(app)).put("p_name", nameFor(app))
                    .put("p_model", android.os.Build.MODEL).put("p_build", Updater.installedBuild(app))).trim() == "true"
                val was = isBlocked(app)
                Prefs.setBool(app, "tv_blocked", blocked)
                if (blocked && !was) {
                    // Cut off: show the locked screen straight away, whatever is on.
                    try { app.startActivity(Intent(app, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }.start()
    }

    /** The owner's proof: the activation code, hashed the same way it was stored. */
    fun proof(code: String): String? = Activation.salt()?.let { Activation.hash(it, code) }

    fun list(proof: String): List<Device> {
        val a = JSONArray(Activation.rpc("device_list", JSONObject().put("p_hash", proof)))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Device(it.getString("id"), it.optString("name"), it.optString("model"), it.optInt("build"), it.optBoolean("blocked"), it.optLong("last_seen") * 1000)
        }
    }

    fun block(proof: String, id: String, blocked: Boolean): Boolean =
        Activation.rpc("device_block", JSONObject().put("p_hash", proof).put("p_id", id).put("p_blocked", blocked)).trim() == "true"

    fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000L
        return when {
            m < 2 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago"
        }
    }

    // ---------- Manage TVs (Settings, Master only) ----------

    fun manage(a: Activity) {
        val d = GlassDialog(a).title("Manage TVs").message("Enter your activation code to see every TV using this launcher.").input("Activation code", secret = true)
        d.button("Continue", GlassDialog.Style.BOLD) {
            val code = d.inputText
            GlassUi.toast(a, "Checking…")
            Thread {
                val p = try { proof(code) } catch (e: Exception) { null }
                val devices = try { p?.let { list(it) } } catch (e: Exception) { null }
                a.runOnUiThread {
                    if (a.isDestroyed) return@runOnUiThread
                    when {
                        p == null -> GlassDialog(a).title("No Activation Code Yet").message("Set one first: Settings › Copy to Another TV › Activation Code.").button("OK", GlassDialog.Style.BOLD).show()
                        devices == null -> GlassUi.toast(a, "That code isn’t right")
                        else -> showList(a, p, devices)
                    }
                }
            }.start()
        }
        d.button("Cancel").show()
    }

    private fun showList(a: Activity, proof: String, devices: List<Device>) {
        val me = Diag.deviceId(a)
        val d = GlassDialog(a).title("TVs Using This Launcher").message("Choose a TV to cut it off or let it back in. This TV can’t be cut off from here.").scrolling()
        for (dev in devices) {
            val mine = dev.id == me
            val label = (dev.name.ifBlank { dev.model }) + (if (mine) " (this TV)" else "") + "  ·  1.${dev.build}  ·  " + ago(dev.lastSeen) + (if (dev.blocked) "  ·  CUT OFF" else "")
            d.button(label, if (dev.blocked) GlassDialog.Style.DESTRUCTIVE else GlassDialog.Style.NORMAL) {
                if (mine) { GlassUi.toast(a, "That’s this TV"); showList(a, proof, devices); return@button }
                GlassDialog(a).title(dev.name.ifBlank { dev.model })
                    .message(if (dev.blocked) "Let this TV use the launcher again?" else "Cut this TV off? It will show a locked screen until you let it back in.")
                    .button(if (dev.blocked) "Let It Back In" else "Cut It Off", if (dev.blocked) GlassDialog.Style.BOLD else GlassDialog.Style.DESTRUCTIVE) {
                        Thread {
                            val ok = try { block(proof, dev.id, !dev.blocked) } catch (e: Exception) { false }
                            val fresh = try { list(proof) } catch (e: Exception) { devices }
                            a.runOnUiThread {
                                if (a.isDestroyed) return@runOnUiThread
                                GlassUi.toast(a, if (!ok) "Couldn’t change it - try again" else if (dev.blocked) "${dev.name.ifBlank { dev.model }} can use the launcher again" else "${dev.name.ifBlank { dev.model }} is cut off (takes effect within 10 minutes)")
                                showList(a, proof, fresh)
                            }
                        }.start()
                    }
                    .button("Cancel") { showList(a, proof, devices) }.show()
            }
        }
        d.button("Done", GlassDialog.Style.BOLD).show()
    }
}

/** What a cut-off TV sees. It checks every minute in case it's been let back in. */
class BlockedActivity : AppCompatActivity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        GlassUi.installBackdrop(root)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(GlassUi.text(this, "Access Removed", 30f, R.font.inter_semibold).apply { gravity = Gravity.CENTER })
        col.addView(GlassUi.text(this, "The owner has switched this TV off from the launcher.\nIt comes back on its own if they let it back in.", 15f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
    private val recheck = object : Runnable {
        override fun run() {
            Fleet.checkin(this@BlockedActivity, force = true)
            handler.postDelayed({ if (!Fleet.isBlocked(this@BlockedActivity)) { startActivity(Intent(this@BlockedActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() } }, 4000)
            handler.postDelayed(this, 60_000)
        }
    }
    override fun onStart() { super.onStart(); handler.post(recheck) }
    override fun onStop() { super.onStop(); handler.removeCallbacksAndMessages(null) }
    @Deprecated("Back can't skip the lock")
    override fun onBackPressed() { /* stays locked */ }
}
