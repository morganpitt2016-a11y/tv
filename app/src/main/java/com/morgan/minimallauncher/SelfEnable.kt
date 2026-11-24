package com.morgan.minimallauncher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Switches Minimal Launcher's accessibility service (and Now Playing's
 * notification access) on by itself - no settings pages needed.
 *
 * Android only allows that for apps holding WRITE_SECURE_SETTINGS, which
 * has to be granted once over ADB (see [ADB_COMMAND]; it can be done from
 * an iPhone with the free iSH app - the iPhone remote's More tab shows
 * the steps). After that, the service is turned back on automatically
 * whenever it gets switched off (e.g. after an app update).
 */
object SelfEnable {

    const val ADB_COMMAND = "adb shell pm grant com.morgan.minimallauncher android.permission.WRITE_SECURE_SETTINGS"

    fun canSelfEnable(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Turns everything on if allowed. Returns true if the accessibility service is (now) on. */
    fun enableAll(c: Context): Boolean {
        if (HomeRedirectService.isServiceEnabled(c)) { enableListener(c); return true }
        if (!canSelfEnable(c)) return false
        return try {
            val me = ComponentName(c, HomeRedirectService::class.java).flattenToString()
            val cr = c.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = current.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(me) }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            enableListener(c)
            true
        } catch (e: Exception) {
            RedirectDiag.error(c, "self-enable: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Switched on but not running: on Fire TV an app update leaves the accessibility service listed as on but never
     * starts it again, so Home stays on Amazon's screen. Switching it off and on again in settings makes the system
     * start it. Only when it was already on (your choice) and the app has the ADB permission.
     */
    fun rebindIfStale(c: Context) {
        if (!canSelfEnable(c) || !HomeRedirectService.isServiceEnabled(c) || HomeRedirectService.instance != null) return
        // Give the system a chance to start it on its own first (it does on a fresh boot).
        if (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis() < 15_000L) return
        try {
            val me = ComponentName(c, HomeRedirectService::class.java)
            val cr = c.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val others = current.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            // Back on 2 seconds later: switched off and on in the same instant, Fire OS never notices either change
            // and the service stays stopped.
            val app = c.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val now = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                        .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me.flattenToString()).joinToString(":"))
                    Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (e: Exception) { RedirectDiag.error(app, "rebind: ${e.javaClass.simpleName}") }
            }, 2_000L)
            RedirectDiag.event(c, "rebind")
        } catch (e: Exception) { RedirectDiag.error(c, "rebind: ${e.javaClass.simpleName}") }
    }

    /** Notification access for the Now Playing widget. */
    private fun enableListener(c: Context) {
        if (!canSelfEnable(c) || NowPlaying.hasAccess(c)) return
        try {
            val me = ComponentName(c, MediaListener::class.java).flattenToString()
            val key = "enabled_notification_listeners"
            val current = Settings.Secure.getString(c.contentResolver, key).orEmpty()
            Settings.Secure.putString(c.contentResolver, key, (current.split(':').filter { it.isNotBlank() && it != me } + me).joinToString(":"))
        } catch (e: Exception) {}
    }
}
