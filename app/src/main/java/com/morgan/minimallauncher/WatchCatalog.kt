package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Films and shows across all your streaming apps, from JustWatch's
 * public catalogue: what's popular or trending, which service has each
 * title in your country, and a direct link that opens it in that app.
 * (Netflix and the others don't let other apps read your own watch
 * history, so "Continue Watching" here is titles you opened from this app.)
 */
object WatchCatalog {

    data class Offer(val provider: Provider, val url: String, val kind: String)

    data class Title(
        val id: String,
        val type: String,          // MOVIE or SHOW
        val name: String,
        val year: Int,
        val poster: String?,
        val backdrop: String?,
        val description: String,
        val imdb: Double?,
        val genres: List<String>,
        val offers: List<Offer>,
    ) {
        val bestOffer: Offer? get() = offers.firstOrNull { it.provider.installed != null }
    }

    /** A streaming service, and the TV app that plays it (if installed). */
    data class Provider(val short: String, val technical: String, val name: String, val installed: String?)

    private const val API = "https://apis.justwatch.com/graphql"
    private const val IMG = "https://images.justwatch.com"
    private val main = Handler(Looper.getMainLooper())

    fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "GB"

    fun posterUrl(path: String?) = path?.replace("{profile}", "s332")?.replace("{format}", "webp")?.let { IMG + it }
    fun backdropUrl(path: String?) = path?.replace("{profile}", "s1440")?.replace("{format}", "webp")?.let { IMG + it }

    // ---------- Android apps for each service ----------

    /** JustWatch technical name -> TV app package names (first installed wins). */
    private val APPS = mapOf(
        "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "netflixbasicwithads" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "amazonprime" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "amazonprimevideowithads" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "amazon" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "disneyplus" to listOf("com.disney.disneyplus"),
        "appletvplus" to listOf("com.apple.atve.androidtv.appletv"),
        "itunes" to listOf("com.apple.atve.androidtv.appletv"),
        "bbc" to listOf("uk.co.bbc.iplayer"),
        "itv" to listOf("air.ITVMobilePlayer", "com.itv.loki"),
        "all4" to listOf("com.channel4.ondemand"),
        "my5" to listOf("com.channel5.my5", "com.channel5.androidtv"),
        "skygo" to listOf("com.bskyb.skygo"),
        "nowtv" to listOf("com.nowtv.player", "com.nowtv.player.androidtv"),
        "paramountplus" to listOf("com.cbs.ott", "com.cbs.app", "com.cbs.ca"),
        "crunchyroll" to listOf("com.crunchyroll.crunchyroid"),
        "tubitv" to listOf("com.tubitv"),
        "plex" to listOf("com.plexapp.android"),
        "youtubered" to listOf("com.google.android.youtube.tv"),
        "youtubefree" to listOf("com.google.android.youtube.tv"),
        "mubi" to listOf("com.mubi"),
        "rakutentv" to listOf("tv.wuaki"), "wuaki" to listOf("tv.wuaki"),
        "hayu" to listOf("com.hayu.android"),
        "uktvplay" to listOf("uk.co.uktv.dave"),
        "stvplayer" to listOf("com.stv.android.player"),
        "britbox" to listOf("com.britbox.uk"),
    )

    /** Live-TV / catch-up apps for the Live TV row, if installed. */
    val LIVE_APPS = listOf(
        "ar.tvplayer.tv",            // TiviMate
        "uk.co.bbc.iplayer", "air.ITVMobilePlayer", "com.itv.loki", "com.channel4.ondemand",
        "com.channel5.my5", "com.nowtv.player", "com.bskyb.skygo", "com.google.android.youtube.tv",
        "com.google.android.youtube.tvunplugged", "com.plexapp.android",
    )

    private fun installedPackage(c: Context, technical: String, name: String, launchable: Map<String, String>): String? {
        APPS[technical]?.firstOrNull { it in launchable }?.let { return it }
        // Fall back to a matching app name (e.g. "BBC iPlayer").
        val n = name.lowercase()
        if (n.length < 4) return null
        return launchable.entries.firstOrNull { (_, label) -> label.lowercase() == n }?.key
    }

    // ---------- Providers ----------

    @Volatile private var providers: List<Provider>? = null

