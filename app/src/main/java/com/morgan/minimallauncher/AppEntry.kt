package com.morgan.minimallauncher

import android.content.Intent
import android.graphics.drawable.Drawable

/** A launchable TV app. The icon is read the first time something draws it (loading every icon up front made the home screen wait). */
class AppEntry(
    val label: String,
    val packageName: String,
    iconLoader: () -> Drawable,
    val launchIntent: Intent?,
) {
    val icon: Drawable by lazy(iconLoader)
    override fun equals(other: Any?) = other is AppEntry && other.packageName == packageName
    override fun hashCode() = packageName.hashCode()
    override fun toString() = "AppEntry($label, $packageName)"
}
