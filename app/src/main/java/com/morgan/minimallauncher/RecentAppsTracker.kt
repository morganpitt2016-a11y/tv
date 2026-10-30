package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Each profile's watch history, kept on the TV: which apps were opened
 * from this launcher and when (powers Continue Watching), plus how often
 * each app is opened at each hour of the day (powers Recommended).
 *
 * It can't show episode titles or progress: Android doesn't let
 * third-party launchers read another app's playback state.
 */
object RecentAppsTracker {
    private const val PREFS = "recent_apps"
    private const val MAX_ENTRIES = 12

    data class Recent(val app: AppEntry, val openedAt: Long)

    private fun orderKey(c: Context) = "order_" + ProfileManager.getActiveProfile(c)
    private fun usageKey(c: Context) = "usage_" + ProfileManager.getActiveProfile(c)

    fun recordOpened(context: Context, packageName: String) {
        val entries = load(context).filter { it.first != packageName }.toMutableList()
        entries.add(0, packageName to System.currentTimeMillis())
        save(context, entries.take(MAX_ENTRIES))
        bumpUsage(context, packageName)
    }

    fun getRecent(context: Context, allApps: List<AppEntry>, limit: Int): List<Recent> {
        val byPackage = allApps.associateBy { it.packageName }
        return load(context)
            .mapNotNull { (pkg, time) -> byPackage[pkg]?.let { Recent(it, time) } }
            .take(limit)
    }

    /**
     * Apps this person tends to open around this time of day, then their
     * most-used apps overall, then apps they haven't tried yet.
     */
    fun recommended(context: Context, allApps: List<AppEntry>, exclude: Set<String>, limit: Int): List<AppEntry> {
        val usage = usage(context)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        fun score(pkg: String): Double {
            val counts = usage[pkg] ?: return 0.0
            val near = (-1..1).sumOf { counts[(hour + it + 24) % 24] }
            return near * 3.0 + counts.sum()
        }
        val candidates = allApps.filter { it.packageName !in exclude }
        val used = candidates.filter { score(it.packageName) > 0 }.sortedByDescending { score(it.packageName) }
        val fresh = candidates.filter { score(it.packageName) == 0.0 }.shuffled(java.util.Random(hour.toLong()))
        return (used + fresh).take(limit)
    }

    private fun bumpUsage(context: Context, pkg: String) {
        val all = usage(context).toMutableMap()
        val counts = all[pkg] ?: IntArray(24)
        counts[Calendar.getInstance().get(Calendar.HOUR_OF_DAY)]++
        all[pkg] = counts
        val json = JSONObject()
        for ((k, v) in all) json.put(k, JSONArray(v.toList()))
        prefs(context).edit().putString(usageKey(context), json.toString()).apply()
    }

    private fun usage(context: Context): Map<String, IntArray> = try {
        val json = JSONObject(prefs(context).getString(usageKey(context), "{}") ?: "{}")
        json.keys().asSequence().associateWith { k ->
            val a = json.getJSONArray(k)
            IntArray(24) { a.optInt(it) }
        }
    } catch (e: Exception) { emptyMap() }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Entries are stored as "package@millis", newest first. */
    private fun load(context: Context): List<Pair<String, Long>> {
        val p = prefs(context)
        // Older builds kept one shared history; the first profile inherits it.
        val raw = p.getString(orderKey(context), null)
            ?: if (ProfileManager.getProfiles(context).first() == ProfileManager.getActiveProfile(context)) p.getString("order", "") else ""
        return (raw ?: "").split(",")
            .filter { it.isNotBlank() }
            .map { r ->
                val at = r.lastIndexOf('@')
                if (at < 0) r to 0L else r.substring(0, at) to (r.substring(at + 1).toLongOrNull() ?: 0L)
            }
    }

    private fun save(context: Context, entries: List<Pair<String, Long>>) {
        prefs(context).edit()
            .putString(orderKey(context), entries.joinToString(",") { "${it.first}@${it.second}" })
            .apply()
    }
}
