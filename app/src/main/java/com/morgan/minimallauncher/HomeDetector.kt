package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent

/**
 * Knows which screen is the stock Google TV / Android TV home screen, so
 * both auto-open methods (accessibility service and the usage-access
 * watcher) react to that screen only - not to other windows the stock
 * home app shows, like its settings side panel.
 */
class HomeDetector(context: Context) {

    private val myPackage = context.packageName

    /** Every other installed home app, minus setup wizards and fallbacks. */
    val otherHomes: Set<String> = (context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
    ).map { it.activityInfo.packageName } + KNOWN_STOCK_LAUNCHERS)
        .filter { it != myPackage && "setup" !in it && it != "com.android.settings" && it != "com.android.tv.settings" }
        .toSet()

    /** package -> class names of its home-screen activity (real class and any alias). */
    private val homeActivities: Map<String, Set<String>> = otherHomes.associateWith { pkg ->
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg), 0
        ).flatMap { listOfNotNull(it.activityInfo.name, it.activityInfo.targetActivity) }.toSet()
    }

    fun isHomeScreen(pkg: String, cls: String): Boolean {
        if (pkg !in otherHomes) return false
        val known = homeActivities[pkg].orEmpty()
        if (known.isNotEmpty()) return cls in known
        // Home activity couldn't be resolved: fall back to its likely name.
        return cls.contains("Home", ignoreCase = true) || cls.contains("Launcher", ignoreCase = true)
    }

    /** Short description for the diagnostics in Settings. */
    fun describe(): String = homeActivities.flatMap { (pkg, classes) ->
        classes.ifEmpty { setOf("?") }.map { "${pkg.substringAfterLast('.')}/${it.substringAfterLast('.')}" }
    }.joinToString(", ")

    companion object {
        private val KNOWN_STOCK_LAUNCHERS = listOf(
            "com.google.android.apps.tv.launcherx", // Google TV home
            "com.google.android.tvlauncher",        // Android TV home
            "com.google.android.leanbacklauncher",  // older Android TV home
        )
    }
}
