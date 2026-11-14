package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Color

/**
 * Live scores in the corner, like a TV scoreboard: team colours and crests, a clock that keeps going when the
 * feed's clock sticks, and what just happened - "Try – Ireland (Lowe)", "TMO – No try", a yellow card, full time.
 * Polls every 25 s while something's live (once a minute otherwise). Matches you're watching aren't shown.
 */
object LiveScores {
    data class Card(
        val id: String, val path: String, val icon: String, val homeAbbr: String, val awayAbbr: String,
        val homeScore: String, val awayScore: String, val clock: String, val homeColor: Int, val awayColor: Int,
        val homeLogo: String?, val awayLogo: String?, val accent: Int, val finished: Boolean, val hideScore: Boolean,
    )
    /** kind: score, disallowed, yellow, red, ft. side: 0 home, 1 away, -1 neither. */
    data class Event(val id: String, val kind: String, val side: Int, val banner: String, val celebrate: Boolean, val colors: List<Int>)

    @Volatile private var lastPoll = 0L
    @Volatile private var anyLive = false
    @Volatile private var showing = false
    private val lastScores = HashMap<String, Pair<Int, Int>>()
    private val cardsSeen = HashMap<String, Int>()
    private val liveIds = HashSet<String>()
    private val ftUntil = HashMap<String, Long>()

    // Settings (Sport tab → Score Pop-ups).
    fun celebrate(c: Context) = Prefs.string(c, "sport_celebrate") ?: "all"          // all | mine | off
    fun confetti(c: Context) = Prefs.bool(c, "sport_confetti", true)
    fun hideWatching(c: Context) = Prefs.bool(c, "sport_hide_watching", true)

