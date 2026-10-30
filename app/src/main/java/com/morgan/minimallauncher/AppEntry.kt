package com.morgan.minimallauncher

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val launchIntent: Intent?
)
