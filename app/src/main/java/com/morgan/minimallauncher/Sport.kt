package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sport hub: follow any mix of teams and whole sports (football, F1, rugby, NFL, NBA, golf, tennis...).
 * Fixtures, results and live scores come from ESPN's public scores feed; which of your channels each
 * one is on comes from the TV guide.
 */
object Sport {
    private const val ROOT = "https://site.api.espn.com/apis/site/v2/sports"

    /** Something you can follow: a competition where you pick a team, or a whole sport/series. */
    data class Choice(val path: String, val name: String, val teams: Boolean, val icon: String)

    val GROUPS: List<Pair<String, List<Choice>>> = listOf(
        "Football" to listOf(
            Choice("soccer/eng.1", "Premier League", true, "⚽"), Choice("soccer/eng.2", "Championship", true, "⚽"),
            Choice("soccer/eng.3", "League One", true, "⚽"), Choice("soccer/eng.4", "League Two", true, "⚽"),
            Choice("soccer/sco.1", "Scottish Premiership", true, "⚽"), Choice("soccer/esp.1", "La Liga", true, "⚽"),
            Choice("soccer/ita.1", "Serie A", true, "⚽"), Choice("soccer/ger.1", "Bundesliga", true, "⚽"),
            Choice("soccer/fra.1", "Ligue 1", true, "⚽"), Choice("soccer/uefa.champions", "Champions League (all matches)", false, "⚽"),
            Choice("soccer/fifa.world", "World Cup (all matches)", false, "⚽")),
        "Motor Racing" to listOf(Choice("racing/f1", "Formula 1", false, "🏎"), Choice("racing/irl", "IndyCar", false, "🏎"), Choice("racing/nascar-premier", "NASCAR", false, "🏎")),
        "Rugby" to listOf(Choice("rugby/17567", "Nations Championship (all matches)", false, "🏉"), Choice("rugby/17567", "Nations Championship - pick a country", true, "🏉"),
            Choice("rugby/267979", "Premiership Rugby", true, "🏉"), Choice("rugby/180659", "Six Nations", true, "🏉"),
            Choice("rugby/270557", "United Rugby Championship", true, "🏉"), Choice("rugby/164205", "Rugby World Cup", true, "🏉")),
        "American Sports" to listOf(Choice("football/nfl", "NFL", true, "🏈"), Choice("basketball/nba", "NBA", true, "🏀"),
            Choice("hockey/nhl", "NHL", true, "🏒"), Choice("baseball/mlb", "MLB", true, "⚾")),
        "Golf & Tennis" to listOf(Choice("golf/pga", "PGA Tour golf", false, "⛳"), Choice("golf/eur", "DP World Tour golf", false, "⛳"),
            Choice("tennis/atp", "ATP tennis (men)", false, "🎾"), Choice("tennis/wta", "WTA tennis (women)", false, "🎾")),
        "Combat" to listOf(Choice("mma/ufc", "UFC", false, "🥊")),
    )

    /** What you follow: a team ([teamId] set) or a whole competition. */
    data class Follow(val path: String, val teamId: String?, val name: String, val short: String, val abbr: String, val logo: String?, val icon: String) {
        val isTeam get() = teamId != null
    }

    data class Match(
        val id: String, val start: Long, val title: String, val home: String, val away: String, val homeAbbr: String, val awayAbbr: String,
        val homeScore: String, val awayScore: String, val state: String, val detail: String, val competition: String,
        val homeLogo: String?, val awayLogo: String?, val teamGame: Boolean, val follow: Follow,
    ) {
        val live get() = state == "in"
        val finished get() = state == "post"
        fun scoreLine() = if (teamGame) "$homeAbbr $homeScore–$awayScore $awayAbbr" else title
    }

