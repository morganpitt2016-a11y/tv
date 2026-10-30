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
