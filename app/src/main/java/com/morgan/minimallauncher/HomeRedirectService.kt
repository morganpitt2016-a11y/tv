package com.morgan.minimallauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV has no setting to change the home app, and its Home button
 * always opens the stock Google TV launcher. This accessibility service
 * (the same approach launchers like Projectivy use) watches for the stock
 * home screen coming to the front - at power-on, after pressing Home, or
 * when leaving an app - and immediately opens Minimal Launcher instead.
 *
 * It only reads which app's window is in front (no screen content), and
 * does nothing while "Open automatically" is switched off in Settings.
 */
class HomeRedirectService : AccessibilityService() {

    private var otherHomes: Set<String> = emptySet()
    private var lastLaunch = 0L

    override fun onServiceConnected() {
        otherHomes = findOtherHomeApps()
        // Covers power-on: the service starts after boot, when the stock
        // home screen is (or is about to be) showing.
        if (AutoLaunch.isEnabled(this)) openLauncher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!AutoLaunch.isEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in otherHomes) openLauncher()
    }

    override fun onInterrupt() = Unit

    private fun openLauncher() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 1500) return // avoid bouncing on repeated events
        lastLaunch = now
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    /** Every other installed home app, minus setup wizards and fallbacks. */
    private fun findOtherHomeApps(): Set<String> {
        val found = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }
        return (found + KNOWN_STOCK_LAUNCHERS)
            .filter { it != packageName && "setup" !in it && it != "com.android.settings" && it != "com.android.tv.settings" }
            .toSet()
    }

    companion object {
        private val KNOWN_STOCK_LAUNCHERS = listOf(
            "com.google.android.apps.tv.launcherx", // Google TV home
            "com.google.android.tvlauncher",        // Android TV home
            "com.google.android.leanbacklauncher",  // older Android TV home
        )

        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, HomeRedirectService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}

/** The "Open automatically" preference (on by default once the service is enabled). */
object AutoLaunch {
    private const val PREFS = "launcher_settings"
    private const val KEY = "auto_launch"

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
