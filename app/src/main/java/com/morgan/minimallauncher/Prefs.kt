package com.morgan.minimallauncher

import android.content.Context

/** Launcher-wide settings (per-profile ones live in ProfileManager). */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    fun bool(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    fun setBool(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()
    fun string(c: Context, key: String, def: String? = null) = p(c).getString(key, def)
    fun setString(c: Context, key: String, v: String?) = p(c).edit().putString(key, v).apply()
    fun int(c: Context, key: String, def: Int) = p(c).getInt(key, def)
    fun setInt(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()

    // Widgets
    const val W_WEATHER = "w_weather"
    const val W_CALENDAR = "w_calendar"
    const val W_MUSIC = "w_music"
    const val W_HOME = "w_home"
    const val W_RECENT = "w_recent"
    fun widget(c: Context, key: String) = bool(c, key, true)

    // Feel
    fun sounds(c: Context) = bool(c, "sounds", true)
    fun ambientLight(c: Context) = bool(c, "ambient_light", true)

    /** Minutes of no remote presses before the screen starts dimming (0 = never). */
    fun dimAfter(c: Context) = int(c, "dim_after", 2)
    /** Minutes before the idle screen takes over (0 = never). */
    fun idleAfter(c: Context) = int(c, "idle_after", 10)
    /** What the idle screen shows: ambient, aerial or sleep. */
    fun idleMode(c: Context) = string(c, "idle_mode", AmbientActivity.AMBIENT)!!
    /** Hours the ambient/aerial screen stays on before fading to the sleep clock (0 = no limit). */
    fun ambientHours(c: Context) = int(c, "ambient_hours", 2)

    // Ambient / Aerial
    /** builtin, aerial, art or album */
    fun ambientSource(c: Context) = string(c, "ambient_source", "aerial")!!
    fun aerialCategory(c: Context) = string(c, "aerial_category", "mountains")!!
    fun showLocation(c: Context) = bool(c, "show_location", true)
    fun visualiser(c: Context) = bool(c, "visualiser", true)

    // Cinema
    fun cinemaApp(c: Context) = string(c, "cinema_app")
    fun cinemaLights(c: Context) = bool(c, "cinema_lights", false)
    fun cinemaDnd(c: Context) = bool(c, "cinema_dnd", true)
    fun cinemaOn(c: Context) = bool(c, "cinema_on", false)
}
