package com.morgan.minimallauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens the launcher once the TV has finished starting up. Android only
 * allows this from the background when the app has a visible window (the
 * accessibility service's invisible overlay) or "Display over other apps",
 * so the accessibility service also opens the launcher when it starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        AppWatchService.startIfNeeded(context)
        if (!AutoLaunch.onBoot(context)) return
        RedirectDiag.event(context, "boot")
        try {
            AutoLaunch.open(context)
        } catch (e: Exception) {
            RedirectDiag.error(context, "boot launch: ${e.javaClass.simpleName}")
        }
    }
}
