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
    data class Provider(val short: String, val technical: String, val name: String, val installed: String?, val icon: String? = null) {
        /** The TV app to install from the Play Store, if we know it. */
        val appPackage: String? get() = APPS[technical]?.firstOrNull()
        val displayName: String get() = when (technical) {
            "disneyplus" -> "Disney+"; "paramountplus" -> "Paramount+"; "amazonprime" -> "Prime Video"
            "appletvplus" -> "Apple TV+"; "nowtv" -> "NOW"; "all4" -> "Channel 4"; "my5" -> "5"; else -> name
        }
    }

    /** The big services, shown even when their app isn't installed yet. */
    val MAJOR = listOf("netflix", "disneyplus", "amazonprime", "paramountplus", "appletvplus", "nowtv", "bbc", "itv", "all4", "my5", "skygo")

    data class Detail(
        val runtime: Int, val age: String, val seasons: Int,
        val cast: List<String>, val directors: List<String>,
        val trailer: String?, val similar: List<Title>,
    )

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
        val json = cached(c, "packages2_${country()}", 7 * 24 * 3600_000L) {
            post("""query { packages(country: ${country()}, platform: WEB) { clearName technicalName shortName icon(profile: S100, format: PNG) } }""", JSONObject())
        }
        val arr = json.optJSONObject("data")?.optJSONArray("packages") ?: JSONArray()
        val launchable = AppListHelper.loadLaunchableTvApps(c).associate { it.packageName to it.label }
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val technical = it.optString("technicalName")
            Provider(it.optString("shortName"), technical, it.optString("clearName"),
                installedPackage(c, technical, it.optString("clearName"), launchable),
                it.optString("icon").takeIf { i -> i.isNotBlank() && i != "null" }?.let { i -> IMG + i })
        }
        providers = list
        return list
    }

    /** One provider per installed app (e.g. Netflix, not also "Netflix with Ads"). */
    fun installedProviders(c: Context): List<Provider> = providers(c).filter { it.installed != null }.distinctBy { it.installed }

    /** Installed services first, then the big ones you don't have yet. */
    fun featuredProviders(c: Context): List<Provider> {
        val all = providers(c)
        val mine = installedProviders(c)
        val more = MAJOR.mapNotNull { t -> all.firstOrNull { it.technical == t } }.filter { p -> mine.none { it.technical == p.technical || it.installed == p.installed && p.installed != null } }
        return mine + more
    }

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
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Title>>>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(6)

    fun titles(
        c: Context, sort: String = "POPULAR", packages: List<String> = emptyList(), types: List<String> = emptyList(),
        search: String? = null, first: Int = 30, genres: List<String> = emptyList(), done: (List<Title>?) -> Unit,
    ) {
        val app = c.applicationContext
        val filter = JSONObject()
        if (packages.isNotEmpty()) filter.put("packages", JSONArray(packages))
        if (types.isNotEmpty()) filter.put("objectTypes", JSONArray(types))
        if (!search.isNullOrBlank()) filter.put("searchQuery", search)
        if (genres.isNotEmpty()) filter.put("genres", JSONArray(genres))
        val vars = JSONObject().put("country", country()).put("language", "en").put("first", first)
            .put("filter", filter).put("sortBy", sort)
        val key = "t_" + (vars.toString().hashCode().toLong() and 0xffffffffL)
        // Already loaded this session: show it instantly.
        memory[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) { done(list); return } }
        pool.execute {
            val result = try {
                val json = cached(app, key, if (search != null) 30 * 60_000L else 6 * 3600_000L) { post(TITLE_QUERY, vars) }
                parse(app, json).also { memory[key] = System.currentTimeMillis() to it }
            } catch (e: Exception) { null }
            main.post { done(result) }
        }
    }

    private fun parse(c: Context, json: JSONObject): List<Title> {
        val byShort = providers(c).associateBy { it.short }
        val edges = json.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyList()
        return (0 until edges.length()).mapNotNull { i ->
            edges.getJSONObject(i).optJSONObject("node")?.let { parseNode(it, byShort) }
        }
    }

    private fun parseNode(node: JSONObject, byShort: Map<String, Provider>): Title? {
        return run {
            val content = node.optJSONObject("content") ?: return null
            val offersJson = node.optJSONArray("offers") ?: JSONArray()
            val offers = (0 until offersJson.length()).mapNotNull { j: Int ->
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
                val app = pkg ?: continue
                RecentAppsTracker.recordOpened(activity, app)
                rememberForApp(activity, app, title)
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
                    RecentAppsTracker.recordOpened(activity, pkg)
                    rememberForApp(activity, pkg, title)
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

    fun remember(c: Context, t: Title) {
        val arr = try { JSONArray(Prefs.string(c, historyKey(c)) ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(toJson(t))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != t.id && out.length() < 20) out.put(o)
        }
        Prefs.setString(c, historyKey(c), out.toString())
    }

    fun history(c: Context): List<Title> = readTitles(c, historyKey(c))

    /** Remember which title was opened in which app (for the home screen's Continue Watching). */
    private fun rememberForApp(c: Context, pkg: String, t: Title) {
        Prefs.setString(c, "last_title_${ProfileManager.getActiveProfile(c)}_$pkg",
            toJson(t).put("at", System.currentTimeMillis()).toString())
    }

    /** The last title opened in [pkg] from Watch, and when. No network needed. */
    fun lastForApp(c: Context, pkg: String): Pair<Title, Long>? = try {
        Prefs.string(c, "last_title_${ProfileManager.getActiveProfile(c)}_$pkg")?.let { s ->
            val o = JSONObject(s)
            fromJson(o, emptyMap()) to o.optLong("at")
        }
    } catch (e: Exception) { null }

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

    // ---------- Title details ----------

    private const val TITLE_FIELDS = """id objectType
        content(country: %C, language: "en") { title originalReleaseYear posterUrl shortDescription backdrops { backdropUrl } scoring { imdbScore } genres { shortName } }
        offers(country: %C, platform: WEB) { monetizationType standardWebURL package { shortName technicalName clearName } }"""

    /** Cast, seasons, trailer and "More Like This" for one title. Main-thread callback; null on failure. */
    fun detail(c: Context, t: Title, done: (Detail?) -> Unit) {
        val app = c.applicationContext
        Thread {
            val result = try {
                val cc = country()
                val fields = TITLE_FIELDS.replace("%C", cc)
                val extra = """content(country: $cc, language: "en") { runtime ageCertification credits { name role } clips { externalId provider } }
                    similarTitlesV2(country: $cc, first: 16) { edges { node { ... on Movie { $fields } ... on Show { $fields } } } }"""
                val q = """query { node(id: ${JSONObject.quote(t.id)}) {
                    ... on Movie { $extra }
                    ... on Show { totalSeasonCount $extra }
                } }"""
                val json = cached(app, "d_" + t.id, 24 * 3600_000L) { post(q, JSONObject()) }
                val node = json.optJSONObject("data")?.optJSONObject("node") ?: throw java.io.IOException("no node")
                val content = node.optJSONObject("content") ?: JSONObject()
                val credits = content.optJSONArray("credits") ?: JSONArray()
                val people = (0 until credits.length()).map { credits.getJSONObject(it) }
                val clips = content.optJSONArray("clips") ?: JSONArray()
                val trailer = (0 until clips.length()).map { clips.getJSONObject(it) }
                    .firstOrNull { it.optString("provider").equals("YOUTUBE", true) }?.optString("externalId")
                val byShort = providers(app).associateBy { it.short }
                val sim = node.optJSONObject("similarTitlesV2")?.optJSONArray("edges") ?: JSONArray()
                Detail(
                    runtime = content.optInt("runtime"),
                    age = content.optString("ageCertification").takeIf { it != "null" }.orEmpty(),
                    seasons = node.optInt("totalSeasonCount"),
                    cast = people.filter { it.optString("role") == "ACTOR" }.map { it.optString("name") }.distinct().take(5),
                    directors = people.filter { it.optString("role") == "DIRECTOR" }.map { it.optString("name") }.distinct().take(2),
                    trailer = trailer?.takeIf { it.isNotBlank() },
                    similar = (0 until sim.length()).mapNotNull { sim.getJSONObject(it).optJSONObject("node")?.let { n -> parseNode(n, byShort) } },
                )
            } catch (e: Exception) { null }
            main.post { done(result) }
        }.start()
    }

    // ---------- My List (per profile) ----------

    private fun listKey(c: Context) = "watch_list_" + ProfileManager.getActiveProfile(c)

    fun myList(c: Context): List<Title> = readTitles(c, listKey(c))

    fun inMyList(c: Context, t: Title) = myList(c).any { it.id == t.id }

    /** Adds or removes; returns true if it's now in the list. */
    fun toggleMyList(c: Context, t: Title): Boolean {
        val list = myList(c).toMutableList()
        val added = list.removeAll { it.id == t.id }.not()
        if (added) list.add(0, t)
        Prefs.setString(c, listKey(c), JSONArray().apply { list.take(60).forEach { put(toJson(it)) } }.toString())
        return added
    }

    private fun readTitles(c: Context, key: String): List<Title> = try {
        val byShort = providers(c).associateBy { it.short }
        val arr = JSONArray(Prefs.string(c, key) ?: "[]")
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it), byShort) }
    } catch (e: Exception) { emptyList() }

    // ---------- Apps ----------

    /** Opens the Play Store page for a service's TV app. */
    fun getApp(activity: android.app.Activity, p: Provider) {
        val pkg = p.appPackage
        val tries = listOfNotNull(
            pkg?.let { Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")) },
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(p.displayName))),
        )
        for (i in tries) {
            try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        }
        GlassUi.toast(activity, "Install ${p.displayName} from the Google Play Store")
    }

    /** Plays a YouTube trailer in the YouTube app. */
    fun playTrailer(activity: android.app.Activity, youtubeId: String) {
        val uri = Uri.parse("https://www.youtube.com/watch?v=$youtubeId")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube.tv"),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (i in tries) {
            try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        }
        GlassUi.toast(activity, "YouTube isn’t installed")
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
