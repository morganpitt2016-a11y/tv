package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.os.Build
import java.util.Calendar

/**
 * Health Check: one look at everything the launcher relies on (internet, VPN, Dodgy Box, the guide,
 * updates, the remote service, space), each with a plain-English fix. It also runs quietly every
 * night at 4am and, if something's wrong, mentions it the next time you're on the home screen.
 */
object Health {
    data class Check(val name: String, val ok: Boolean, val detail: String, val fix: String = "")

    /** Runs every check (takes a few seconds - call off the main thread). */
    fun run(c: Context): List<Check> {
        val app = c.applicationContext
        val out = mutableListOf<Check>()
        val online = try { Net.get("https://clients3.google.com/generate_204", timeoutMs = 8_000); true } catch (e: Exception) { false }
        out += Check("Internet", online, if (online) "Connected" else "Not connected", "Check the Wi-Fi in Settings › Wi-Fi, or restart the router.")
        if (Xtream.isSetUp(app)) {
            if (Xtream.needsVpn(app)) {
                val vpn = Xtream.vpnOn(app)
                out += Check("VPN", vpn, if (vpn) "On" else "Off", "Dodgy Box needs the VPN on this TV - open ${PlayerActivity.vpnApp(app)?.second ?: "your VPN app"} and connect.")
            }
            val login = try { Xtream.check(app) } catch (e: Exception) { null }
            out += Check("Dodgy Box", login != null, login ?: "Not answering",
                if (online) "Turn the VPN on if this TV needs it, then try again. Still failing? Check the login in Settings › Dodgy Box Login." else "Fix the internet first.")
            val exp = Prefs.string(app, "xt_exp")?.toLongOrNull()
            if (exp != null) {
                val days = ((exp - System.currentTimeMillis()) / 86_400_000L).toInt()
                out += Check("Subscription", days >= 3, when { days < 0 -> "Expired"; days == 0 -> "Ends today"; else -> "$days days left" },
                    "Renew it with your Dodgy Box seller.")
            }
            val chans = if (login != null) try { Xtream.liveCategories(app).isNotEmpty() } catch (e: Exception) { false } else false
            out += Check("TV Guide", chans, if (chans) "Channels loading" else "No channels", "Usually the VPN or the internet - fix those first.")
        } else out += Check("Dodgy Box", false, "Not signed in", "Settings › Dodgy Box Login.")
        val latest = try {
            org.json.JSONObject(Net.get("https://api.github.com/repos/morganpitt2016-a11y/tv/contents/dist/version.json?ref=main",
                mapOf("Accept" to "application/vnd.github.raw"), timeoutMs = 10_000)).getInt("build")
        } catch (e: Exception) { -1 }
        val have = Updater.installedBuild(app)
        out += Check("Updates", latest <= have, if (latest <= have) "Latest version (1.$have)" else "1.$latest is out (this TV has 1.$have)", "Settings › About › Check for Updates.")
        val service = HomeRedirectService.instance != null
        out += Check("Remote Service", service, if (service) "Running" else "Off",
            "Settings › Accessibility › Minimal Launcher › On (it lets Home, the remote and reminders work).")
        if (Build.VERSION.SDK_INT >= 26) {
            val can = app.packageManager.canRequestPackageInstalls()
            out += Check("Auto Updates", can, if (can) "Allowed" else "Not allowed", "Settings › Apps › Special app access › Install unknown apps › Minimal Launcher › Allow.")
        }
        val freeMb = (app.filesDir.usableSpace / 1_048_576L).toInt()
        out += Check("Storage", freeMb > 400, "${freeMb} MB free", "Remove apps you don’t use, or clear caches in Settings › Apps.")
        Prefs.setString(app, "health_bad", out.filterNot { it.ok }.joinToString(", ") { it.name }.ifEmpty { null })
        return out
    }

    /** The full check, shown in a box. */
    fun show(activity: Activity) {
        GlassUi.toast(activity, "Checking everything…")
        Bg.run {
            val list = run(activity)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val bad = list.filterNot { it.ok }
                val text = list.joinToString("\n") { (if (it.ok) "✓  " else "✗  ") + it.name + "  ·  " + it.detail } +
                    if (bad.isEmpty()) "\n\nEverything’s working." else "\n\nTo fix:\n" + bad.joinToString("\n") { "• ${it.name}: ${it.fix}" }
                GlassDialog(activity).title(if (bad.isEmpty()) "All Good" else "Health Check").message(text)
                    .button("Done", GlassDialog.Style.BOLD).show()
            }
        }
    }

    /** Once a night at 4am (or the first chance after), quietly. */
    fun nightly(c: Context) {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (cal.get(Calendar.HOUR_OF_DAY) < 4 || Prefs.int(c, "health_day", 0) == today) return
        Prefs.setInt(c, "health_day", today)
        Bg.run { try { run(c) ; Prefs.setBool(c, "health_told", false) } catch (e: Exception) {} }
    }

    /** On the home screen: mention a problem the nightly check found (once). */
    fun mention(activity: Activity) {
        val bad = Prefs.string(activity, "health_bad") ?: return
        if (Prefs.bool(activity, "health_told", false)) return
        Prefs.setBool(activity, "health_told", true)
        GlassUi.toast(activity, "Needs attention: $bad - see Settings › About › Health Check")
    }
}