    /** Spoiler-free: scores stay hidden until you've watched (Sport tab → Scores). */
    fun spoilersHidden(c: Context) = Prefs.bool(c, ProfileManager.scope(c, "sport_no_spoilers"), false)
    fun setSpoilersHidden(c: Context, on: Boolean) = Prefs.setBool(c, ProfileManager.scope(c, "sport_no_spoilers"), on)

    // ---------- What this person follows ----------

    private fun key(c: Context) = ProfileManager.scope(c, "sport_follows")

    fun follows(c: Context): List<Follow> {
        val list = try {
            Prefs.string(c, key(c))?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                Follow(it.getString("path"), it.optString("team").ifBlank { null }, it.getString("name"), it.optString("short"), it.optString("abbr"),
                    it.optString("logo").ifBlank { null }, it.optString("icon", "🏆"))
            } }
        } catch (e: Exception) { null }
        if (list != null) return list
        // The first version kept one football team: carry it over.
        val old = try { Prefs.string(c, ProfileManager.scope(c, "sport_team"))?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return emptyList()
        val f = Follow("soccer/" + old.optString("league", "eng.1"), old.getString("id"), old.getString("name"), old.optString("short"), old.optString("abbr"),
            old.optString("logo").ifBlank { null }, "⚽")
        save(c, listOf(f))
        return listOf(f)
    }

    private fun save(c: Context, list: List<Follow>) = Prefs.setString(c, key(c), JSONArray().apply {
        list.forEach { put(JSONObject().put("path", it.path).put("team", it.teamId ?: "").put("name", it.name).put("short", it.short)
            .put("abbr", it.abbr).put("logo", it.logo ?: "").put("icon", it.icon)) }
    }.toString())

    fun add(c: Context, f: Follow) = save(c, follows(c).filterNot { it.path == f.path && it.teamId == f.teamId } + f)
    fun remove(c: Context, f: Follow) = save(c, follows(c).filterNot { it.path == f.path && it.teamId == f.teamId })

    // ---------- Feed (call off the main thread) ----------

    fun teams(ch: Choice): List<Follow> {
        val o = JSONObject(Net.get("$ROOT/${ch.path}/teams", timeoutMs = 12_000))
        val arr = o.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
        return (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("team") }.map {
            Follow(ch.path, it.getString("id"), it.optString("displayName"), it.optString("shortDisplayName"), it.optString("abbreviation"),
                it.optJSONArray("logos")?.optJSONObject(0)?.optString("href"), ch.icon)
        }.sortedBy { it.name }
    }

    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val isoA = fmt("yyyy-MM-dd'T'HH:mm'Z'"); private val isoB = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'"); private val isoC = fmt("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    fun time(s: String): Long = listOf(isoA, isoB, isoC).firstNotNullOfOrNull { f -> try { synchronized(f) { f.parse(s)?.time } } catch (e: Exception) { null } } ?: 0L
    private val day = fmt("yyyyMMdd")

    private fun parseEvent(e: JSONObject, f: Follow): Match? {
        val comps = e.optJSONArray("competitions")
        // Races and tournaments: the main session (the race, not practice) where there are several.
        val comp = comps?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .let { l -> l.lastOrNull { it.optJSONObject("type")?.optString("abbreviation").equals("Race", true) } ?: l.lastOrNull() } } ?: return null
        val cs = comp.optJSONArray("competitors") ?: JSONArray()
        val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
        val home = sides.firstOrNull { it.optString("homeAway") == "home" }
        val away = sides.firstOrNull { it.optString("homeAway") == "away" }
        val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
        val start = time(comp.optString("date").ifBlank { e.optString("date") })
        val name = e.optString("name").ifBlank { e.optString("shortName") }
        fun team(x: JSONObject?) = x?.optJSONObject("team") ?: x?.optJSONObject("athlete") ?: JSONObject()
        fun score(x: JSONObject?) = x?.optJSONObject("score")?.optString("displayValue") ?: x?.optString("score").orEmpty()
        fun logo(x: JSONObject?) = team(x).let { t -> t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t.optString("logo").ifBlank { null } }
        val teamGame = home != null && away != null
        return Match(
            e.optString("id") + "_" + comp.optString("id"), start, if (teamGame) "${team(home).optString("displayName")} v ${team(away).optString("displayName")}" else name,
            team(home).optString("displayName"), team(away).optString("displayName"),
            SportTables.abbr(team(home).optString("displayName"), team(home).optString("abbreviation")), SportTables.abbr(team(away).optString("displayName"), team(away).optString("abbreviation")),
            score(home).ifBlank { "0" }, score(away).ifBlank { "0" },
            st?.optString("state").orEmpty(), st?.optString("shortDetail").orEmpty(),
            e.optJSONObject("season")?.optString("slug")?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: f.name,
            logo(home), logo(away), teamGame, f,
        )
    }

    private fun events(o: JSONObject) = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /**
     * Every event between [from] and [to]. Football takes a date range in one go; rugby (and some others)
     * refuse ranges, so their league calendar is read and each match day fetched (a few at a time).
     */
    fun window(path: String, from: Long, to: Long): List<JSONObject> {
        val range = day.format(java.util.Date(from)) + "-" + day.format(java.util.Date(to))
        try { return events(JSONObject(Net.get("$ROOT/$path/scoreboard?dates=$range&limit=300", timeoutMs = 15_000))) } catch (e: Exception) {}
        val base = JSONObject(Net.get("$ROOT/$path/scoreboard", timeoutMs = 12_000))
        val cal = base.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar")
        val days = (0 until (cal?.length() ?: 0)).mapNotNull { i ->
            val v = cal!!.opt(i)
            // Calendar entries are dates ("2026-11-14T08:00Z") or, for some sports, objects with a startDate.
            val str = (v as? String) ?: (v as? JSONObject)?.optString("startDate") ?: return@mapNotNull null
            time(str).takeIf { it in (from - 86_400_000L)..(to + 86_400_000L) }?.let { day.format(java.util.Date(it)) }
        }.distinct()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val parts = days.map { d -> pool.submit<List<JSONObject>> { try { events(JSONObject(Net.get("$ROOT/$path/scoreboard?dates=$d", timeoutMs = 12_000))) } catch (e: Exception) { emptyList() } } }
        val all = parts.flatMap { it.get() } + events(base)
        pool.shutdown()
        return all.distinctBy { it.optString("id") }
    }

    /** Everything for one follow: upcoming, finished and live (team schedule, or the competition's calendar). */
    fun matches(f: Follow): List<Match> {
        val now = System.currentTimeMillis()
        val list = if (f.isTeam && !f.path.startsWith("rugby/")) {
            (try { events(JSONObject(Net.get("$ROOT/${f.path}/teams/${f.teamId}/schedule?fixture=true", timeoutMs = 12_000))) } catch (e: Exception) { emptyList() } +
                try { events(JSONObject(Net.get("$ROOT/${f.path}/teams/${f.teamId}/schedule", timeoutMs = 12_000))) } catch (e: Exception) { emptyList() })
                .mapNotNull { parseEvent(it, f) }
        } else {
            window(f.path, now - 30 * 86_400_000L, now + 75 * 86_400_000L).mapNotNull { parseEvent(it, f) }
        }
        // Today's scoreboard has the live state; merge it in.
        val today = try { events(JSONObject(Net.get("$ROOT/${f.path}/scoreboard", timeoutMs = 10_000))).mapNotNull { parseEvent(it, f) } } catch (e: Exception) { emptyList() }
        val merged = (list.associateBy { it.id } + today.associateBy { it.id }).values.toList()
        return if (f.isTeam) merged.filter { it.home == f.name || it.away == f.name || it.homeAbbr == f.abbr && it.homeAbbr.isNotEmpty() || it.awayAbbr == f.abbr && it.awayAbbr.isNotEmpty() } else merged
    }

    // ---------- Which of your channels it's on ----------

    private val GENERIC = setOf("the", "and", "live", "grand", "prix", "round", "match", "game", "championship", "league", "cup", "open", "tour", "race", "united", "city", "town")

    fun channelFor(m: Match, guide: List<LiveGuide.Channel>): LiveGuide.Channel? {
        fun words(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").split(" ").filter { it.length > 3 && it !in GENERIC }
        val a = words(m.home); val b = words(m.away); val t = words(m.title)
        val sportWord = when { m.follow.path.startsWith("racing/f1") -> "formula|f1"; m.follow.path.startsWith("golf") -> "golf"; m.follow.path.startsWith("tennis") -> "tennis"
            m.follow.path.startsWith("mma") -> "ufc"; m.follow.path.startsWith("football/nfl") -> "nfl"; m.follow.path.startsWith("basketball") -> "nba"; else -> null }
        return guide.firstOrNull { ch ->
            ch.programmes.any { p ->
                p.start <= m.start + 45 * 60_000L && p.stop >= m.start + 10 * 60_000L && p.title.lowercase().let { title ->
                    if (m.teamGame) a.any { it in title } && b.any { it in title }
                    else (sportWord != null && Regex("\\b($sportWord)\\b").containsMatchIn(title)) || t.count { it in title } >= 2
                }
            }
        }
    }

    /** The Dodgy Box channel for a guide channel (best quality). */
    fun liveFor(c: Context, ch: LiveGuide.Channel): Xtream.Live? = try {
        Xtream.best(Xtream.live(c)).firstOrNull { LiveGuide.guideFor(it.name)?.id == ch.id }
    } catch (e: Exception) { null }

    // ---------- Live scores in the corner (shown by the always-on service) ----------

    @Volatile private var lastPoll = 0L
    private val lastLines = HashMap<String, String>()

    /** Called every 30 s by the service: polls once a minute; shows whatever you follow that's live. */
    fun tick(c: Context, show: (String?, Boolean) -> Unit) {
        if (!Prefs.bool(c, "sport_overlay", true)) { if (lastLines.isNotEmpty()) { lastLines.clear(); show(null, false) }; return }
        val list = follows(c)
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPoll < 60_000L) return
        lastPoll = now
        Thread {
            val live = list.flatMap { f ->
                try { events(JSONObject(Net.get("$ROOT/${f.path}/scoreboard", timeoutMs = 10_000))).mapNotNull { parseEvent(it, f) } } catch (e: Exception) { emptyList() }
                    .filter { m -> m.live && (!f.isTeam || m.home == f.name || m.away == f.name) }
            }.distinctBy { it.id }.take(3)
            var goal = false
            val lines = live.map { m ->
                val line = if (spoilersHidden(c)) "${m.follow.icon} ${m.title}  ·  LIVE" else "${m.follow.icon} ${m.scoreLine()}  ·  ${m.detail}"
                if (m.teamGame && lastLines[m.id]?.substringBefore("  ·") != null && lastLines[m.id]!!.substringBefore("  ·") != line.substringBefore("  ·")) goal = true
                lastLines[m.id] = line
                line
            }
            val text = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            android.os.Handler(android.os.Looper.getMainLooper()).post { show(text, goal) }
        }.start()
    }
}

