package com.morgan.minimallauncher

import android.app.NotificationManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.morgan.minimallauncher.GlassUi.dp
import org.json.JSONObject
import java.util.Calendar

/**
 * One button for movie night: dims the home screen, turns on Do Not
 * Disturb, dims the Govee lights (optional), shows a cinema-style clock,
 * then opens your streaming app. Press it again to undo everything.
 */
object CinemaMode {

    fun toggle(activity: MainActivity) {
        if (Prefs.cinemaOn(activity)) turnOff(activity) else turnOn(activity)
    }

    private fun turnOn(activity: MainActivity) {
        val app = Prefs.cinemaApp(activity)
        if (app == null) {
            chooseApp(activity) { turnOn(activity) }
            return
        }
        Prefs.setBool(activity, "cinema_on", true)
        activity.applyDimming(animate = true)
        if (Prefs.cinemaDnd(activity)) setDnd(activity, true)
        if (Prefs.cinemaLights(activity)) dimLights(activity)
        intro(activity) {
            val entry = AppListHelper.loadLaunchableTvApps(activity).firstOrNull { it.packageName == app }
            if (entry != null) AppActions.launch(activity, entry, null)
            else GlassUi.toast(activity, "Your cinema app isn’t installed. Choose another in Settings.")
        }
    }

    fun turnOff(activity: MainActivity) {
        Prefs.setBool(activity, "cinema_on", false)
        activity.applyDimming(animate = true)
        setDnd(activity, false)
        restoreLights(activity)
        GlassUi.toast(activity, "Cinema mode off")
    }

    /** Let the person pick the app cinema mode opens. */
    fun chooseApp(activity: android.app.Activity, then: (() -> Unit)? = null) {
        val apps = AppListHelper.loadLaunchableTvApps(activity)
        val current = Prefs.cinemaApp(activity)
        val d = GlassDialog(activity).title("Cinema App").message("Cinema mode opens this app.").scrolling()
        for (a in apps) {
            d.button(if (a.packageName == current) "${a.label}  ✓" else a.label,
                if (a.packageName == current) GlassDialog.Style.BOLD else GlassDialog.Style.NORMAL) {
                Prefs.setString(activity, "cinema_app", a.packageName)
                then?.invoke()
            }
        }
        d.button("Cancel")
        d.show()
    }

    fun appLabel(activity: android.app.Activity): String {
        val pkg = Prefs.cinemaApp(activity) ?: return "Not Chosen"
        return try {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "Not Installed" }
    }

    // ---------- Do Not Disturb ----------

    fun hasDndAccess(c: android.content.Context) =
        c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun setDnd(activity: MainActivity, on: Boolean) {
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            if (on) {
                Prefs.setInt(activity, "dnd_before", nm.currentInterruptionFilter)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                val before = Prefs.int(activity, "dnd_before", NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(if (before == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else before)
            }
        } catch (e: Exception) {}
    }

    // ---------- Lights ----------

    private fun dimLights(activity: MainActivity) {
        val lights = activity.lights().filter { it.isOn }
        if (lights.isEmpty()) return
        val app = activity.applicationContext
        Thread {
            val saved = JSONObject()
            for (d in lights) {
                saved.put(d.deviceId, GoveeHelper.readBrightness(app, d) ?: 100)
                GoveeHelper.setBrightness(app, d, 12)
            }
            Prefs.setString(app, "cinema_lights_before", saved.toString())
        }.start()
    }

    private fun restoreLights(activity: MainActivity) {
        val saved = try { JSONObject(Prefs.string(activity, "cinema_lights_before") ?: return) } catch (e: Exception) { return }
        val lights = activity.lights().filter { saved.has(it.deviceId) }
        val app = activity.applicationContext
        Prefs.setString(activity, "cinema_lights_before", null)
        Thread { for (d in lights) GoveeHelper.setBrightness(app, d, saved.optInt(d.deviceId, 100)) }.start()
    }

    // ---------- Cinema-style clock ----------

    /** Black screen, letterbox bars closing in, a thin tall clock - then [then]. */
    private fun intro(activity: MainActivity, then: () -> Unit) {
        val c = activity
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; isClickable = true }
        val barH = dp(c, 70f)
        val top = View(c).apply { setBackgroundColor(0xFF000000.toInt()); translationY = -barH.toFloat() }
        val bottom = View(c).apply { setBackgroundColor(0xFF000000.toInt()); translationY = barH.toFloat() }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f }
        val clock = GlassUi.text(c, DateFormat.format(if (DateFormat.is24HourFormat(c)) "H:mm" else "h:mm", Calendar.getInstance()), 150f, R.font.inter_regular, 0xFFF2E6D0.toInt())
        GlassUi.flex(clock, 160, 60)
        col.addView(clock)
        col.addView(GlassUi.text(c, "CINEMA", 13f, R.font.inter_semibold, 0x99F2E6D0.toInt()).apply { letterSpacing = 0.6f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(top, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, barH, Gravity.BOTTOM))
        content.addView(root, FrameLayout.LayoutParams(-1, -1))

        val ease = DecelerateInterpolator(2f)
        root.animate().alpha(1f).setDuration(600).start()
        top.animate().translationY(0f).setStartDelay(200).setDuration(900).setInterpolator(ease).start()
        bottom.animate().translationY(0f).setStartDelay(200).setDuration(900).setInterpolator(ease).start()
        col.scaleX = 0.96f; col.scaleY = 0.96f
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(500).setDuration(1200).setInterpolator(ease).withEndAction {
            col.postDelayed({
                then()
                // Gone by the time the person comes back home.
                root.postDelayed({ content.removeView(root) }, 800)
            }, 1100)
        }.start()
    }
}
