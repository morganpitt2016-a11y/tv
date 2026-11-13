package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One team: where they sit in the table, follow / unfollow, their next matches (watch or be reminded)
 * and their results (OK finds replays and highlights on Dodgy Box and the TV guide).
 */
class TeamActivity : Activity() {
    companion object {
        fun open(c: Context, path: String, team: String, abbr: String, logo: String?, id: String) =
            c.startActivity(Intent(c, TeamActivity::class.java).putExtra("path", path).putExtra("team", team)
                .putExtra("abbr", abbr).putExtra("logo", logo).putExtra("id", id))
    }

    private var path = ""
    private var team = ""
    private var abbr = ""
    private var logo: String? = null
    private var teamId = ""
    private lateinit var theme: SportTableActivity.Companion.Theme
    private lateinit var col: LinearLayout
    private lateinit var info: TextView
    private lateinit var body: LinearLayout
    private val dayFmt = SimpleDateFormat("EEE d MMM  ·  HH:mm", Locale.UK)
    private fun bold(style: Int = Typeface.BOLD) = Typeface.create(GlassUi.font(this, R.font.inter_semibold), style)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Activation.guard(this)) return
        path = intent.getStringExtra("path").orEmpty(); team = intent.getStringExtra("team").orEmpty()
        abbr = intent.getStringExtra("abbr").orEmpty(); logo = intent.getStringExtra("logo"); teamId = intent.getStringExtra("id").orEmpty()
        theme = SportTableActivity.themeFor(path)
        val c = this
        val root = FrameLayout(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, theme.bg) }
        col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 56f), dp(c, 36f), dp(c, 56f), dp(c, 60f)) }
        root.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(col) }, FrameLayout.LayoutParams(-1, -1))

        // Crest, name, competition and where they sit.
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; logo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } }, LinearLayout.LayoutParams(dp(c, 92f), dp(c, 92f)))
        val names = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        names.addView(TextView(c).apply { text = "${SportTables.iconOf(path)}  ${SportTables.nameOf(path).uppercase()}"; setTextColor(theme.accent); textSize = 12f; letterSpacing = 0.18f; typeface = bold() })
        names.addView(TextView(c).apply { text = team.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 34f; typeface = bold(Typeface.BOLD_ITALIC); includeFontPadding = false },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
        info = TextView(c).apply { text = "Loading…"; setTextColor(0xCCFFFFFF.toInt()); textSize = 14f; typeface = GlassUi.font(c, R.font.inter_medium) }
        names.addView(info, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 22f) })
        col.addView(head)

        // Actions.
        val actions = LinearLayout(c).apply { clipChildren = false }
        val followBtn = pill("") {}
        fun refreshFollow() {
            val on = following()
            followBtn.text = if (on) "✓  Following" else "+  Follow $team"
            followBtn.setOnClickListener {
                if (following()) Sport.follows(this).filter { matchesMe(it) }.forEach { Sport.remove(this, it) }
                else Sport.add(this, Sport.Follow(path, teamId.ifBlank { team }, team, team, abbr, logo, SportTables.iconOf(path)))
                GlassUi.toast(this, if (following()) "Following $team - their matches show on the Sport tab" else "Stopped following $team")
                refreshFollow()
            }
        }
        refreshFollow()
        actions.addView(followBtn, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 12f) })
        actions.addView(pill("Replays & Highlights") { search(team) }, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 12f) })
        actions.addView(pill("Full Table & Fixtures") { SportTableActivity.open(this, path, SportTables.nameOf(path)) }, LinearLayout.LayoutParams(-2, dp(c, 40f)))
        col.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 22f) })

        body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10f) })
        setContentView(root)
        UiSounds.attach(this)
        followBtn.requestFocus()
        load()
    }

    private fun matchesMe(f: Sport.Follow) = f.path == path && (f.teamId == teamId && teamId.isNotBlank() || f.name.equals(team, true))
    private fun following() = Sport.follows(this).any { matchesMe(it) }
    private fun isMe(name: String, id: String) = (teamId.isNotBlank() && id == teamId) || name.equals(team, true)

    private fun search(q: String) = startActivity(Intent(this, WatchActivity::class.java)
        .putExtra(WatchActivity.EXTRA_TAB, "search").putExtra(WatchActivity.EXTRA_QUERY, q))

    private fun load() {
        Bg.run {
            val table = try { SportTables.standings(path) } catch (e: Exception) { null }
            val season = try { SportTables.season(path) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val row = table?.flatMap { g -> g.rows.map { g to it } }?.firstOrNull { (_, r) -> isMe(r.name, r.id) }
                info.text = row?.let { (g, r) ->
                    val where = if (table.size > 1 && g.name.isNotBlank()) " in the ${g.name}" else ""
                    "${ordinal(r.rank)}$where  ·  ${r.pts} pts  ·  Played ${r.p}, won ${r.w}, drew ${r.d}, lost ${r.l}"
                } ?: if (table == null && season == null) "Couldn’t load - check the internet connection." else ""
                val mine = season.orEmpty().filter { isMe(it.home, it.homeId) || isMe(it.away, it.awayId) }
                val upcoming = mine.filter { !it.finished }.take(4)
                val results = mine.filter { it.finished }.sortedByDescending { it.start }.take(10)
                section(if (upcoming.any { it.live }) "ON NOW & NEXT" else "NEXT MATCHES", upcoming)
                section("RESULTS", results)
                if (mine.isEmpty()) body.addView(TextView(this).apply { text = "No matches in the feed right now - it may be the off-season."; setTextColor(0x99FFFFFF.toInt()); textSize = 14f },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@TeamActivity, 24f) })
            }
        }
    }

    private fun ordinal(n: Int) = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    private fun section(title: String, list: List<SportTables.Fixture>) {
        if (list.isEmpty()) return
        val c = this
        body.addView(TextView(c).apply { text = title; setTextColor(theme.accent); textSize = 13f; letterSpacing = 0.2f; typeface = bold() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 26f); bottomMargin = dp(c, 10f) })
        for (pair in list.chunked(2)) {
            val r = LinearLayout(c).apply { clipChildren = false }
            pair.forEachIndexed { i, m -> r.addView(card(m), LinearLayout.LayoutParams(0, dp(c, 74f), 1f).apply { if (i == 1) marginStart = dp(c, 16f) }) }
            if (pair.size == 1) r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 16f) })
            body.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 10f) })
        }
    }

    private fun card(m: SportTables.Fixture): View {
        val c = this
        val edge = (0x33 shl 24) or (theme.accent and 0xFFFFFF)
        val homeSide = isMe(m.home, m.homeId)
        val opp = if (homeSide) m.away else m.home
        val oppLogo = if (homeSide) m.awayLogo else m.homeLogo
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(c, 16f).toFloat(); setColor(theme.bar); setStroke(dp(c, 1f), edge) }
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.04f else 1f)
                (v.background as? GradientDrawable)?.setStroke(dp(c, if (has) 2f else 1f), if (has) theme.accent else edge) }
            setOnClickListener { if (m.finished) search("${m.home} v ${m.away}") else watchOrRemind(m) }
        }
        val hide = Sport.spoilersHidden(this)
        if (m.finished && !hide) {
            val us = (if (homeSide) m.homeScore else m.awayScore).toIntOrNull() ?: 0
            val them = (if (homeSide) m.awayScore else m.homeScore).toIntOrNull() ?: 0
            val (letter, colour) = when { us > them -> "W" to 0xFF2BB673.toInt(); us < them -> "L" to 0xFFE0453A.toInt(); else -> "D" to 0xFF8A8F98.toInt() }
            card.addView(TextView(c).apply { text = letter; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; typeface = bold()
                background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(colour) } }, LinearLayout.LayoutParams(dp(c, 30f), dp(c, 30f)).apply { marginEnd = dp(c, 12f) })
        }
        card.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; oppLogo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } }, LinearLayout.LayoutParams(dp(c, 36f), dp(c, 36f)))
        val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(c).apply {
            text = (if (homeSide) "v " else "@ ") + opp + if ((m.finished || m.live) && !hide) "   ${m.homeScore}–${m.awayScore}" else ""
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; maxLines = 1; typeface = bold() })
        txt.addView(TextView(c).apply {
            text = when { m.live -> "LIVE  ·  ${m.detail}  ·  OK to watch"; m.finished -> dayFmt.format(Date(m.start)).substringBefore("  ·") + "  ·  OK for replays & highlights"
                          else -> dayFmt.format(Date(m.start)) + "  ·  OK to watch or remind" }
            setTextColor(if (m.live) theme.accent else 0xB3FFFFFF.toInt()); textSize = 12f; maxLines = 1 })
        card.addView(txt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 12f) })
        return card
    }

    private fun watchOrRemind(m: SportTables.Fixture) {
        val app = applicationContext
        LiveGuide.load(this) { guide ->
            Bg.run {
                val follow = Sport.Follow(path, null, SportTables.nameOf(path), SportTables.nameOf(path), "", null, "")
                val asMatch = Sport.Match(m.id, m.start, "${m.home} v ${m.away}", m.home, m.away, m.homeAbbr, m.awayAbbr, m.homeScore, m.awayScore,
                    m.state, m.detail, SportTables.nameOf(path), m.homeLogo, m.awayLogo, true, follow)
                val live = Sport.channelFor(asMatch, guide.orEmpty())?.let { Sport.liveFor(app, it) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    when {
                        live == null -> GlassUi.toast(this, "Not on your channels yet - it may show on the guide closer to the day")
                        m.live || m.start - System.currentTimeMillis() < 20 * 60_000L ->
                            PlayerActivity.play(this, Xtream.liveUrl(app, live.id), live.name, live = true, subtitle = "${m.home} v ${m.away}", eyebrow = "LIVE",
                                alt = Xtream.liveUrl(app, live.id, hls = true), liveId = live.id, liveCat = live.category)
                        else -> {
                            Reminders.add(this, Reminders.Item(live.id, live.name, live.category, "${m.home} v ${m.away}", m.start - 5 * 60_000L))
                            GlassUi.toast(this, "We’ll remind you 5 minutes before kick-off (on ${live.name})")
                        }
                    }
                }
            }
        }
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = GlassUi.font(context, R.font.inter_semibold)
        gravity = Gravity.CENTER; setPadding(dp(context, 20f), 0, dp(context, 20f), 0)
        isFocusable = true; isClickable = true
        setOnClickListener { click() }
        fun style(f: Boolean) {
            background = GradientDrawable().apply { cornerRadius = dp(context, 20f).toFloat(); setColor(if (f) theme.accent else 0x1FFFFFFF) }
            setTextColor(if (f) theme.onAccent else 0xFFFFFFFF.toInt())
        }
        setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.06f else 1f); style(has) }
        style(false)
    }
}
