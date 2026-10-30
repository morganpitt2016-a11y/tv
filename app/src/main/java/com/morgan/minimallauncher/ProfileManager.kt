package com.morgan.minimallauncher

import android.content.Context

/**
 * Local, on-device profiles (like the Person 1 / Person 2 switcher on
 * Apple TV). Each profile has its own dock apps and order, favourites,
 * wallpaper, watch history and recommendations - no accounts, no
 * sign-in, all stored on the TV.
 */
object ProfileManager {
    private const val PREFS = "launcher_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_PINNED_PREFIX = "pinned_"
    private val DEFAULT_PROFILES = listOf("Person 1", "Person 2")

    fun getProfiles(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null) ?: return DEFAULT_PROFILES
        val list = raw.split(",").filter { it.isNotBlank() }
        return list.ifEmpty { DEFAULT_PROFILES }
    }

    fun addProfile(context: Context, name: String) {
        val profiles = getProfiles(context).toMutableList()
        if (!profiles.contains(name)) {
            profiles.add(name)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILES, profiles.joinToString(","))
                .apply()
        }
    }

    fun removeProfile(context: Context, name: String) {
        val profiles = getProfiles(context).toMutableList()
        profiles.remove(name)
        if (profiles.isEmpty()) profiles.add(DEFAULT_PROFILES.first())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROFILES, profiles.joinToString(","))
            .remove(KEY_PINNED_PREFIX + name)
            .apply()
        if (getActiveProfile(context) == name) setActiveProfile(context, profiles[0])
    }

    fun getActiveProfile(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getString(KEY_ACTIVE, null)
        val profiles = getProfiles(context)
        return if (active != null && profiles.contains(active)) active else profiles.first()
    }

    fun setActiveProfile(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE, name)
            .apply()
    }

    fun getPinnedApps(context: Context, profile: String = getActiveProfile(context)): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PINNED_PREFIX + profile, "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setPinnedApps(context: Context, profile: String, packages: Set<String>) {
        // Keep the existing order; newly added apps go on the end.
        val order = getDockOrder(context, profile).filter { it in packages } + packages.filter { it !in getDockOrder(context, profile) }
        setDockOrder(context, profile, order)
    }

    /** The dock apps in the order this profile arranged them. */
    fun getDockOrder(context: Context, profile: String = getActiveProfile(context)): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getString(KEY_PINNED_PREFIX + profile, "") ?: "").split(",").filter { it.isNotBlank() }.distinct()
    }

    fun setDockOrder(context: Context, profile: String, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PINNED_PREFIX + profile, packages.distinct().joinToString(","))
            .apply()
    }

    /** Moves an app left (-1) or right (+1) in the dock. [shown] is the dock as currently displayed. */
    fun moveInDock(context: Context, pkg: String, delta: Int, shown: List<String>) {
        val profile = getActiveProfile(context)
        val order = getDockOrder(context, profile).ifEmpty { shown }.toMutableList()
        val i = order.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, order.size - 1)
        order.removeAt(i)
        order.add(j, pkg)
        setDockOrder(context, profile, order)
    }

    fun removeFromDock(context: Context, pkg: String, shown: List<String>) {
        val profile = getActiveProfile(context)
        setDockOrder(context, profile, getDockOrder(context, profile).ifEmpty { shown }.filter { it != pkg })
    }

    fun addToDock(context: Context, pkg: String, shown: List<String>) {
        val profile = getActiveProfile(context)
        val order = getDockOrder(context, profile).ifEmpty { shown }
        if (pkg !in order) setDockOrder(context, profile, order + pkg)
    }

    // ---------- Favourites ----------

    fun getFavourites(context: Context, profile: String = getActiveProfile(context)): List<String> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fav_$profile", "") ?: "")
            .split(",").filter { it.isNotBlank() }

    fun isFavourite(context: Context, pkg: String) = pkg in getFavourites(context)

    fun toggleFavourite(context: Context, pkg: String): Boolean {
        val profile = getActiveProfile(context)
        val favs = getFavourites(context, profile).toMutableList()
        val nowFav = if (favs.remove(pkg)) false else { favs.add(0, pkg); true }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fav_$profile", favs.joinToString(",")).apply()
        return nowFav
    }

    // ---------- Wallpaper ----------

    /** "all", "cat:<id>", "photo:<n>" or "album" - see WallpaperLibrary. */
    fun getWallpaper(context: Context, profile: String = getActiveProfile(context)): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("wall_$profile", null)
            ?: if (getProfiles(context).indexOf(profile) == 1) "cat:lakes" else "all"

    fun setWallpaper(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("wall_${getActiveProfile(context)}", value).apply()
    }
}
