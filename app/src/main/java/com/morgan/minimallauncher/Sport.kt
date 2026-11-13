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
    private fun time(s: String): Long = listOf(isoA, isoB, isoC).firstNotNullOfOrNull { f -> try { synchronized(f) { f.parse(s)?.time } } catch (e: Exception) { null } } ?: 0L
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
            team(home).optString("abbreviation"), team(away).optString("abbreviation"),
            score(home).ifBlank { "0" }, score(away).ifBlank { "0" },
            st?.optString("state").orEmpty(), st?.optString("shortDetail").orEmpty(),
            e.optJSONObject("season")?.optString("slug")?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: f.name,
            logo(home), logo(away), teamGame, f,
        )
    }

    private fun events(o: JSONObject) = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /** Everything for one follow: upcoming, finished and live (team schedule, or the competition's calendar). */
    fun matches(f: Follow): List<Match> {
        val now = System.currentTimeMillis()
        val list = if (f.isTeam) {
            (events(JSONObject(Net.get("$ROOT/${f.path}/teams/${f.teamId}/schedule?fixture=true", timeoutMs = 12_000))) +
                events(JSONObject(Net.get("$ROOT/${f.path}/teams/${f.teamId}/schedule", timeoutMs = 12_000))))
                .mapNotNull { parseEvent(it, f) }
        } else {
            val range = day.format(java.util.Date(now - 14 * 86_400_000L)) + "-" + day.format(java.util.Date(now + 60 * 86_400_000L))
            events(JSONObject(Net.get("$ROOT/${f.path}/scoreboard?dates=$range&limit=200", timeoutMs = 15_000))).mapNotNull { parseEvent(it, f) }
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
                val line = "${m.follow.icon} ${m.scoreLine()}  ·  ${m.detail}"
                if (m.teamGame && lastLines[m.id]?.substringBefore("  ·") != null && lastLines[m.id]!!.substringBefore("  ·") != line.substringBefore("  ·")) goal = true
                lastLines[m.id] = line
                line
            }
            val text = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            android.os.Handler(android.os.Looper.getMainLooper()).post { show(text, goal) }
        }.start()
    }
}
