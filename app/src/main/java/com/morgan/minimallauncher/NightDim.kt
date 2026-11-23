package com.morgan.minimallauncher

import android.content.Context

/**
 * Night mode for the whole TV: a soft dark layer over everything on screen - the home screen,
 * the guide, films and other apps - drawn by the launcher's accessibility service.
 */
object NightDim {
    const val LEVEL = 0.45f

    /** How dark, 10-70 %. Kept below 70 so pictures stay watchable (darker just crushes the shadows). */
    fun level(c: Context): Int = Prefs.int(c, "night_level", 45).coerceIn(MIN, MAX)
    const val MIN = 10
    const val MAX = 70

    /** Darker / lighter by [delta] percent (turns it on if it was off). Returns the new level. */
    fun adjust(c: Context, delta: Int): Int {
        val n = (level(c) + delta).coerceIn(MIN, MAX)
        Prefs.setInt(c, "night_level", n)
        if (!isOn(c)) { Prefs.setBool(c, "night_mode", true); RoomLights.down(c) }
        apply(c)
        return n
    }

    fun isOn(c: Context) = Prefs.bool(c, "night_mode", false)

    fun set(c: Context, on: Boolean) {
        Prefs.setBool(c, "night_mode", on)
        if (on) RoomLights.down(c) else RoomLights.up(c)
        apply(c)
    }

    /** Show or hide the dark layer to match the setting. */
    fun apply(c: Context) {
        HomeRedirectService.instance?.setNightDim(isOn(c))
    }

    /** True when the whole-screen layer is available (so the home screen needn't dim itself too). */
    fun covering() = HomeRedirectService.instance != null
}
