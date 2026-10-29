package com.morgan.minimallauncher

import android.content.Context

/**
 * Powers the "Keep watching" row with real, local data: the last few apps
 * opened from this launcher and when. It does not show show titles or
 * episode progress: a third-party launcher can't read another app's
 * playback state (Google's own launcher uses privileged APIs for that).
 */
object RecentAppsTracker {
    private const val PREFS = "recent_apps"
    private const val KEY_ORDER = "order"
    private const val MAX_ENTRIES = 6

    data class Recent(val app: AppEntry, val openedAt: Long)

    fun recordOpened(context: Context, packageName: String) {
        val entries = load(context).filter { it.first != packageName }.toMutableList()
        entries.add(0, packageName to System.currentTimeMillis())
        save(context, entries.take(MAX_ENTRIES))
    }

    fun getRecent(context: Context, allApps: List<AppEntry>, limit: Int): List<Recent> {
        val byPackage = allApps.associateBy { it.packageName }
        return load(context)
            .mapNotNull { (pkg, time) -> byPackage[pkg]?.let { Recent(it, time) } }
            .take(limit)
    }

    /** Entries are stored as "package@millis", newest first. */
    private fun load(context: Context): List<Pair<String, Long>> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORDER, "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .map { raw ->
                val at = raw.lastIndexOf('@')
                if (at < 0) raw to 0L else raw.substring(0, at) to (raw.substring(at + 1).toLongOrNull() ?: 0L)
            }

    private fun save(context: Context, entries: List<Pair<String, Long>>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, entries.joinToString(",") { "${it.first}@${it.second}" })
            .apply()
    }
}
