package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

/**
 * Shared "list every launchable TV app" logic, used by the home screen
 * dock (a pinned subset), the All Apps screen (everything), and the
 * Settings screen's pin-picker (everything, with checkboxes).
 */
object AppListHelper {
    fun loadLaunchableTvApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val results = mutableListOf<ResolveInfo>()

        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER")
        results += pm.queryIntentActivities(leanbackIntent, 0)

        if (results.isEmpty()) {
            val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            results += pm.queryIntentActivities(standardIntent, 0)
        }

        return results
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm),
                    launchIntent = pm.getLeanbackLaunchIntentForPackage(it.activityInfo.packageName)
                        ?: pm.getLaunchIntentForPackage(it.activityInfo.packageName)
                )
            }
            .filter { it.launchIntent != null }
            .sortedBy { it.label.lowercase() }
    }
}