    /** Services available in this country, with the installed TV app for each (blocking). */
    fun providers(c: Context): List<Provider> {
        providers?.let { return it }
        val json = cached(c, "packages_${country()}", 7 * 24 * 3600_000L) {
            post("""query { packages(country: ${country()}, platform: WEB) { clearName technicalName shortName } }""", JSONObject())
        }
        val arr = json.optJSONObject("data")?.optJSONArray("packages") ?: JSONArray()
        val launchable = AppListHelper.loadLaunchableTvApps(c).associate { it.packageName to it.label }
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val technical = it.optString("technicalName")
            Provider(it.optString("shortName"), technical, it.optString("clearName"),
                installedPackage(c, technical, it.optString("clearName"), launchable))
        }
        providers = list
        return list
    }

    /** One provider per installed app (e.g. Netflix, not also "Netflix with Ads"). */
    fun installedProviders(c: Context): List<Provider> = providers(c).filter { it.installed != null }.distinctBy { it.installed }

    // ---------- Titles ----------

    private val TITLE_QUERY = """
        query T(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}filter: TitleFilter, ${'$'}sortBy: PopularTitlesSorting!) {
          popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter, sortBy: ${'$'}sortBy) {
            edges { node { id objectType
              content(country: ${'$'}country, language: ${'$'}language) {
                title originalReleaseYear posterUrl shortDescription backdrops { backdropUrl } scoring { imdbScore } genres { shortName }
              }
              offers(country: ${'$'}country, platform: WEB) { monetizationType standardWebURL package { shortName technicalName clearName } }
            } }
          }
        }
    """.trimIndent()

    /**
     * Popular or trending titles. [packages]: JustWatch short names to limit to;
     * [types]: MOVIE / SHOW; [search]: free text. Calls back on the main thread.
     */
    fun titles(
        c: Context, sort: String = "POPULAR", packages: List<String> = emptyList(), types: List<String> = emptyList(),
        search: String? = null, first: Int = 30, done: (List<Title>?) -> Unit,
    ) {
        val app = c.applicationContext
        Thread {
            val result = try {
                val filter = JSONObject()
                if (packages.isNotEmpty()) filter.put("packages", JSONArray(packages))
                if (types.isNotEmpty()) filter.put("objectTypes", JSONArray(types))
                if (!search.isNullOrBlank()) filter.put("searchQuery", search)
                val vars = JSONObject().put("country", country()).put("language", "en").put("first", first)
                    .put("filter", filter).put("sortBy", sort)
                val key = "t_" + (vars.toString().hashCode().toLong() and 0xffffffffL)
                val json = cached(app, key, if (search != null) 30 * 60_000L else 6 * 3600_000L) { post(TITLE_QUERY, vars) }
                parse(app, json)
            } catch (e: Exception) { null }
            main.post { done(result) }
        }.start()
    }

    private fun parse(c: Context, json: JSONObject): List<Title> {
        val byShort = providers(c).associateBy { it.short }
        val edges = json.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyList()
        return (0 until edges.length()).mapNotNull { i ->
            val node = edges.getJSONObject(i).optJSONObject("node") ?: return@mapNotNull null
            val content = node.optJSONObject("content") ?: return@mapNotNull null
            val offersJson = node.optJSONArray("offers") ?: JSONArray()
            val offers = (0 until offersJson.length()).mapNotNull { j ->
                val o = offersJson.getJSONObject(j)
                val pkg = o.optJSONObject("package") ?: return@mapNotNull null
                val provider = byShort[pkg.optString("shortName")]
                    ?: Provider(pkg.optString("shortName"), pkg.optString("technicalName"), pkg.optString("clearName"), null)
                Offer(provider, o.optString("standardWebURL"), o.optString("monetizationType"))
            }
                // Included-in-subscription first, then free, then rent/buy; one per app.
                .sortedBy { when (it.kind) { "FLATRATE" -> 0; "FREE", "ADS" -> 1; else -> 2 } }
                .distinctBy { it.provider.installed ?: it.provider.short }
            val backdrops = content.optJSONArray("backdrops")
            Title(
                id = node.optString("id"),
                type = node.optString("objectType"),
                name = content.optString("title"),
                year = content.optInt("originalReleaseYear"),
                poster = posterUrl(content.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" }),
                backdrop = backdropUrl(backdrops?.optJSONObject(0)?.optString("backdropUrl")),
                description = content.optString("shortDescription").takeIf { it != "null" }.orEmpty(),
                imdb = content.optJSONObject("scoring")?.optDouble("imdbScore")?.takeIf { !it.isNaN() && it > 0 },
                genres = content.optJSONArray("genres")?.let { g -> (0 until g.length()).map { genreName(g.getJSONObject(it).optString("shortName")) } }.orEmpty(),
                offers = offers,
            )
        }
    }

    private fun genreName(s: String) = when (s) {
        "act" -> "Action"; "ani" -> "Animation"; "cmy" -> "Comedy"; "crm" -> "Crime"; "doc" -> "Documentary"
        "drm" -> "Drama"; "eur" -> "European"; "fml" -> "Family"; "fnt" -> "Fantasy"; "hrr" -> "Horror"
        "hst" -> "History"; "msc" -> "Music"; "rly" -> "Reality"; "rma" -> "Romance"; "scf" -> "Sci-Fi"
        "spt" -> "Sport"; "trl" -> "Thriller"; "war" -> "War"; "wsn" -> "Western"
        else -> s.replaceFirstChar { it.uppercase() }
    }

    // ---------- Opening a title in its app ----------

    /** Opens the title in the app; falls back to the app itself, then Google TV search. */
    fun open(activity: android.app.Activity, title: Title, offer: Offer) {
        val pkg = offer.provider.installed
        val tries = mutableListOf<Intent>()
        if (pkg != null && offer.url.isNotBlank()) {
            tries += Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)).setPackage(pkg).putExtra("source", "30")
            // Netflix's TV app also understands /watch/ links.
            if (offer.url.contains("netflix.com/title/")) {
                tries += Intent(Intent.ACTION_VIEW, Uri.parse(offer.url.replace("/title/", "/watch/"))).setPackage(pkg).putExtra("source", "30")
            }
        }
        for (i in tries) {
            try {
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                remember(activity, title)
                return
            } catch (e: Exception) {}
        }
        // The app couldn't take the link: open it and let them search.
        if (pkg != null) {
            val launch = activity.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: activity.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                try {
                    activity.startActivity(launch)
                    GlassUi.toast(activity, "Search for “${title.name}” in ${offer.provider.name}")
                    remember(activity, title)
                    return
                } catch (e: Exception) {}
            }
        }
        searchGoogleTv(activity, title.name)
    }

    fun searchGoogleTv(activity: android.app.Activity, query: String) {
        val tries = listOf(
            Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, query),
            Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, query),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query),
        )
        for (i in tries) {
            try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        }
        GlassUi.toast(activity, "Search isn’t available on this TV")
    }

    // ---------- Continue watching (titles opened from here, per profile) ----------

    private fun historyKey(c: Context) = "watch_history_" + ProfileManager.getActiveProfile(c)

    private fun remember(c: Context, t: Title) {
        val arr = try { JSONArray(Prefs.string(c, historyKey(c)) ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(toJson(t))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != t.id && out.length() < 20) out.put(o)
        }
        Prefs.setString(c, historyKey(c), out.toString())
    }

    fun history(c: Context): List<Title> = try {
        val byShort = providers(c).associateBy { it.short }
        val arr = JSONArray(Prefs.string(c, historyKey(c)) ?: "[]")
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it), byShort) }
    } catch (e: Exception) { emptyList() }

    private fun toJson(t: Title) = JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("year", t.year)
        .put("poster", t.poster).put("backdrop", t.backdrop).put("description", t.description).put("imdb", t.imdb ?: 0.0)
        .put("genres", JSONArray(t.genres))
        .put("offers", JSONArray().apply { t.offers.forEach { put(JSONObject().put("p", it.provider.short).put("u", it.url).put("k", it.kind)) } })

    private fun fromJson(o: JSONObject, byShort: Map<String, Provider>): Title {
        val offers = o.optJSONArray("offers") ?: JSONArray()
        return Title(o.optString("id"), o.optString("type"), o.optString("name"), o.optInt("year"),
            o.optString("poster").takeIf { it.isNotBlank() && it != "null" }, o.optString("backdrop").takeIf { it.isNotBlank() && it != "null" },
            o.optString("description"), o.optDouble("imdb").takeIf { it > 0 },
            o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
            (0 until offers.length()).mapNotNull { i ->
                val x = offers.getJSONObject(i)
                byShort[x.optString("p")]?.let { Offer(it, x.optString("u"), x.optString("k")) }
            })
    }

    // ---------- HTTP + cache ----------

    private fun post(query: String, variables: JSONObject): JSONObject {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", Net.USER_AGENT)
            conn.outputStream.use { it.write(JSONObject().put("query", query).put("variables", variables).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun cached(c: Context, key: String, maxAge: Long, fetch: () -> JSONObject): JSONObject {
        val f = File(File(c.cacheDir, "watch").apply { mkdirs() }, "$key.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge) {
            try { return JSONObject(f.readText()) } catch (e: Exception) {}
        }
        return try {
            fetch().also { f.writeText(it.toString()) }
        } catch (e: Exception) {
            // Offline: use an older copy if there is one.
            if (f.exists()) JSONObject(f.readText()) else throw e
        }
    }
}
