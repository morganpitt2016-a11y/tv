package com.morgan.minimallauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

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

    private var detector: HomeDetector? = null
    private var lastLaunch = 0L
    private var keepAliveWindow: View? = null

    /** "Screen on" is only announced to running apps, so listen from here. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && AutoLaunch.onWake(context)) {
                RedirectDiag.event(context, "wake")
                lastLaunch = 0L // always act on a wake-up, even right after another switch
                openLauncher()
            }
        }
    }
    private var wakeReceiverRegistered = false

    override fun onServiceConnected() {
        instance = this
        detector = HomeDetector(this).also { RedirectDiag.watching(this, it.describe()) }
        addKeepAliveWindow()
        RedirectDiag.connected(this, keepAliveWindow != null)
        if (!wakeReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wakeReceiverRegistered = true
        }
        // Covers power-on: the service starts shortly after boot. Only then -
        // the service also (re)connects when it's switched on in Settings,
        // and jumping to the launcher at that moment yanks you out of the
        // accessibility screen.
        if (AutoLaunch.onBoot(this) && SystemClock.elapsedRealtime() < 3 * 60_000L) {
            RedirectDiag.event(this, "boot")
            openLauncher()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!AutoLaunch.isEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        RedirectDiag.seen(this, "$pkg/${cls.substringAfterLast('.')}")
        if (detector?.isHomeScreen(pkg, cls) == true) openLauncher()
    }

    override fun onInterrupt() = Unit

    /**
     * Back while watching a channel that Live TV opened in TiviMate: go back to
     * the Watch guide instead of TiviMate's own menus. Every other key, and Back
     * anywhere else (including TiviMate's lists), passes straight through.
     */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK || !TiviTuner.returnToGuide) return false
        val front = rootInActiveWindow?.packageName?.toString()
        if (front != LiveGuide.TIVIMATE) {
            if (front != null && front != packageName) TiviTuner.returnToGuide = false
            return false
        }
        if (!TiviTuner.isWatchingFullScreen(this)) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            TiviTuner.returnToGuide = false
            try {
                startActivity(Intent(this, WatchActivity::class.java)
                    .putExtra(WatchActivity.EXTRA_TAB, "live")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) {}
        }
        return true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
            wakeReceiverRegistered = false
        }
        keepAliveWindow?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        keepAliveWindow = null
        super.onDestroy()
    }

    /**
     * Android only lets an app open a screen from the background if it
     * already has a visible window. Accessibility services may add
     * overlay windows without extra permissions, so keep an invisible
     * 1x1 pixel one (it can't be seen, focused or touched).
     */
    private fun addKeepAliveWindow() {
        if (keepAliveWindow != null) return
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            keepAliveWindow = view
        } catch (e: Exception) {
            RedirectDiag.error(this, "overlay: ${e.javaClass.simpleName}")
        }
    }

    private fun openLauncher() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 1500) return // avoid bouncing on repeated events
        lastLaunch = now
        RedirectDiag.attempted(this)
        try {
            AutoLaunch.open(this)
        } catch (e: Exception) {
            RedirectDiag.error(this, "launch: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------- iPhone remote ----------

    /** Presses a remote button anywhere on the TV (Android 13+ for the arrows). */
    fun press(key: String): Boolean {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> if (android.os.Build.VERSION.SDK_INT >= 33) when (key) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                "right" -> GLOBAL_ACTION_DPAD_RIGHT
                "select" -> GLOBAL_ACTION_DPAD_CENTER
                else -> return false
            } else return false
        }
        return performGlobalAction(action)
    }

    /** Types into the text box that has focus, in any app. */
    fun type(text: String, replace: Boolean): Boolean {
        val node = rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val current = if (replace) "" else node.text?.toString().orEmpty().takeUnless { node.isShowingHintText }.orEmpty()
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Every other installed home app, minus setup wizards and fallbacks. */
    companion object {
        @Volatile var instance: HomeRedirectService? = null

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

    /** Open the launcher when the TV finishes starting up. */
    fun onBoot(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on_boot", true)

    fun setOnBoot(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on_boot", on).apply()

    /** Open the launcher when the TV wakes from sleep, whatever was open before. */
    fun onWake(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on_wake", true)

    fun setOnWake(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on_wake", on).apply()

    /** Bring the launcher to the front; used by the service and the boot receiver. */
    fun open(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}

/**
 * What the auto-open service has seen and done, shown in Settings so a
 * problem can be diagnosed from a photo of the screen.
 */
object RedirectDiag {
    private const val PREFS = "redirect_diag"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis()

    fun connected(c: Context, overlayOk: Boolean) =
        prefs(c).edit().putLong("connected", now()).putBoolean("overlay", overlayOk).apply()

    fun seen(c: Context, pkg: String) {
        val recent = (prefs(c).getString("seen", "") ?: "").split(",").filter { it.isNotBlank() && it != pkg }
        prefs(c).edit().putString("seen", (listOf(pkg) + recent).take(4).joinToString(",")).apply()
    }

    fun attempted(c: Context) = prefs(c).edit().putLong("attempt", now()).apply()
    fun watching(c: Context, what: String) = prefs(c).edit().putString("watching", what).apply()

    /** e.g. "boot" or "wake", shown with its time. */
    fun event(c: Context, name: String) = prefs(c).edit().putLong("event_$name", now()).apply()
    fun launcherShown(c: Context) = prefs(c).edit().putLong("shown", now()).apply()
    fun error(c: Context, msg: String) = prefs(c).edit().putString("error", msg).apply()

    fun summary(c: Context): String {
        val p = prefs(c)
        fun time(key: String) = p.getLong(key, 0L).let { if (it == 0L) "never" else DateFormat.format("HH:mm:ss", it).toString() }
        val attempt = p.getLong("attempt", 0L)
        val shown = p.getLong("shown", 0L)
        val result = when {
            attempt == 0L -> "no attempt yet"
            shown >= attempt && shown - attempt < 5000 -> "launcher opened"
            else -> "launcher did NOT open"
        }
        return buildString {
            append("Service started: ").append(time("connected"))
            append(if (p.getBoolean("overlay", false)) " (overlay ok)" else " (overlay missing)").append('\n')
            append("Watching for: ").append(p.getString("watching", "").orEmpty().ifBlank { "nothing found" }).append('\n')
            append("Recent screens: ").append(p.getString("seen", "").orEmpty().ifBlank { "none" }).append('\n')
            append("Last switch: ").append(time("attempt")).append(" → ").append(result).append('\n')
            append("Last power-on: ").append(time("event_boot")).append("   Last wake: ").append(time("event_wake"))
            p.getString("error", null)?.let { append('\n').append("Error: ").append(it) }
        }
    }
}