/** League tables and the whole season's fixtures, for the Table / Fixtures screen. */
object SportTables {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val V2 = "https://site.api.espn.com/apis/v2/sports"

    /** How rugby graphics abbreviate the national teams (ESPN says "SOU" for South Africa, for one). */
    private val NATION = mapOf("south africa" to "RSA", "new zealand" to "NZL", "australia" to "AUS", "argentina" to "ARG", "fiji" to "FIJ", "japan" to "JPN",
        "ireland" to "IRE", "england" to "ENG", "scotland" to "SCO", "wales" to "WAL", "france" to "FRA", "italy" to "ITA", "georgia" to "GEO", "samoa" to "SAM", "tonga" to "TGA")
    fun abbr(name: String, given: String) = NATION[name.lowercase().trim()] ?: given
    private val SOUTH = setOf("south africa", "new zealand", "australia", "argentina", "fiji", "japan")

    data class Row(val rank: Int, val name: String, val abbr: String, val logo: String?, val p: String, val w: String, val d: String, val l: String, val pd: String, val bp: String, val pts: String,
                   val id: String = "")
    data class Group(val name: String, val rows: List<Row>)
    data class Fixture(val id: String, val start: Long, val round: String, val home: String, val away: String, val homeAbbr: String, val awayAbbr: String,
                       val homeLogo: String?, val awayLogo: String?, val homeScore: String, val awayScore: String, val state: String, val detail: String, val venue: String,
                       val homeId: String = "", val awayId: String = "") {
        val finished get() = state == "post"
        val live get() = state == "in"
    }

