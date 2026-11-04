package com.morgan.minimallauncher

import android.content.Context

/**
 * Night mode for the whole TV: a soft dark layer over everything on screen - the home screen,
 * the guide, films and other apps - drawn by the launcher's accessibility service.
 */
object NightDim {
    const val LEVEL = 0.45f

    fun isOn(c: Context) = Prefs.bool(c, "night_mode", false)

    fun set(c: Context, on: Boolean) {
        Prefs.setBool(c, "night_mode", on)
        apply(c)
    }

    /** Show or hide the dark layer to match the setting. */
    fun apply(c: Context) {
        HomeRedirectService.instance?.setNightDim(isOn(c))
    }

    /** True when the whole-screen layer is available (so the home screen needn't dim itself too). */
    fun covering() = HomeRedirectService.instance != null
}
