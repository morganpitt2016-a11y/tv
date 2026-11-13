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
 * A competition's own page, styled like the tournament's graphics: the table (one per group,
 * "NORTHERN HEMISPHERE"-style headings, rounded bars, points in a neon pill) and the fixtures
 * and results round by round ("ROUND 5 FIXTURES", crest V crest, date, time and venue).
 */
class SportTableActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "path"; const val EXTRA_NAME = "name"
        private const val ACCENT = 0xFFE3F43A.toInt()
        private const val BAR = 0xFF1C302B.toInt()
        private const val BAR_EDGE = 0x33E3F43A
        fun open(c: Context, path: String, name: String) = c.startActivity(Intent(c, SportTableActivity::class.java).putExtra(EXTRA_PATH, path).putExtra(EXTRA_NAME, name))
    }

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabs = LinkedHashMap<String, TextView>()
    private var groups: List<SportTables.Group>? = null
    private var fixtures: List<SportTables.Fixture>? = null
    private var tab = "table"
    private var path = ""
    private var compName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Activation.guard(this)) return
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        compName = intent.getStringExtra(EXTRA_NAME).orEmpty().substringBefore(" (").substringBefore(" -")
        val c = this
        val root = FrameLayout(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF13241F.toInt(), 0xFF0A1311.toInt(), 0xFF030504.toInt()))
        }
        // The tournament's swooshes, top-left and bottom-right.
        root.addView(swoosh(c), FrameLayout.LayoutParams(dp(c, 220f), dp(c, 60f), Gravity.TOP or Gravity.START).apply { topMargin = -dp(c, 14f); marginStart = -dp(c, 40f) })
        root.addView(swoosh(c), FrameLayout.LayoutParams(dp(c, 260f), dp(c, 70f), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = -dp(c, 18f); marginEnd = -dp(c, 50f) })

        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(c).apply {
            text = "◯"; setTextColor(ACCENT); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        })
        top.addView(TextView(c).apply {
            text = compName.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = GlassUi.font(c, R.font.inter_semibold); letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 10f); marginEnd = dp(c, 28f) })
        for ((id, label) in listOf("table" to "Table", "fixtures" to "Fixtures", "results" to "Results")) {
            val t = pill(label) { show(id) }
            tabs[id] = t
            top.addView(t, LinearLayout.LayoutParams(-2, dp(c, 34f)).apply { marginEnd = dp(c, 10f) })
        }
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(c, 48f), dp(c, 26f), 0, 0) })

        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 48f), dp(c, 8f), dp(c, 48f), dp(c, 60f)) }
        scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(content) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(c, 80f) })
        setContentView(root)
        UiSounds.attach(this)
        message("Loading…")
        tabs["table"]?.requestFocus()

        val app = applicationContext
        Thread {
            val g = try { SportTables.standings(path) } catch (e: Exception) { null }
            val f = try { SportTables.season(path) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                groups = g; fixtures = f
                // Opens on the table if there is one, otherwise on the fixtures.
                show(if (g.isNullOrEmpty()) "fixtures" else "table")
            }
        }.start()
    }

    // ---------- Pieces ----------

    private fun swoosh(c: Context) = View(c).apply {
        background = GradientDrawable().apply { cornerRadius = dp(c, 40f).toFloat(); setColor(ACCENT) }
        rotation = -18f; alpha = 0.9f
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13.5f; typeface = GlassUi.font(context, R.font.inter_semibold)
        gravity = Gravity.CENTER; setPadding(dp(context, 18f), 0, dp(context, 18f), 0)
        isFocusable = true; isClickable = true
        setOnClickListener { click() }
        setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.06f else 1f); styleTab(this, has) }
        styleTab(this, false)
    }

    private fun styleTab(t: TextView, focused: Boolean) {
        val selected = tabs.entries.firstOrNull { it.value === t }?.key == tab
        t.background = GradientDrawable().apply {
            cornerRadius = dp(this@SportTableActivity, 17f).toFloat()
            setColor(if (focused) ACCENT else if (selected) 0x33E3F43A else 0x14FFFFFF)
        }
        t.setTextColor(if (focused) 0xFF0A1311.toInt() else if (selected) ACCENT else 0xCCFFFFFF.toInt())
    }

    private fun message(text: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply { this.text = text; setTextColor(0x99FFFFFF.toInt()); textSize = 14f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@SportTableActivity, 30f) })
    }

    /** "NORTHERN HEMISPHERE": all but the last word in neon, the last in white, big and bold. */
    private fun bigTitle(text: String, small: String? = null): View {
        val c = this
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        small?.let { col.addView(TextView(c).apply { this.text = it.uppercase(); setTextColor(ACCENT); textSize = 12f; letterSpacing = 0.3f; typeface = GlassUi.font(c, R.font.inter_semibold) }) }
        val words = text.trim().uppercase().split(" ").filter { it.isNotBlank() }
        val first = if (words.size > 1) words.dropLast(1).joinToString(" ") else ""
        if (first.isNotEmpty()) col.addView(TextView(c).apply { this.text = first; setTextColor(ACCENT); textSize = 30f; typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD); includeFontPadding = false })
        col.addView(TextView(c).apply { this.text = words.lastOrNull().orEmpty(); setTextColor(0xFFFFFFFF.toInt()); textSize = 34f; typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD); includeFontPadding = false })
        return col
    }

    private fun show(id: String) {
        tab = id
        tabs.forEach { (_, t) -> styleTab(t, t.isFocused) }
        when (id) {
            "table" -> showTable()
            "fixtures" -> showRounds(results = false)
            "results" -> showRounds(results = true)
        }
        scroll.scrollTo(0, 0)
    }

    // ---------- Table ----------

    private fun showTable() {
        val g = groups
        if (g.isNullOrEmpty()) { message(if (g == null) "Couldn’t load the table - check the internet connection." else "No table for this competition yet."); return }
        content.removeAllViews()
        val c = this
        // Two groups side by side (like Northern and Southern Hemisphere); more stack in pairs.
        for (pair in g.chunked(2)) {
            val row = LinearLayout(c).apply { gravity = Gravity.TOP; clipChildren = false }
            pair.forEachIndexed { i, grp ->
                row.addView(table(grp), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) marginStart = dp(c, 28f) })
            }
            if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 28f) })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 14f) })
        }
    }

    private fun table(g: SportTables.Group): View {
        val c = this
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        col.addView(bigTitle(g.name.ifBlank { "Table" }), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12f) })
        val cols = listOf("P", "W", "D", "L", "PD", "BP")
        // Column heads
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 14f), 0, dp(c, 6f), 0) }
        head.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        for (h in cols) head.addView(cell(h, 11f, ACCENT, bold = true), LinearLayout.LayoutParams(dp(c, 34f), -2))
        head.addView(cell("PTS", 11f, ACCENT, bold = true), LinearLayout.LayoutParams(dp(c, 52f), -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 6f) })
        for (r in g.rows) {
            val bar = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(c, 14f).toFloat(); setColor(BAR); setStroke(dp(c, 1f), BAR_EDGE) }
                setPadding(dp(c, 14f), 0, 0, 0)
                isFocusable = true
                setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.03f else 1f)
                    (v.background as? GradientDrawable)?.setStroke(dp(c, if (has) 2f else 1f), if (has) ACCENT else BAR_EDGE) }
            }
            bar.addView(cell(r.rank.toString(), 18f, ACCENT, bold = true), LinearLayout.LayoutParams(dp(c, 26f), -2))
            bar.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; r.logo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } },
                LinearLayout.LayoutParams(dp(c, 26f), dp(c, 26f)).apply { marginStart = dp(c, 8f) })
            bar.addView(TextView(c).apply { text = r.abbr.ifBlank { r.name }.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
                typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD); maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 10f) })
            for (v in listOf(r.p, r.w, r.d, r.l, r.pd, r.bp)) bar.addView(cell(v, 14f, 0xE6FFFFFF.toInt()), LinearLayout.LayoutParams(dp(c, 34f), -2))
            // Points in the neon pill.
            bar.addView(TextView(c).apply {
                text = r.pts; gravity = Gravity.CENTER; textSize = 17f; setTextColor(0xFF0A1311.toInt()); typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD)
                background = GradientDrawable().apply { cornerRadii = floatArrayOf(0f, 0f, dp(c, 14f).toFloat(), dp(c, 14f).toFloat(), dp(c, 14f).toFloat(), dp(c, 14f).toFloat(), 0f, 0f); setColor(ACCENT) }
            }, LinearLayout.LayoutParams(dp(c, 52f), -1))
            col.addView(bar, LinearLayout.LayoutParams(-1, dp(c, 44f)).apply { bottomMargin = dp(c, 7f) })
        }
        return col
    }

    private fun cell(t: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; textSize = size; setTextColor(colour); maxLines = 1
        typeface = if (bold) Typeface.create(GlassUi.font(context, R.font.inter_semibold), Typeface.BOLD) else GlassUi.font(context, R.font.inter_medium)
    }

    // ---------- Fixtures / results by round ----------

    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)

    private fun showRounds(results: Boolean) {
        val all = fixtures
        if (all == null) { message("Couldn’t load the fixtures - check the internet connection."); return }
        val list = if (results) all.filter { it.finished } else all.filter { !it.finished }
        if (list.isEmpty()) { message(if (results) "No results yet." else "No more fixtures this season."); return }
        content.removeAllViews()
        val c = this
        val rounds = list.groupBy { it.round }.toList().let { if (results) it.reversed() else it }
        for ((round, ms) in rounds) {
            content.addView(bigTitle(if (results) "Results" else "Fixtures", small = round.ifBlank { null }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f); bottomMargin = dp(c, 12f) })
            for (pair in ms.sortedBy { it.start }.chunked(2)) {
                val row = LinearLayout(c).apply { clipChildren = false }
                pair.forEachIndexed { i, m -> row.addView(matchCard(m), LinearLayout.LayoutParams(0, dp(c, 92f), 1f).apply { if (i == 1) marginStart = dp(c, 20f) }) }
                if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 20f) })
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12f) })
            }
        }
    }

    private fun matchCard(m: SportTables.Fixture): View {
        val c = this
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(c, 18f).toFloat(); setColor(BAR); setStroke(dp(c, 1f), BAR_EDGE) }
            setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            isFocusable = true; isClickable = true
            contentDescription = "${m.home} v ${m.away}"
            setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.04f else 1f)
                (v.background as? GradientDrawable)?.setStroke(dp(c, if (has) 2f else 1f), if (has) ACCENT else BAR_EDGE) }
            setOnClickListener { openMatch(m) }
        }
        fun side(logo: String?, abbr: String) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; logo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } }, LinearLayout.LayoutParams(dp(c, 40f), dp(c, 40f)))
            addView(TextView(c).apply { text = abbr.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 11f; gravity = Gravity.CENTER; typeface = GlassUi.font(c, R.font.inter_semibold) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 3f) })
        }
        card.addView(side(m.homeLogo, m.homeAbbr), LinearLayout.LayoutParams(dp(c, 56f), -2))
        card.addView(TextView(c).apply {
            text = if (m.finished || m.live) "${m.homeScore}–${m.awayScore}" else "V"
            setTextColor(ACCENT); gravity = Gravity.CENTER
            textSize = if (m.finished || m.live) 22f else 30f
            typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD_ITALIC)
        }, LinearLayout.LayoutParams(dp(c, 76f), -2))
        card.addView(side(m.awayLogo, m.awayAbbr), LinearLayout.LayoutParams(dp(c, 56f), -2))
        // Divider, then when and where.
        card.addView(View(c).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(c, 1f), dp(c, 56f)).apply { marginStart = dp(c, 14f); marginEnd = dp(c, 14f) })
        val info = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val d = Date(m.start)
        info.addView(TextView(c).apply { text = (if (m.live) "LIVE  ·  ${m.detail}" else dayFmt.format(d)).uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD) })
        info.addView(TextView(c).apply { text = if (m.finished) "Full time" else timeFmt.format(d); setTextColor(ACCENT); textSize = 15f
            typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD) })
        if (m.venue.isNotBlank()) info.addView(TextView(c).apply { text = m.venue.uppercase(); setTextColor(0xB3FFFFFF.toInt()); textSize = 9.5f; maxLines = 2; letterSpacing = 0.03f })
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    /** On now: watch it. Coming up: a reminder on the channel it's on. */
    private fun openMatch(m: SportTables.Fixture) {
        if (m.finished) return
        val app = applicationContext
        LiveGuide.load(this) { guide ->
            Thread {
                val follow = Sport.Follow(path, null, compName, compName, "", null, "")
                val asMatch = Sport.Match(m.id, m.start, "${m.home} v ${m.away}", m.home, m.away, m.homeAbbr, m.awayAbbr, m.homeScore, m.awayScore,
                    m.state, m.detail, compName, m.homeLogo, m.awayLogo, true, follow)
                val ch = Sport.channelFor(asMatch, guide.orEmpty())
                val live = ch?.let { Sport.liveFor(app, it) }
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
            }.start()
        }
    }
}
