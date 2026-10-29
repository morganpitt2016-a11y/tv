package com.morgan.minimallauncher

import android.content.Context

/**
 * Local, on-device "profiles" (like the Person 1 / Person 2 switcher on
 * Apple TV / Google TV home screens). Each profile just remembers its own
 * set of pinned apps for the dock - no accounts, no sign-in, pure
 * SharedPreferences.
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PINNED_PREFIX + profile, packages.joinToString(","))
            .apply()
    }
}
