package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sport hub: your team's fixtures and results (from ESPN's public scores feed), which of your
 * channels each match is on (from the TV guide), and the live score while a match is on.
 */
object Sport {
    private const val API = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    val LEAGUES = listOf("eng.1" to "Premier League", "eng.2" to "Championship", "eng.3" to "League One", "eng.4" to "League Two",
        "sco.1" to "Scottish Premiership", "esp.1" to "La Liga", "ita.1" to "Serie A", "ger.1" to "Bundesliga", "fra.1" to "Ligue 1")

    data class Team(val id: String, val name: String, val short: String, val abbr: String, val logo: String?, val league: String)
    data class Match(
        val id: String, val start: Long, val home: String, val away: String, val homeAbbr: String, val awayAbbr: String,
        val homeScore: String, val awayScore: String, val state: String, val detail: String, val competition: String,
        val homeLogo: String?, val awayLogo: String?,
    ) {
        val live get() = state == "in"
        val finished get() = state == "post"
        fun scoreLine() = "$homeAbbr $homeScore–$awayScore $awayAbbr"
    }

    // ---------- Your team (per profile) ----------

    private fun key(c: Context) = ProfileManager.scope(c, "sport_team")

    fun team(c: Context): Team? = try {
        Prefs.string(c, key(c))?.let { JSONObject(it) }?.let {
            Team(it.getString("id"), it.getString("name"), it.optString("short"), it.optString("abbr"), it.optString("logo").ifBlank { null }, it.optString("league"))
        }
    } catch (e: Exception) { null }

    fun setTeam(c: Context, t: Team?) = Prefs.setString(c, key(c), t?.let {
        JSONObject().put("id", it.id).put("name", it.name).put("short", it.short).put("abbr", it.abbr).put("logo", it.logo ?: "").put("league", it.league).toString()
    })

    // ---------- Feed (call off the main thread) ----------

    fun teams(league: String): List<Team> {
        val o = JSONObject(Net.get("$API/$league/teams", timeoutMs = 12_000))
        val arr = o.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
        return (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("team") }.map {
            Team(it.getString("id"), it.optString("displayName"), it.optString("shortDisplayName"), it.optString("abbreviation"),
                it.optJSONArray("logos")?.optJSONObject(0)?.optString("href"), league)
        }.sortedBy { it.name }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val iso2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun time(s: String): Long = try { iso.parse(s)!!.time } catch (e: Exception) { try { iso2.parse(s)!!.time } catch (e2: Exception) { 0L } }

    private fun parseEvent(e: JSONObject): Match? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
        val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: return null
        val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: return null
        fun score(x: JSONObject) = x.optJSONObject("score")?.optString("displayValue") ?: x.optString("score", "")
        val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
        return Match(
            e.optString("id"), time(e.optString("date")),
            home.getJSONObject("team").optString("displayName"), away.getJSONObject("team").optString("displayName"),
            home.getJSONObject("team").optString("abbreviation"), away.getJSONObject("team").optString("abbreviation"),
            score(home).ifBlank { "0" }, score(away).ifBlank { "0" },
            st?.optString("state").orEmpty(), st?.optString("shortDetail").orEmpty(),
            e.optJSONObject("season")?.optString("slug")?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
                ?: e.optJSONObject("league")?.optString("name").orEmpty(),
            home.getJSONObject("team").optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: home.getJSONObject("team").optString("logo").ifBlank { null },
            away.getJSONObject("team").optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: away.getJSONObject("team").optString("logo").ifBlank { null },
        )
    }

    /** Upcoming matches (all competitions the feed knows for this team). */
    fun fixtures(t: Team): List<Match> {
        val o = JSONObject(Net.get("$API/${t.league}/teams/${t.id}/schedule?fixture=true", timeoutMs = 12_000))
        val ev = o.optJSONArray("events") ?: return emptyList()
        return (0 until ev.length()).mapNotNull { parseEvent(ev.getJSONObject(it)) }.filter { !it.finished }.sortedBy { it.start }
    }

    fun results(t: Team): List<Match> {
        val o = JSONObject(Net.get("$API/${t.league}/teams/${t.id}/schedule", timeoutMs = 12_000))
        val ev = o.optJSONArray("events") ?: return emptyList()
        return (0 until ev.length()).mapNotNull { parseEvent(ev.getJSONObject(it)) }.filter { it.finished }.sortedByDescending { it.start }
    }

    /** Your team's match today, if it's on now. */
    fun liveMatch(t: Team): Match? {
        val o = JSONObject(Net.get("$API/${t.league}/scoreboard", timeoutMs = 10_000))
        val ev = o.optJSONArray("events") ?: return null
        return (0 until ev.length()).mapNotNull { parseEvent(ev.getJSONObject(it)) }
            .firstOrNull { it.live && (it.home == t.name || it.away == t.name) }
    }

    // ---------- Which of your channels it's on ----------

    /** A guide channel showing this match around kick-off (by team names in the programme title). */
    fun channelFor(m: Match, guide: List<LiveGuide.Channel>): LiveGuide.Channel? {
        fun simple(s: String) = s.lowercase().replace(Regex("\\b(fc|afc|city|united|utd|town|rovers|athletic|albion|hotspur|wanderers)\\b"), "").replace(Regex("[^a-z ]"), "").trim()
        val a = simple(m.home).split(" ").filter { it.length > 2 }
        val b = simple(m.away).split(" ").filter { it.length > 2 }
        return guide.firstOrNull { ch ->
            ch.programmes.any { p ->
                p.start <= m.start + 30 * 60_000L && p.stop >= m.start + 15 * 60_000L &&
                    p.title.lowercase().let { t -> a.any { it in t } && b.any { it in t } }
            }
        }
    }

    /** The Dodgy Box channel for a guide channel (best quality). */
    fun liveFor(c: Context, ch: LiveGuide.Channel): Xtream.Live? = try {
        Xtream.best(Xtream.live(c)).firstOrNull { LiveGuide.guideFor(it.name)?.id == ch.id }
    } catch (e: Exception) { null }

    // ---------- Live score in the corner (shown by the always-on service) ----------

    @Volatile private var lastPoll = 0L
    @Volatile private var lastLine: String? = null

    /** Called every 30 s by the service: polls once a minute while your team might be playing. */
    fun tick(c: Context, show: (String?, Boolean) -> Unit) {
        if (!Prefs.bool(c, "sport_overlay", true)) { if (lastLine != null) { lastLine = null; show(null, false) }; return }
        val t = team(c) ?: return
        val now = System.currentTimeMillis()
        if (now - lastPoll < 60_000L) return
        lastPoll = now
        Thread {
            val m = try { liveMatch(t) } catch (e: Exception) { return@Thread }
            val line = m?.let { "${it.scoreLine()}  ·  ${it.detail}" }
            val goal = line != null && lastLine != null && m.scoreLine() != lastLine!!.substringBefore("  ·")
            lastLine = line
            android.os.Handler(android.os.Looper.getMainLooper()).post { show(line, goal) }
        }.start()
    }
}
