package com.morgan.minimallauncher

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Your TV provider's own line-up, through its Xtream Codes login (the same login
 * TiviMate uses). The login is typed on the TV and kept only on the TV.
 * Lists are cached on the TV for half a day; nothing is sent anywhere but the provider.
 */
object Xtream {

    data class Live(val id: Int, val num: Int, val name: String, val icon: String?, val category: String)
    data class Vod(val id: Int, val name: String, val ext: String, val icon: String?, val category: String)
    data class Series(val id: Int, val name: String, val cover: String?, val category: String)
    data class Episode(val id: String, val season: Int, val number: Int, val title: String, val ext: String,
                       val image: String? = null, val plot: String = "", val minutes: Int = 0)

    // ---------- Login ----------

    fun server(c: Context) = Prefs.string(c, "xt_server")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }
    private fun user(c: Context) = Prefs.string(c, "xt_user")?.trim().orEmpty()
    private fun pass(c: Context) = Prefs.string(c, "xt_pass")?.trim().orEmpty()

    fun isSetUp(c: Context) = server(c) != null && user(c).isNotEmpty() && pass(c).isNotEmpty()

    fun save(c: Context, server: String, user: String, pass: String) {
        Prefs.setString(c, "xt_server", server.trim())
        Prefs.setString(c, "xt_user", user.trim())
        Prefs.setString(c, "xt_pass", pass.trim())
        clearCache(c)
    }

    /**
     * Many providers use the same server, username and password in the guide (XMLTV) link as for streaming.
     * With the owner's say-so, try signing in with the ones in the saved guide link, entirely on this TV.
     * Returns a status line (never the details themselves), or throws with the reason.
     */
    fun signInFromGuideLink(c: Context): String {
        val link = LiveGuide.customUrl(c) ?: throw Exception("No guide link is saved on this TV")
        val uri = android.net.Uri.parse(link)
        val user = uri.getQueryParameter("username") ?: throw Exception("The guide link has no username in it")
        val pass = uri.getQueryParameter("password") ?: throw Exception("The guide link has no password in it")
        val host = uri.host ?: throw Exception("The guide link has no server in it")
        val server = (uri.scheme ?: "http") + "://" + host + (if (uri.port > 0) ":${uri.port}" else "")
        val before = Triple(Prefs.string(c, "xt_server"), Prefs.string(c, "xt_user"), Prefs.string(c, "xt_pass"))
        save(c, server, user, pass)
        return try { check(c) } catch (e: Exception) {
            Prefs.setString(c, "xt_server", before.first); Prefs.setString(c, "xt_user", before.second); Prefs.setString(c, "xt_pass", before.third)
            clearCache(c)
            throw e
        }
    }

    /** A quiet reminder when the subscription is about to run out (at most once a day). */
    fun expiryNotice(activity: android.app.Activity) {
        if (!isSetUp(activity)) return
        val app = activity.applicationContext
        val today = (System.currentTimeMillis() / 86_400_000L).toString()
        // Ask the provider again (it may have been renewed) - at most every 15 minutes; the reminder itself shows once a day.
        val now = System.currentTimeMillis()
        if (now - lastStatusCheck < 15 * 60_000L) return
        lastStatusCheck = now
        Thread {
            try { check(app) } catch (e: Exception) { return@Thread }
            if (Prefs.string(app, "xt_notice_day") == today) return@Thread
            val exp = Prefs.string(app, "xt_exp")?.toLongOrNull() ?: return@Thread
            val days = ((exp - System.currentTimeMillis()) / 86_400_000L).toInt()
            if (days <= 7) {
                Prefs.setString(app, "xt_notice_day", today)
                activity.runOnUiThread {
                    if (!activity.isDestroyed) GlassUi.toast(activity, when {
                        days < 0 -> "Your Dodgy Box subscription has ended - ask your seller to renew it"
                        days == 0 -> "Your Dodgy Box subscription ends today"
                        days == 1 -> "Your Dodgy Box subscription ends tomorrow"
                        else -> "Your Dodgy Box subscription ends in $days days"
                    })
                }
            }
        }.start()
    }

    // ---------- Subscription ended ----------

    @Volatile private var lastStatusCheck = 0L

    /** Shown as expired? Ask the provider now (renewals show straight away), then [done] on the main thread with the answer. */
    fun recheckIfExpired(c: Context, done: (stillExpired: Boolean) -> Unit) {
        if (!isExpired(c)) return
        val app = c.applicationContext
        Thread {
            try { check(app) } catch (e: Exception) {}
            val still = isExpired(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(still) }
        }.start()
    }

    /** True once the subscription has run out (by its end date, or the provider saying so). */
    fun isExpired(c: Context): Boolean {
        if (!isSetUp(c)) return false
        if (Prefs.string(c, "xt_status")?.equals("Expired", true) == true) return true
        val exp = Prefs.string(c, "xt_exp")?.toLongOrNull() ?: return false
        return exp in 1 until System.currentTimeMillis()
    }

    fun expiredOn(c: Context): String? = Prefs.string(c, "xt_exp")?.toLongOrNull()?.let {
        java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(java.util.Date(it))
    }

    /** The message shown instead of playing. "Check again" asks the provider, in case it's been renewed. */
    fun showExpired(activity: android.app.Activity, then: (() -> Unit)? = null) {
        val app = activity.applicationContext
        val on = expiredOn(app)?.let { " on $it" }.orEmpty()
        GlassDialog(activity).title("Dodgy Box Subscription Expired")
            .message("The Dodgy Box subscription ran out$on, so films, shows and live channels from Dodgy Box can’t play.\n\nRenew it with your seller - it works again straight away.")
            .button("OK", GlassDialog.Style.BOLD)
            .button("Check Again") {
                GlassUi.toast(activity, "Checking with Dodgy Box…")
                Thread {
                    val ok = try { check(app); !isExpired(app) } catch (e: Exception) { false }
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        if (ok) { GlassUi.toast(activity, "Renewed - Dodgy Box is back"); then?.invoke() }
                        else GlassUi.toast(activity, "Still expired - renew it with your seller")
                    }
                }.start()
            }.show()
    }

    fun signOut(c: Context) {
        listOf("xt_server", "xt_user", "xt_pass").forEach { Prefs.setString(c, it, null) }
        clearCache(c)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Being gentle with the provider ----------
    // Too many requests at once and the provider shuts the door for a while (it answers "HTTP 466").
    // So: at most two at a time, a short gap between them, and a pause if it complains.
    private val gate = java.util.concurrent.Semaphore(2, true)
    private val spacing = Object()
    @Volatile private var lastAt = 0L
    @Volatile private var coolUntil = 0L

    private fun busy(e: Exception) = e.message.orEmpty().let { it.contains("HTTP 466") || it.contains("HTTP 429") || it.contains("HTTP 503") }

    fun <T> gated(block: () -> T): T {
        var attempt = 0
        while (true) {
            val wait = coolUntil - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(minOf(wait, 8_000L))
            gate.acquire()
            try {
                synchronized(spacing) {
                    val gap = lastAt + 250 - System.currentTimeMillis()
                    if (gap > 0) Thread.sleep(gap)
                    lastAt = System.currentTimeMillis()
                }
                return block()
            } catch (e: Exception) {
                if (!busy(e) || attempt >= 2) throw e
                attempt++
                coolUntil = System.currentTimeMillis() + 3_000L * attempt
            } finally { gate.release() }
        }
    }

    /** Some providers turn away anything that doesn't look like a video player, so ask as one. */
    val PLAYER_AGENT = "MinimalLauncher/1.0 (Linux;Android ${android.os.Build.VERSION.RELEASE}) AndroidXMedia3/1.3.1"

    private fun apiGet(c: Context, action: String? = null, extra: String = "", timeoutMs: Int = 15_000): String = gated {
        val vpn = vpnOn(c)
        try {
            Net.get(api(c, action, extra), mapOf("User-Agent" to PLAYER_AGENT), timeoutMs = timeoutMs)
                .also { if (!vpn) Prefs.setBool(c, "xt_needs_vpn", false) }          // works without one here
                .also { r -> if (action != null && r.trimStart().startsWith("[") && r.length > 2 && isExpired(c)) {
                    // The provider is handing out lists again, so it's been renewed: forget the old end date until the next check.
                    Prefs.setString(c, "xt_status", "Active"); Prefs.setString(c, "xt_exp", null)
                } }
        } catch (e: Exception) {
            if (!vpn && e.message.orEmpty().contains("HTTP 466")) Prefs.setBool(c, "xt_needs_vpn", true)   // turned away without it
            throw e
        }
    }

    fun apiForDiag(c: Context, action: String) = api(c, action)

    private fun api(c: Context, action: String? = null, extra: String = ""): String =
        "${server(c)}/player_api.php?username=${enc(user(c))}&password=${enc(pass(c))}" +
            (action?.let { "&action=$it" } ?: "") + extra

    /** Checks the login. Returns a short status ("Active until 3 Mar 2027") or throws with the reason. */
    fun check(c: Context): String {
        val o = org.json.JSONObject(apiGet(c))
        val info = o.optJSONObject("user_info") ?: throw Exception("The server didn’t accept the login")
        if (info.optInt("auth", 1) == 0) throw Exception("Wrong username or password")
        val status = info.optString("status", "Active")
        Prefs.setString(c, "xt_status", status)
        val exp = info.optString("exp_date").toLongOrNull()
        if (exp != null) Prefs.setString(c, "xt_exp", (exp * 1000).toString())
        val until = exp?.let { " until " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(it * 1000)) }.orEmpty()
        return status + until
    }

    // ---------- Stream links ----------

    /** Catch-up ("Start Over") for providers that keep an archive: [start] in ms, [minutes] long. */
    fun archiveUrl(c: Context, id: Int, start: Long, minutes: Int): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        return "${server(c)}/timeshift/${enc(user(c))}/${enc(pass(c))}/$minutes/${f.format(java.util.Date(start))}/$id.ts"
    }

    fun liveUrl(c: Context, id: Int, hls: Boolean = false) = "${server(c)}/live/${enc(user(c))}/${enc(pass(c))}/$id.${if (hls) "m3u8" else "ts"}"
    fun vodUrl(c: Context, v: Vod) = "${server(c)}/movie/${enc(user(c))}/${enc(pass(c))}/${v.id}.${v.ext}"
    fun episodeUrl(c: Context, e: Episode) = "${server(c)}/series/${enc(user(c))}/${enc(pass(c))}/${e.id}.${e.ext}"

    // ---------- Lists (blocking; call off the main thread) ----------

    @Volatile private var liveMem: List<Live>? = null
    @Volatile private var vodMem: List<Vod>? = null
    @Volatile private var seriesMem: List<Series>? = null

    private fun dir(c: Context) = File(c.cacheDir, "xtream").apply { mkdirs() }

    /** Load the lists in the background so the first film or channel doesn't wait for a big download. */
    fun warm(c: Context) {
        if (!isSetUp(c)) return
        val app = c.applicationContext
        Thread {
            // The UK channel groups first (small, and what the TV Guide opens on), several at once.
            try {
                val cats = liveCategories(app)
                val ids = cats.filter { it.value.trim().startsWith("UK", true) }.keys.toList()
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                ids.forEach { id -> pool.execute { try { liveInCategory(app, id) } catch (e: Exception) {} } }
                pool.shutdown(); pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {}
            try { vod(app) } catch (e: Exception) {}
            try { series(app) } catch (e: Exception) {}
            try { live(app) } catch (e: Exception) {}
        }.start()
    }

    fun clearCache(c: Context) {
        liveMem = null; vodMem = null; seriesMem = null; catMem = null
        dir(c).listFiles()?.forEach { it.delete() }
    }

    private const val MAGIC = 0x58544931

    /**
     * Provider lists can be huge (a hundred thousand films), so the first read downloads and parses the JSON
     * once and keeps a compact binary copy; every later start reads that in a fraction of the time, and an
     * old copy is refreshed quietly in the background.
     */
    private fun <T> list(
        c: Context, action: String, enc: (java.io.DataOutputStream, T) -> Unit, dec: (java.io.DataInputStream) -> T,
        extra: String = "", cacheName: String = action, parse: (JsonReader) -> T?,
    ): List<T> {
        val bin = File(dir(c), "$cacheName.bin")
        fun write(items: List<T>) {
            if (items.isEmpty()) return
            val tmp = File(bin.path + ".part")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { o ->
                o.writeInt(MAGIC); o.writeInt(items.size); items.forEach { enc(o, it) }
            }
            tmp.renameTo(bin)
        }
        if (bin.exists()) {
            val items = try {
                java.io.DataInputStream(java.io.BufferedInputStream(bin.inputStream(), 64 * 1024)).use { i ->
                    if (i.readInt() != MAGIC) throw java.io.IOException("format")
                    List(i.readInt()) { dec(i) }
                }
            } catch (e: Exception) { bin.delete(); null }
            if (!items.isNullOrEmpty()) {
                if (System.currentTimeMillis() - bin.lastModified() > 2 * 3600_000L) {
                    bin.setLastModified(System.currentTimeMillis())      // one refresh at a time
                    Thread { try { write(listJson(c, action, extra, cacheName, parse, force = true)) } catch (e: Exception) {} }.start()
                }
                return items
            }
        }
        return listJson(c, action, extra, cacheName, parse, force = false).also { write(it) }
    }

    private fun <T> listJson(c: Context, action: String, extra: String, cacheName: String, parse: (JsonReader) -> T?, force: Boolean): List<T> {
        val f = File(dir(c), "$cacheName.json")
        if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > 2 * 3600_000L) {
            val tmp = File(dir(c), "$cacheName.tmp")
            gated {
                val conn = URL(api(c, action, extra)).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", PLAYER_AGENT)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw java.io.IOException("HTTP $code")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally { conn.disconnect() }
            }
            tmp.renameTo(f)
        }
        val out = ArrayList<T>()
        JsonReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) { f.delete(); return out }
            r.beginArray()
            while (r.hasNext()) parse(r)?.let { out += it }
            r.endArray()
        }
        if (out.isEmpty()) f.delete()   // an empty answer is probably an error - ask again next time
        f.delete()                      // the binary copy replaces it; keeps the cache small
        return out
    }

    /** Reads one JSON object as a flat map of its simple values. */
    private fun obj(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING -> m[k] = r.nextString()
                JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private fun icon(s: String?) = s?.takeIf { it.startsWith("http") }

    @Volatile private var catMem: Map<String, String>? = null

    /** Channel group names ("UK ~ Entertainment") by category id. */
    fun liveCategories(c: Context): Map<String, String> = catMem ?: try {
        val a = org.json.JSONArray(apiGet(c, "get_live_categories"))
        (0 until a.length()).associate { val o = a.getJSONObject(it); o.optString("category_id") to o.optString("category_name") }
    } catch (e: Exception) { emptyMap() }.also { if (it.isNotEmpty()) catMem = it }

    // ---------- The library, for browsing and search ----------

    @Volatile private var vodCatMem: Map<String, String>? = null
    @Volatile private var seriesCatMem: Map<String, String>? = null

    private fun cats(c: Context, action: String): Map<String, String> = try {
        val a = org.json.JSONArray(apiGet(c, action))
        (0 until a.length()).associate { val o = a.getJSONObject(it); o.optString("category_id") to o.optString("category_name").trim() }
    } catch (e: Exception) { emptyMap() }

    fun vodCategories(c: Context): Map<String, String> = vodCatMem ?: cats(c, "get_vod_categories").also { if (it.isNotEmpty()) vodCatMem = it }
    fun seriesCategories(c: Context): Map<String, String> = seriesCatMem ?: cats(c, "get_series_categories").also { if (it.isNotEmpty()) seriesCatMem = it }

    private val TAG_IN_FRONT = Regex("^\\s*(\\|[^|]{1,6}\\||\\[[^\\]]{1,6}]|[A-Z]{2,4}\\s*[-:|]\\s+)\\s*")

    /** A tidy name for the screen: without the "EN - " / "|UK| " style tags providers put in front. */
    fun clean(name: String): String = name.replace(TAG_IN_FRONT, "").replace(Regex("\\s{2,}"), " ").trim()

    /** Most recently added first (the provider numbers things as it adds them). */
    fun newestFilms(c: Context, n: Int = 40): List<Vod> = vod(c).sortedByDescending { it.id }.distinctBy { TextMatch.vodKey(clean(it.name)) }.take(n)
    fun newestSeries(c: Context, n: Int = 40): List<Series> = series(c).sortedByDescending { it.id }.distinctBy { TextMatch.vodKey(clean(it.name)) }.take(n)

    /** Category name -> its films (newest first), in the provider's own order of categories. */
    fun filmsByCategory(c: Context): List<Pair<String, List<Vod>>> {
        val groups = vod(c).groupBy { it.category }
        return vodCategories(c).mapNotNull { (id, name) -> groups[id]?.takeIf { it.isNotEmpty() }?.let { name to it.sortedByDescending { v -> v.id } } }
    }

    fun seriesByCategory(c: Context): List<Pair<String, List<Series>>> {
        val groups = series(c).groupBy { it.category }
        return seriesCategories(c).mapNotNull { (id, name) -> groups[id]?.takeIf { it.isNotEmpty() }?.let { name to it.sortedByDescending { v -> v.id } } }
    }

    /** Films and series whose name matches what was typed - everything here really plays. */
    fun searchLibrary(c: Context, query: String, n: Int = 40): Pair<List<Vod>, List<Series>> {
        val q = TextMatch.vodKey(query)
        if (q.isBlank()) return emptyList<Vod>() to emptyList()
        fun score(name: String): Int {
            val k = TextMatch.vodKey(clean(name))
            return when { k == q -> 0; k.startsWith(q) -> 1; k.contains(" $q") -> 2; k.contains(q) -> 3; else -> -1 }
        }
        val films = vod(c).mapNotNull { v -> score(v.name).takeIf { it >= 0 }?.let { it to v } }
            .sortedWith(compareBy({ it.first }, { -it.second.id })).map { it.second }.distinctBy { TextMatch.vodKey(clean(it.name)) }.take(n)
        val shows = series(c).mapNotNull { v -> score(v.name).takeIf { it >= 0 }?.let { it to v } }
            .sortedWith(compareBy({ it.first }, { -it.second.id })).map { it.second }.distinctBy { TextMatch.vodKey(clean(it.name)) }.take(n)
        return films to shows
    }

    // ---------- VPN ----------

    /** Is a VPN connected on this TV right now? */
    fun vpnOn(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { false }

    /** Learned from use: on this TV, Dodgy Box only answers through the VPN. */
    fun needsVpn(c: Context) = Prefs.bool(c, "xt_needs_vpn", false)

    private fun java.io.DataOutputStream.str(x: String?) = writeUTF((x ?: "").take(600))

    fun live(c: Context): List<Live> = liveMem ?: list(c, "get_live_streams",
        { o, v: Live -> o.writeInt(v.id); o.writeInt(v.num); o.str(v.name); o.str(v.icon); o.str(v.category) },
        { i -> Live(i.readInt(), i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Live(id, m["num"]?.toIntOrNull() ?: 0, m["name"].orEmpty().trim(), icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { liveMem = it }

    /** The provider's "what's on" listings for a channel, as raw JSON. */
    fun shortEpg(c: Context, streamId: Int, limit: Int = 14): String =
        apiGet(c, "get_short_epg", "&stream_id=$streamId&limit=$limit", 10_000)

    /** One group's channels (small and quick), instead of the whole line-up. */
    fun liveInCategory(c: Context, categoryId: String): List<Live> =
        liveMem?.filter { it.category == categoryId }?.takeIf { it.isNotEmpty() } ?: list(c, "get_live_streams",
            { o, v: Live -> o.writeInt(v.id); o.writeInt(v.num); o.str(v.name); o.str(v.icon); o.str(v.category) },
            { i -> Live(i.readInt(), i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) },
            extra = "&category_id=$categoryId", cacheName = "live_cat_$categoryId") { r ->
            val m = obj(r)
            val id = m["stream_id"]?.toIntOrNull() ?: return@list null
            Live(id, m["num"]?.toIntOrNull() ?: 0, m["name"].orEmpty().trim(), icon(m["stream_icon"]), m["category_id"].orEmpty().ifEmpty { categoryId })
        }

    private val qualityNoise = Regex("(?i)\\b(uhd|fhd|hd|sd|4k|hevc|h\\.?265|1080p?|720p?|50fps|60fps)\\b|\\(f\\)|\\[[^\\]]*\\]")
    private fun qualityRank(n: String): Int = when {
        Regex("(?i)\\b(uhd|4k)\\b").containsMatchIn(n) -> 4
        Regex("(?i)\\bfhd\\b|1080").containsMatchIn(n) -> 3
        Regex("(?i)\\bhd\\b|720").containsMatchIn(n) -> 2
        Regex("(?i)\\bsd\\b").containsMatchIn(n) -> 0
        else -> 1
    }

    /** One entry per channel: the highest-definition version (UHD, then FHD, HD, SD), in channel-number order. */
    fun best(list: List<Live>): List<Live> =
        list.groupBy { TextMatch.normalise(qualityNoise.replace(it.name, " ")).ifEmpty { it.name.lowercase() } }.values
            .map { g -> g.sortedWith(compareByDescending<Live> { qualityRank(it.name) }.thenBy { it.num }).first() }
            .sortedBy { it.num }

    // ---------- Channels in TV-guide order (BBC One 1, BBC Two 2, ITV1 3 ...) ----------

    private val GUIDE_ORDER = listOf("bbc one", "bbc two", "itv1", "channel 4", "5", "itv2", "bbc three", "bbc four", "itv3", "itv4",
        "e4", "more4", "film4", "5star", "5usa", "5action", "dave", "drama", "yesterday", "quest", "really", "gold", "alibi", "w", "comedy central",
        "sky showcase", "sky atlantic", "sky max", "sky witness", "sky comedy", "sky crime", "sky documentaries", "sky nature", "sky history", "sky arts",
        "sky cinema premiere", "sky cinema select", "sky cinema hits", "sky cinema action", "sky cinema comedy", "sky cinema family", "sky cinema thriller",
        "sky cinema drama", "sky cinema sci fi horror", "sky cinema animation", "sky cinema greats",
        "sky sports main event", "sky sports premier league", "sky sports football", "sky sports f1", "sky sports cricket", "sky sports golf",
        "sky sports racing", "sky sports arena", "sky sports action", "sky sports mix", "sky sports news",
        "tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "premier sports 1", "premier sports 2", "eurosport 1", "eurosport 2",
        "bbc news", "sky news", "gb news", "cnn", "al jazeera",
        "cbbc", "cbeebies", "cartoon network", "cartoonito", "nickelodeon", "nick jr", "disney junior", "pop",
        "discovery", "national geographic", "animal planet", "history", "crime investigation", "investigation discovery")

    /** "UK: BBC ONE FHD (F)" -> "BBC ONE"; tidier for the guide. */
    fun displayName(name: String): String = name
        .replace(Regex("(?i)^\\s*(uk|gb|vip)\\s*[:|~\\-]+\\s*"), "")
        .replace(Regex("(?i)\\b(uhd|fhd|hd|sd|4k|hevc|h\\.?265|1080p?|720p?|50fps|60fps|backup|raw)\\b|\\(f\\)|\\[[^\\]]*\\]"), "")
        .replace(Regex("\\s{2,}"), " ").trim(' ', '-', '|', ':').ifBlank { name }

    /** The big channels first in the usual UK order and numbered from 1, then everything else; quality tags dropped from names. */
    fun guideOrder(list: List<Live>): List<Live> {
        val left = list.toMutableList()
        val first = mutableListOf<Live>()
        for (key in GUIDE_ORDER) {
            val exact = left.firstOrNull { TextMatch.normalise(it.name) == key }
            val hit = exact ?: if (key.length >= 4) left.firstOrNull { TextMatch.matches(TextMatch.normalise(it.name), key) && !TextMatch.normalise(it.name).contains("+1") } else null
            if (hit != null) { first += hit; left.remove(hit) }
        }
        return (first + left).mapIndexed { i, l -> l.copy(num = i + 1, name = displayName(l.name)) }
    }

    /** A group's channels, one per station. */
    fun liveGroup(c: Context, categoryId: String): List<Live> = best(liveInCategory(c, categoryId))

    // Favourites (channel ids), kept on the TV.
    fun favourites(c: Context): Set<Int> = (Prefs.string(c, ProfileManager.scope(c, "fav_live")) ?: "").split(",").mapNotNull { it.toIntOrNull() }.toSet()
    fun toggleFavourite(c: Context, id: Int): Boolean {
        val f = favourites(c).toMutableSet()
        val added = if (id in f) { f.remove(id); false } else { f.add(id); true }
        Prefs.setString(c, ProfileManager.scope(c, "fav_live"), f.joinToString(","))
        return added
    }
    fun liveById(c: Context, ids: Set<Int>): List<Live> = try { live(c).filter { it.id in ids } } catch (e: Exception) { emptyList() }

    fun vod(c: Context): List<Vod> = vodMem ?: list(c, "get_vod_streams",
        { o, v: Vod -> o.writeInt(v.id); o.str(v.name); o.str(v.ext); o.str(v.icon); o.str(v.category) },
        { i -> Vod(i.readInt(), i.readUTF(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["stream_id"]?.toIntOrNull() ?: return@list null
        Vod(id, m["name"].orEmpty().trim(), m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4", icon(m["stream_icon"]), m["category_id"].orEmpty())
    }.also { vodMem = it }

    fun series(c: Context): List<Series> = seriesMem ?: list(c, "get_series",
        { o, v: Series -> o.writeInt(v.id); o.str(v.name); o.str(v.cover); o.str(v.category) },
        { i -> Series(i.readInt(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF()) }) { r ->
        val m = obj(r)
        val id = m["series_id"]?.toIntOrNull() ?: return@list null
        Series(id, m["name"].orEmpty().trim(), icon(m["cover"]), m["category_id"].orEmpty())
    }.also { seriesMem = it }

    private val episodeMem = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<Episode>>>()

    /** Look the series up and fetch its episodes in the background, ready for when Watch is pressed. */
    fun prefetchSeries(c: Context, title: String, year: Int) {
        Thread { try { findSeries(c, title, year)?.let { episodes(c, it) } } catch (e: Exception) {} }.start()
    }

    fun episodes(c: Context, s: Series): List<Episode> {
        episodeMem[s.id]?.let { (at, list) -> if (System.currentTimeMillis() - at < 30 * 60_000L) return list }
        return fetchEpisodes(c, s).also { if (it.isNotEmpty()) episodeMem[s.id] = System.currentTimeMillis() to it }
    }

    private fun fetchEpisodes(c: Context, s: Series): List<Episode> {
        val o = org.json.JSONObject(apiGet(c, "get_series_info", "&series_id=${s.id}", 20_000))
        val eps = o.optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (season in eps.keys()) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val info = e.optJSONObject("info")
                val secs = info?.optString("duration_secs")?.toIntOrNull()
                    ?: info?.optString("duration")?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }?.let { it[0] * 3600 + it[1] * 60 + it[2] } ?: 0
                val num = e.optInt("episode_num", i + 1)
                // "Show - S01E03 - Name" -> "Name"
                val raw = e.optString("title")
                val name = Regex("S\\d+\\s*E\\d+\\s*[-–:]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: raw
                out += Episode(e.optString("id"), e.optInt("season", season.toIntOrNull() ?: 1), num, name, e.optString("container_extension").ifBlank { "mp4" },
                    info?.optString("movie_image")?.takeIf { it.startsWith("http") }, info?.optString("plot").orEmpty().trim(), (secs / 60))
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    // ---------- Matching ----------

    /** A provider channel for a guide channel name ("BBC One" → "UK: BBC ONE FHD"). */
    fun findLive(c: Context, name: String): Live? {
        val wanted = TiviTuner.normalise(name)
        val all = live(c)
        val keyed = all.map { it to TiviTuner.normalise(it.name.replace(Regex("^[A-Za-z]{2,3}\\s*[:|\\-]\\s*"), "")) }
        // Exact name first; prefer the best quality (FHD/HD over SD) when there are several.
        val exact = keyed.filter { it.second == wanted }.map { it.first }
        if (exact.isNotEmpty()) return exact.maxByOrNull { quality(it.name) }
        return keyed.filter { (_, k) -> Regex("(^| )" + Regex.escape(wanted) + "( |$)").containsMatchIn(k) && !k.contains("+1") == !wanted.contains("+1") && k.length <= wanted.length + 10 }
            .map { it.first }.maxByOrNull { quality(it.name) }
    }

    private fun quality(n: String): Int {
        val s = n.lowercase()
        return when { "uhd" in s || "4k" in s -> 2; "fhd" in s -> 4; "hd" in s -> 3; "sd" in s -> 0; else -> 1 }
    }

    private fun yearOf(n: String) = Regex("\\b(19|20)\\d{2}\\b").find(n)?.value?.toIntOrNull()

    fun findVod(c: Context, title: String, year: Int): Vod? {
        val wanted = TiviTuner.vodKey(title)
        val hits = vod(c).filter { TiviTuner.vodKey(it.name) == wanted }
        return hits.firstOrNull { year > 0 && yearOf(it.name) == year } ?: hits.firstOrNull { yearOf(it.name) == null || year <= 0 } ?: hits.firstOrNull()
    }

    fun findSeries(c: Context, title: String, year: Int): Series? {
        val wanted = TiviTuner.vodKey(title)
        val hits = series(c).filter { TiviTuner.vodKey(it.name) == wanted }
        return hits.firstOrNull { year > 0 && yearOf(it.name) == year } ?: hits.firstOrNull()
    }
}