    /** The table(s): one group per conference/hemisphere/pool. Field names vary by sport, so several are tried. */
    fun standings(path: String): List<Group> {
        val o = org.json.JSONObject(Net.get("$V2/$path/standings", timeoutMs = 15_000))
        fun rows(st: org.json.JSONObject?): List<Row> {
            val entries = st?.optJSONArray("entries") ?: return emptyList()
            return (0 until entries.length()).map { entries.getJSONObject(it) }.mapIndexed { i, e ->
                val t = e.optJSONObject("team") ?: org.json.JSONObject()
                val stats = e.optJSONArray("stats")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                fun stat(vararg keys: String): String {
                    for (k in keys) stats.firstOrNull { it.optString("name").equals(k, true) || it.optString("abbreviation").equals(k, true) || it.optString("type").equals(k, true) }
                        ?.let { s -> return s.optString("displayValue").ifBlank { s.optString("value") }.removeSuffix(".0") }
                    return "–"
                }
                Row(stat("rank").toIntOrNull() ?: (i + 1), t.optString("displayName"), abbr(t.optString("displayName"), t.optString("abbreviation").ifBlank { t.optString("shortDisplayName") }),
                    t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t.optString("logo").ifBlank { null },
                    stat("gamesPlayed", "GP", "P"), stat("wins", "gamesWon", "W"), stat("ties", "draws", "gamesDrawn", "D", "T"), stat("losses", "gamesLost", "L"),
                    stat("pointDifferential", "pointsDifference", "differential", "PD", "DIFF"), stat("bonusPoints", "totalBonusPoints", "BP", "B"),
                    stat("points", "PTS", "Pts"), t.optString("id"))
            }.sortedBy { it.rank }
        }
        val kids = o.optJSONArray("children")
        val groups = if (kids != null && kids.length() > 0) (0 until kids.length()).map { kids.getJSONObject(it) }.map { Group(it.optString("name"), rows(it.optJSONObject("standings"))) }
                     else listOf(Group(o.optString("name"), rows(o.optJSONObject("standings"))))
        // One combined table with both hemispheres in it: split it the way the tournament shows it.
        val all = groups.flatMap { it.rows }.distinctBy { it.name }
        val mixed = groups.any { g -> g.rows.any { it.name.lowercase() in SOUTH } && g.rows.any { it.name.lowercase() !in SOUTH } }
        if (mixed && all.count { it.name.lowercase() in SOUTH } >= 3 && all.count { it.name.lowercase() !in SOUTH } >= 3) {
            fun ranked(rs: List<Row>) = rs.sortedWith(compareByDescending<Row> { it.pts.toIntOrNull() ?: 0 }.thenByDescending { it.pd.replace("+", "").toIntOrNull() ?: 0 })
                .mapIndexed { i, r -> r.copy(rank = i + 1) }
            return listOf(Group("Northern Hemisphere", ranked(all.filter { it.name.lowercase() !in SOUTH })),
                          Group("Southern Hemisphere", ranked(all.filter { it.name.lowercase() in SOUTH })))
        }
        // Hemisphere groups: name each by who's actually in it (the feed's labels can be the wrong way round).
        return groups.filter { it.rows.isNotEmpty() }.map { g ->
            if (!g.name.contains("hemisphere", true)) g
            else g.copy(name = if (g.rows.count { it.name.lowercase() in SOUTH } * 2 > g.rows.size) "Southern Hemisphere" else "Northern Hemisphere")
        }.sortedBy { if (it.name.startsWith("Northern")) 0 else 1 }
    }

