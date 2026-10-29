package com.morgan.minimallauncher

import android.content.Context

/**
 * Powers the "Keep watching"-style row with real, local data: the last
 * few apps actually opened from this launcher. It deliberately does NOT
 * show fake show titles/episode progress - no third-party launcher can
 * read another app's real playback state without Google's own internal
 * Android TV Home APIs, so inventing that data would just be lying to
 * the person looking at it. This is the honest equivalent.
 */
object RecentAppsTracker {
    private const val PREFS = "recent_apps"
    private const val KEY_ORDER = "order"
    private const val MAX_ENTRIES = 6

    fun recordOpened(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getString(KEY_ORDER, "") ?: "")
            .split(",").filter { it.isNotBlank() }.toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_ORDER, current.joinToString(",")).apply()
    }

    fun getRecent(context: Context, allApps: List<AppEntry>, limit: Int = 3): List<AppEntry> {
        val order = (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, "") ?: "").split(",").filter { it.isNotBlank() }
        val byPackage = allApps.associateBy { it.packageName }
        return order.mapNotNull { byPackage[it] }.take(limit)
    }
}
