package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/**
 * Shared "list every launchable TV app" logic, used by the home screen
 * dock (a pinned subset), the All Apps screen (everything), and the
 * Settings screen's pin-picker (everything, with checkboxes).
 */
object AppListHelper {
    /** The app's sharpest icon (xxxhdpi), so it stays crisp when it grows on focus. */
    private fun hiResIcon(pm: PackageManager, ri: ResolveInfo): Drawable = try {
        val res = ri.activityInfo.iconResource
        if (res == 0) ri.loadIcon(pm)
        else pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
            .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null) ?: ri.loadIcon(pm)
    } catch (e: Exception) {
        ri.loadIcon(pm)
    }

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
                    icon = hiResIcon(pm, it),
                    launchIntent = pm.getLeanbackLaunchIntentForPackage(it.activityInfo.packageName)
                        ?: pm.getLaunchIntentForPackage(it.activityInfo.packageName)
                )
            }
            .filter { it.launchIntent != null }
            .sortedBy { it.label.lowercase() }
    }
}