    /** Called every 30 s by the always-on service (and it also re-runs itself sooner while a match is live). */
    fun tick(c: Context, show: (List<Card>, List<Event>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (!Prefs.bool(c, "sport_overlay", true)) { if (showing) { showing = false; main.post { show(emptyList(), emptyList()) } }; return }
        val follows = Sport.follows(c)
        if (follows.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPoll < (if (anyLive) 24_000L else 58_000L)) return
        lastPoll = now
        Thread {
            try {
                val pairs = follows.flatMap { f -> try { Sport.todays(f).filter { Sport.isMine(f, it) }.map { it to f } } catch (e: Exception) { emptyList() } }
                    .distinctBy { it.first.id }
                val watching = if (hideWatching(c)) watchingTitle() else null
                val cards = mutableListOf<Card>()
                val events = mutableListOf<Event>()
                val noSpoilers = Sport.spoilersHidden(c)
                for ((m, f) in pairs) {
                    if (!m.teamGame) continue
                    val justEnded = m.finished && (m.id in liveIds || (ftUntil[m.id] ?: 0L) > now)
                    if (!m.live && !justEnded) continue
                    // You're watching this match: no box in the corner for it.
                    if (watching != null && listOf(m.home, m.away).any { team -> team.lowercase().split(" ").filter { it.length > 3 }.any { w -> w in watching } }) continue
                    val hc = color(m.homeColor, m.homeAbbr, m.homeAlt); val ac = color(m.awayColor, m.awayAbbr, m.awayAlt)
                    val hs = m.homeScore.toIntOrNull() ?: 0; val aws = m.awayScore.toIntOrNull() ?: 0
                    val prev = lastScores[m.id]
                    val wants = when (celebrate(c)) { "off" -> false; "mine" -> f.isTeam; else -> true }
                    if (m.live && !noSpoilers) {
                        // What happened since the last look.
                        val moments = try { Sport.moments(m) } catch (e: Exception) { emptyList() }
                        if (prev != null && (hs > prev.first || aws > prev.second)) {
                            val side = if (hs > prev.first) 0 else 1
                            val team = if (side == 0) m.home else m.away
                            val last = moments.lastOrNull { it.type in setOf("try", "goal", "penalty", "conversion", "drop") && (it.team.isBlank() || it.team.equals(team, true)) }
                            val what = when (last?.type) { "try" -> "Try"; "goal" -> "Goal"; "penalty" -> "Penalty"; "conversion" -> "Conversion"; "drop" -> "Drop goal"; else -> if (m.follow.path.startsWith("soccer")) "Goal" else "Score" }
                            val who = last?.who?.takeIf { it.isNotBlank() }?.let { " (${it.substringAfterLast(' ')})" } ?: ""
                            events += Event(m.id, "score", side, "$what – $team$who", wants && (f.isTeam.not() || team.equals(f.name, true) || celebrate(c) == "all"),
                                listOf(if (side == 0) hc else ac, Color.WHITE, if (side == 0) color(m.homeAlt, "", "") else color(m.awayAlt, "", "")))
                        } else if (prev != null && (hs < prev.first || aws < prev.second)) {
                            val side = if (hs < prev.first) 0 else 1
                            val label = if (m.follow.path.startsWith("soccer")) "VAR – Goal disallowed" else "TMO – No try"
                            events += Event(m.id, "disallowed", side, "$label · ${if (side == 0) m.home else m.away}", false, emptyList())
                        }
                        val cardsNow = moments.filter { it.type == "yellow" || it.type == "red" }
                        val seen = cardsSeen[m.id]
                        if (seen != null && cardsNow.size > seen) cardsNow.drop(seen).forEach { k ->
                            val side = if (k.team.equals(m.away, true)) 1 else if (k.team.equals(m.home, true)) 0 else -1
                            events += Event(m.id, k.type, side, (if (k.type == "red") "Red card" else "Yellow card") + " – ${k.team.ifBlank { "" }}" +
                                (k.who.takeIf { it.isNotBlank() }?.let { " (${it.substringAfterLast(' ')})" } ?: ""), false, emptyList())
                        }
                        cardsSeen[m.id] = cardsNow.size
                    }
                    if (justEnded && m.id in liveIds && !noSpoilers) {
                        val result = when { hs > aws -> "${m.home} win $hs–$aws"; aws > hs -> "${m.away} win $aws–$hs"; else -> "Draw $hs–$aws" }
                        events += Event(m.id, "ft", if (hs > aws) 0 else if (aws > hs) 1 else -1, "FT · $result", false, emptyList())
                        ftUntil[m.id] = now + 25_000L
                    }
                    lastScores[m.id] = hs to aws
                    cards += Card(m.id, m.follow.path, m.follow.icon.ifBlank { "🏆" }, m.homeAbbr, m.awayAbbr, m.homeScore.ifBlank { "0" }, m.awayScore.ifBlank { "0" },
                        if (m.finished) "FT" else clock(m, now), hc, ac, m.homeLogo, m.awayLogo, SportTableActivity.themeFor(m.follow.path).accent, m.finished, noSpoilers)
                }
                liveIds.clear(); pairs.filter { it.first.live }.forEach { liveIds += it.first.id }
                ftUntil.entries.removeAll { it.value < now }
                anyLive = liveIds.isNotEmpty()
                val list = cards.take(3)
                if (list.isNotEmpty() || showing) { showing = list.isNotEmpty(); main.post { show(list, events) } }
            } catch (e: Exception) {}
        }.start()
    }

    // Clock state per match: the last minute the feed gave and when it changed, and when the second half began.
    private val anchor = HashMap<String, Pair<Int, Long>>()
    private val lastPeriod = HashMap<String, Int>()
    private val periodStart = HashMap<String, Long>()

    /**
     * The match minute. The feed's own clock is right (it stops for injuries, TMO and half-time), so it's used as is
     * whenever it's moving. When it sticks, count on from the last minute it gave (not from kick-off), hold it while
     * play is stopped or at half-time, start the second half when the feed says it started, and show added time
     * (45+2', 80'+) rather than counting past the end of a half. Estimates get a small "~".
     */
    private fun clock(m: Sport.Match, now: Long): String {
        val d = m.detail.trim()
        val status = m.statusName.uppercase()
        val rugby = m.follow.path.startsWith("rugby")
        val half = if (rugby) 40 else 45
        // Half-time / breaks / finished: as the feed says.
        if (status.contains("HALFTIME") || Regex("(?i)^(ht|half ?time)$").matches(d)) { anchor.remove(m.id); return "HT" }
        if (status.contains("FULL_TIME") || status.contains("FINAL") || d.equals("FT", true)) return "FT"
        if (status.contains("END_PERIOD") || status.contains("END_OF")) return d.ifBlank { "Break" }
        // A new half: remember when it started.
        val prevP = lastPeriod[m.id]
        if (m.period > 0 && prevP != null && m.period != prevP) { periodStart[m.id] = now; anchor.remove(m.id) }
        if (m.period > 0) lastPeriod[m.id] = m.period
        // The feed's minute ("23'", "45'+2'", or seconds from displayClock "23:10").
        val shown = Regex("^(\\d+)'(?:\\s*\\+\\s*(\\d+))?").find(d)?.let { r -> r.groupValues[1].toInt() + (r.groupValues[2].toIntOrNull() ?: 0) }
            ?: Regex("^(\\d+):(\\d{2})").find(m.displayClock)?.groupValues?.get(1)?.toIntOrNull()
        if (shown != null) {
            val a = anchor[m.id]
            if (a == null || a.first != shown) anchor[m.id] = shown to now        // the feed moved: fresh
        }
        val stopped = status.contains("DELAY") || status.contains("SUSPEND") || status.contains("STOPPED")
        val a = anchor[m.id]
        val limit = if (m.period >= 2) half * 2 else half
        fun label(min: Int, estimated: Boolean): String {
            val txt = if (min > limit) (if (rugby) "$limit'+" else "$limit+${min - limit}'") else "$min'"
            return if (estimated) "~$txt" else txt
        }
        // Fresh from the feed (changed within the last 90 s), or play stopped: show it as is.
        if (a != null && (now - a.second < 90_000L || stopped)) return if (d.isNotBlank() && shown != null) d else label(a.first, false)
        // Stuck: count on from the last minute the feed gave.
        if (a != null) return label(a.first + ((now - a.second) / 60_000L).toInt(), true)
        // No minute at all yet: from when this half started (second half) or kick-off.
        val from = if (m.period >= 2) periodStart[m.id] else m.start
        if (from == null) return d.ifBlank { "LIVE" }
        val base = if (m.period >= 2) half else 0
        return label(base + ((now - from) / 60_000L).toInt().coerceAtLeast(0), true)
    }

    /** What's on the channel being watched right now (lower case), or null. */
    private fun watchingTitle(): String? {
        val ch = PlayerActivity.liveChannel ?: return null
        val prog = LiveGuide.guideFor(ch)?.programmes?.firstOrNull { it.isOn() } ?: return ch.lowercase()
        return (prog.title + " " + prog.desc.take(200)).lowercase()
    }

    // Team colours: the feed's own, else a sensible kit colour for the big rugby and football nations.
    private val KITS = mapOf("IRE" to 0xFF169B62, "FRA" to 0xFF1E3A8A, "RSA" to 0xFF007A4D, "SOU" to 0xFF007A4D, "NZL" to 0xFF111111, "ENG" to 0xFFE8E8E8,
        "WAL" to 0xFFC8102E, "SCO" to 0xFF1C2C5B, "ITA" to 0xFF0066B3, "AUS" to 0xFFFFCD00, "ARG" to 0xFF75AADB, "FIJ" to 0xFF68B5E8, "JPN" to 0xFFBC002D,
        "GEO" to 0xFFB5121B, "SAM" to 0xFF1C3F94, "TGA" to 0xFFC10000)

    private fun color(hex: String, abbr: String, alt: String): Int {
        KITS[abbr.uppercase()]?.let { return it.toInt() }
        for (h in listOf(hex, alt)) {
            val clean = h.trim().removePrefix("#")
            if (clean.length == 6 && clean.lowercase() !in setOf("000000", "ffffff")) try { return Color.parseColor("#$clean") } catch (e: Exception) {}
        }
        return 0xFF5A5F6B.toInt()
    }
}