    private fun fmt(p: String) = java.text.SimpleDateFormat(p, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    private val day = fmt("yyyyMMdd")
    private val isos = listOf(fmt("yyyy-MM-dd'T'HH:mm'Z'"), fmt("yyyy-MM-dd'T'HH:mm:ss'Z'"), fmt("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    private fun time(s: String) = isos.firstNotNullOfOrNull { f -> try { synchronized(f) { f.parse(s)?.time } } catch (e: Exception) { null } } ?: 0L

    /** Competitions that have a table (for "Choose Tables" on the Sport tab). */
    fun tableChoices(): List<Sport.Choice> = Sport.GROUPS.flatMap { it.second }
        .filter { !it.path.startsWith("golf") && !it.path.startsWith("tennis") && !it.path.startsWith("mma") && it.path != "racing/irl" && it.path != "racing/nascar-premier" }
        .distinctBy { it.path }

    fun nameOf(path: String) = Sport.GROUPS.flatMap { it.second }.firstOrNull { it.path == path }?.name?.substringBefore(" (")?.substringBefore(" -") ?: path
    fun iconOf(path: String) = Sport.GROUPS.flatMap { it.second }.firstOrNull { it.path == path }?.icon ?: "🏆"

    /** The tables shown on the Sport tab: your choice, or (until you choose) the ones you follow. */
    fun picked(c: android.content.Context): List<String> {
        Prefs.string(c, ProfileManager.scope(c, "sport_tables"))?.let { s -> return s.split(",").filter { it.isNotBlank() } }
        val have = tableChoices().map { it.path }.toSet()
        return Sport.follows(c).map { it.path }.distinct().filter { it in have }
    }
    fun setPicked(c: android.content.Context, paths: List<String>) = Prefs.setString(c, ProfileManager.scope(c, "sport_tables"), paths.joinToString(","))

    /** Every match this season (and what's coming), with its round and venue. */
    fun season(path: String): List<Fixture> {
        val now = System.currentTimeMillis()
        val list = Sport.window(path, now - 200L * 86_400_000L, now + 120L * 86_400_000L).mapNotNull { e ->
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
            val cs = comp.optJSONArray("competitors") ?: return@mapNotNull null
            val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
            val h = sides.firstOrNull { it.optString("homeAway") == "home" } ?: sides.getOrNull(0) ?: return@mapNotNull null
            val a = sides.firstOrNull { it.optString("homeAway") == "away" } ?: sides.getOrNull(1) ?: return@mapNotNull null
            fun t(x: org.json.JSONObject) = x.optJSONObject("team") ?: org.json.JSONObject()
            fun logo(x: org.json.JSONObject) = t(x).optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t(x).optString("logo").ifBlank { null }
            fun score(x: org.json.JSONObject) = x.optJSONObject("score")?.optString("displayValue") ?: x.optString("score")
            val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
            val week = e.optJSONObject("week")?.optInt("number") ?: comp.optJSONObject("week")?.optInt("number")
            val roundName = comp.optJSONObject("type")?.optString("text")?.takeIf { it.isNotBlank() && !it.equals("standard", true) }
            val v = comp.optJSONObject("venue")
            Fixture(e.optString("id"), time(e.optString("date")), roundName ?: week?.takeIf { it > 0 }?.let { "Round $it" } ?: "",
                t(h).optString("displayName"), t(a).optString("displayName"), abbr(t(h).optString("displayName"), t(h).optString("abbreviation")), abbr(t(a).optString("displayName"), t(a).optString("abbreviation")),
                logo(h), logo(a), score(h), score(a), st?.optString("state").orEmpty(), st?.optString("shortDetail").orEmpty(),
                listOfNotNull(v?.optString("fullName")?.ifBlank { null }, v?.optJSONObject("address")?.optString("city")?.ifBlank { null }).joinToString(", "),
                t(h).optString("id"), t(a).optString("id"))
        }.sortedBy { it.start }
        // No round numbers in the feed: group by match weekend (matches within 4 days of each other).
        if (list.all { it.round.isNotBlank() }) return list
        var n = 0; var last = 0L
        return list.map { f -> if (f.start - last > 4 * 86_400_000L) n++; last = f.start; if (f.round.isBlank()) f.copy(round = "Round $n") else f }
    }
}

/** Formula 1: driver and team standings, the race calendar and the live order, styled like F1's own graphics. */
object F1 {
    private const val API = "https://site.api.espn.com/apis"

    data class Team(val name: String, val colour: Int, val dark: Boolean)    // dark = needs dark text on its colour
    data class Row(val rank: Int, val name: String, val code: String, val pts: String, val team: Team?)
    data class Race(val name: String, val start: Long, val end: Long)
    data class Runner(val pos: Int, val code: String, val name: String, val team: Team?, val gap: String)

    // The 2026 grid and the teams' colours.
    private val MCL = Team("McLaren", 0xFFFF8000.toInt(), false)
    private val FER = Team("Ferrari", 0xFFE8002D.toInt(), false)
    private val MER = Team("Mercedes", 0xFF27F4D2.toInt(), true)
    private val RBR = Team("Red Bull Racing", 0xFF1E2A6E.toInt(), false)
    private val WIL = Team("Williams", 0xFF1868DB.toInt(), false)
    private val RB = Team("Racing Bulls", 0xFF6692FF.toInt(), false)
    private val AST = Team("Aston Martin", 0xFF229971.toInt(), false)
    private val HAA = Team("Haas", 0xFFF2F2F2.toInt(), true)
    private val ALP = Team("Alpine", 0xFF0093CC.toInt(), false)
    private val AUD = Team("Audi", 0xFFB40020.toInt(), false)
    private val CAD = Team("Cadillac", 0xFF2A2A2E.toInt(), false)
    private val TEAMS = listOf(MCL, FER, MER, RBR, WIL, RB, AST, HAA, ALP, AUD, CAD)
    private val DRIVERS = mapOf("NOR" to MCL, "PIA" to MCL, "LEC" to FER, "HAM" to FER, "RUS" to MER, "ANT" to MER, "VER" to RBR, "HAD" to RBR,
        "ALB" to WIL, "SAI" to WIL, "LAW" to RB, "LIN" to RB, "ALO" to AST, "STR" to AST, "OCO" to HAA, "BEA" to HAA,
        "GAS" to ALP, "COL" to ALP, "HUL" to AUD, "BOR" to AUD, "BOT" to CAD, "PER" to CAD)

    fun teamFor(code: String) = DRIVERS[code.uppercase()]
    fun teamNamed(name: String): Team? {
        val n = name.lowercase()
        return TEAMS.firstOrNull { n.contains(it.name.lowercase().substringBefore(' ')) } ?: when {
            "bull" in n && ("visa" in n || "racing bulls" in n || "rb " in n) -> RB
            "sauber" in n || "kick" in n -> AUD
            "aston" in n -> AST
            else -> null
        }
    }

    /** Drivers and teams tables (call off the main thread). */
    fun standings(): Pair<List<Row>, List<Row>> {
        val o = org.json.JSONObject(Net.get("$API/v2/sports/racing/f1/standings", timeoutMs = 15_000))
        val kids = o.optJSONArray("children") ?: return emptyList<Row>() to emptyList()
        fun rows(child: org.json.JSONObject, drivers: Boolean): List<Row> {
            val e = child.optJSONObject("standings")?.optJSONArray("entries") ?: return emptyList()
            return (0 until e.length()).map { e.getJSONObject(it) }.mapIndexed { i, x ->
                val who = x.optJSONObject("athlete") ?: x.optJSONObject("team") ?: org.json.JSONObject()
                val stats = x.optJSONArray("stats")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                fun stat(vararg k: String) = k.firstNotNullOfOrNull { key -> stats.firstOrNull { it.optString("name") == key || it.optString("type") == key }?.optString("displayValue") }
                val name = who.optString("displayName").ifBlank { who.optString("name") }
                val code = who.optString("abbreviation")
                Row(stat("rank")?.toIntOrNull() ?: (i + 1), name, code, stat("championshipPts", "points") ?: "0",
                    if (drivers) teamFor(code) else teamNamed(name))
            }.sortedBy { it.rank }
        }
        val list = (0 until kids.length()).map { kids.getJSONObject(it) }
        val d = list.firstOrNull { it.optString("name").contains("driver", true) }?.let { rows(it, true) }.orEmpty()
        val t = list.firstOrNull { it.optString("name").contains("constructor", true) || it.optString("name").contains("team", true) }?.let { rows(it, false) }.orEmpty()
        // No constructors table in the feed: add up each team's drivers.
        val teams = t.ifEmpty {
            d.filter { it.team != null }.groupBy { it.team!! }.map { (tm, rs) -> tm to rs.sumOf { it.pts.toDoubleOrNull() ?: 0.0 } }
                .sortedByDescending { it.second }
                .mapIndexed { i, (tm, p) -> Row(i + 1, tm.name, "", if (p % 1.0 == 0.0) p.toInt().toString() else p.toString(), tm) }
        }
        return d to teams
    }

    private fun scoreboard() = org.json.JSONObject(Net.get("$API/site/v2/sports/racing/f1/scoreboard", timeoutMs = 12_000))

    /** The season's races, from the calendar. */
    fun races(): List<Race> {
        val cal = scoreboard().optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: return emptyList()
        return (0 until cal.length()).mapNotNull { cal.optJSONObject(it) }.map {
            Race(it.optString("label").replace(Regex("(?i)^formula 1\\s+"), "").trim(),
                Sport.time(it.optString("startDate")), Sport.time(it.optString("endDate")))
        }
    }

    /** The order right now, if a session is on (position, driver, gap). */
    fun live(): Pair<String, List<Runner>>? {
        val ev = scoreboard().optJSONArray("events") ?: return null
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            val comps = e.optJSONArray("competitions") ?: continue
            for (j in 0 until comps.length()) {
                val c = comps.getJSONObject(j)
                val st = c.optJSONObject("status")?.optJSONObject("type")
                if (st?.optString("state") != "in") continue
                val cs = c.optJSONArray("competitors") ?: continue
                val runners = (0 until cs.length()).map { cs.getJSONObject(it) }.map { r ->
                    val a = r.optJSONObject("athlete") ?: org.json.JSONObject()
                    val stats = r.optJSONArray("statistics")?.let { s -> (0 until s.length()).map { s.getJSONObject(it) } }.orEmpty()
                    val gap = stats.firstOrNull { it.optString("name").contains("behind", true) || it.optString("name").contains("gap", true) || it.optString("name").contains("interval", true) }
                        ?.optString("displayValue").orEmpty()
                    val code = a.optString("abbreviation").ifBlank { a.optString("shortName").takeLast(3).uppercase() }
                    Runner(r.optInt("order", 0), code, a.optString("displayName"), teamFor(code), gap)
                }.sortedBy { it.pos }
                val title = (e.optString("name") + "  ·  " + (c.optJSONObject("type")?.optString("abbreviation") ?: "") + "  ·  " + st.optString("shortDetail")).trim(' ', '·')
                return title to runners
            }
        }
        return null
    }
}
