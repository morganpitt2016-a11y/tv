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
        /** Each competition's own colours - the neon green is the Nations Championship's alone. */
        class Theme(val accent: Int, val bar: Int, val bg: IntArray, val onAccent: Int, val swoosh: Boolean)
        val NATIONS = Theme(0xFFE3F43A.toInt(), 0xFF1C302B.toInt(), intArrayOf(0xFF13241F.toInt(), 0xFF0A1311.toInt(), 0xFF030504.toInt()), 0xFF0A1311.toInt(), true)
        val PREMIER = Theme(0xFF00FF85.toInt(), 0xFF2E0A38.toInt(), intArrayOf(0xFF3D0042.toInt(), 0xFF22002A.toInt(), 0xFF0C0010.toInt()), 0xFF37003C.toInt(), false)
        val F1_THEME = Theme(0xFFE10600.toInt(), 0xFF16161D.toInt(), intArrayOf(0xFF17171F.toInt(), 0xFF0C0C11.toInt(), 0xFF000000.toInt()), 0xFFFFFFFF.toInt(), false)
        val PLAIN = Theme(0xFF4DA3FF.toInt(), 0xFF1A2333.toInt(), intArrayOf(0xFF111A28.toInt(), 0xFF0A0F18.toInt(), 0xFF04060A.toInt()), 0xFF04060A.toInt(), false)
        fun themeFor(path: String) = when {
            path == "rugby/17567" -> NATIONS
            path == "soccer/eng.1" -> PREMIER
            path.startsWith("racing/f1") -> F1_THEME
            else -> PLAIN
        }
        fun open(c: Context, path: String, name: String) = c.startActivity(Intent(c, SportTableActivity::class.java).putExtra(EXTRA_PATH, path).putExtra(EXTRA_NAME, name))
    }

    private var theme = PLAIN
    private val ACCENT get() = theme.accent
    private val BAR get() = theme.bar
    private val BAR_EDGE get() = (0x33 shl 24) or (theme.accent and 0xFFFFFF)
    private val f1 get() = path.startsWith("racing/f1")
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
        theme = themeFor(path)
        val c = this
        val root = FrameLayout(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, theme.bg)
        }
        // The tournament's swooshes, top-left and bottom-right.
        if (theme.swoosh) {
            root.addView(swoosh(c), FrameLayout.LayoutParams(dp(c, 220f), dp(c, 60f), Gravity.TOP or Gravity.START).apply { topMargin = -dp(c, 14f); marginStart = -dp(c, 40f) })
            root.addView(swoosh(c), FrameLayout.LayoutParams(dp(c, 260f), dp(c, 70f), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = -dp(c, 18f); marginEnd = -dp(c, 50f) })
        }
        if (f1) {
            // F1's red racing stripes along the top.
            for ((i, h) in listOf(8f, 3f).withIndex()) root.addView(View(c).apply { setBackgroundColor(ACCENT) },
                FrameLayout.LayoutParams(-1, dp(c, h), Gravity.TOP).apply { topMargin = dp(c, 8f + i * 12f) })
        }

        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(c).apply {
            text = if (f1) "F1" else "◯"; setTextColor(if (f1) 0xFFFFFFFF.toInt() else ACCENT); textSize = if (f1) 24f else 26f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, if (f1) Typeface.BOLD_ITALIC else Typeface.BOLD)
        })
        top.addView(TextView(c).apply {
            text = compName.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = GlassUi.font(c, R.font.inter_semibold); letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 10f); marginEnd = dp(c, 28f) })
        val tabList = if (f1) listOf("drivers" to "Drivers", "teams" to "Teams", "races" to "Races", "live" to "Live")
                      else listOf("table" to "Table", "fixtures" to "Fixtures", "results" to "Results")
        for ((id, label) in tabList) {
            val t = pill(label) { show(id) }
            tabs[id] = t
            top.addView(t, LinearLayout.LayoutParams(-2, dp(c, 34f)).apply { marginEnd = dp(c, 10f) })
        }
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(c, 48f), dp(c, if (f1) 32f else 26f), 0, 0) })

        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 48f), dp(c, 8f), dp(c, 48f), dp(c, 60f)) }
        scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(content) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(c, 80f) })
        setContentView(root)
        UiSounds.attach(this)
        message("Loading…")
        tabs.values.firstOrNull()?.requestFocus()
        if (f1) { loadF1(); return }

        Bg.run {
            val g = try { SportTables.standings(path) } catch (e: Exception) { null }
            val f = try { SportTables.season(path) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                groups = g; fixtures = f
                // Opens on the table if there is one, otherwise on the fixtures.
                show(if (g.isNullOrEmpty()) "fixtures" else "table")
            }
        }
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
            setColor(if (focused) ACCENT else if (selected) BAR_EDGE else 0x14FFFFFF)
        }
        t.setTextColor(if (focused) theme.onAccent else if (selected) (if (f1) 0xFFFFFFFF.toInt() else ACCENT) else 0xCCFFFFFF.toInt())
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
            "drivers" -> showF1Table(drivers = true)
            "teams" -> showF1Table(drivers = false)
            "races" -> showF1Races()
            "live" -> showF1Live()
            "table" -> showTable()
            "fixtures" -> showRounds(results = false)
            "results" -> showRounds(results = true)
        }
        scroll.scrollTo(0, 0)
    }

    // ---------- Formula 1 ----------

    private var f1Drivers: List<F1.Row>? = null
    private var f1Teams: List<F1.Row>? = null
    private var f1Races: List<F1.Race>? = null
    private val year get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private fun f1Font(style: Int) = Typeface.create(GlassUi.font(this, R.font.inter_semibold), style)

    private fun loadF1() {
        Bg.run {
            val st = try { F1.standings() } catch (e: Exception) { null }
            val races = try { F1.races() } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                f1Drivers = st?.first; f1Teams = st?.second; f1Races = races
                show(if (tab in listOf("drivers", "teams", "races", "live")) tab else "drivers")
            }
        }
    }

    private fun f1Heading(text: String) = TextView(this).apply {
        this.text = text; setTextColor(0xFFFFFFFF.toInt()); textSize = 30f; gravity = Gravity.CENTER
        typeface = f1Font(Typeface.BOLD_ITALIC)
    }

    /** "2026 DRIVER STANDINGS": position, a bar in the team's colour with the name, points in a red cell. */
    private fun showF1Table(drivers: Boolean) {
        val list = if (drivers) f1Drivers else f1Teams
        if (list.isNullOrEmpty()) { message(if (f1Drivers == null) "Couldn’t load the standings - check the internet connection." else "No standings yet."); return }
        content.removeAllViews()
        val c = this
        content.addView(f1Heading("$year ${if (drivers) "DRIVER" else "TEAM"} STANDINGS"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 14f) })
        // Two columns, like the broadcast graphic: the top half on the left, the rest on the right.
        val half = (list.size + 1) / 2
        val cols = LinearLayout(c).apply { clipChildren = false }
        for ((i, part) in listOf(list.take(half), list.drop(half)).withIndex()) {
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            for (r in part) col.addView(f1Row(r, drivers), LinearLayout.LayoutParams(-1, dp(c, 38f)).apply { bottomMargin = dp(c, 4f) })
            cols.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) marginStart = dp(c, 28f) })
        }
        content.addView(cols, LinearLayout.LayoutParams(-1, -2))
    }

    private fun f1Row(r: F1.Row, driver: Boolean): View {
        val c = this
        val team = r.team
        val ink = if (team?.dark == true) 0xFF101014.toInt() else 0xFFFFFFFF.toInt()
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF0E0E14.toInt())
            isFocusable = true
            setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.03f else 1f) }
        }
        row.addView(TextView(c).apply { text = r.rank.toString(); setTextColor(0xFFFFFFFF.toInt()); textSize = 17f; gravity = Gravity.CENTER
            typeface = f1Font(Typeface.BOLD) }, LinearLayout.LayoutParams(dp(c, 42f), -1))
        val bar = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(team?.colour ?: 0xFF3A3A44.toInt()); setPadding(dp(c, 14f), 0, dp(c, 10f), 0)
        }
        bar.addView(TextView(c).apply {
            text = (if (driver) r.name.substringAfterLast(' ') else r.name).uppercase(); setTextColor(ink); textSize = 17f; maxLines = 1
            typeface = f1Font(Typeface.BOLD_ITALIC)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (driver && team != null) bar.addView(TextView(c).apply {
            text = team.name.uppercase(); setTextColor(if (team.dark) 0xB3101014.toInt() else 0xC0FFFFFF.toInt()); textSize = 10f; letterSpacing = 0.06f; maxLines = 1
            typeface = f1Font(Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2))
        row.addView(bar, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(TextView(c).apply { text = r.pts; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; gravity = Gravity.CENTER; setBackgroundColor(ACCENT)
            typeface = f1Font(Typeface.BOLD_ITALIC) }, LinearLayout.LayoutParams(dp(c, 74f), -1))
        return row
    }

    private val raceDay = SimpleDateFormat("d MMM", Locale.UK)

    private fun showF1Races() {
        val races = f1Races
        if (races.isNullOrEmpty()) { message("Couldn’t load the race calendar - check the internet connection."); return }
        content.removeAllViews()
        val c = this
        val now = System.currentTimeMillis()
        val nextIdx = races.indexOfFirst { it.end > now }
        content.addView(f1Heading("$year RACE CALENDAR"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 14f) })
        var focusMe: View? = null
        for (pair in races.withIndex().chunked(2)) {
            val row = LinearLayout(c).apply { clipChildren = false }
            pair.forEachIndexed { k, (i, r) ->
                val done = r.end < now
                val next = i == nextIdx
                val card = LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 14f), 0, dp(c, 14f), 0)
                    setBackgroundColor(if (next) ACCENT else 0xFF16161D.toInt()); alpha = if (done) 0.55f else 1f
                    isFocusable = true; isClickable = true
                    setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.04f else 1f) }
                    setOnClickListener { if (done) GlassUi.toast(this@SportTableActivity, "That race has been run - see the standings") else remindRace(r) }
                }
                if (next) focusMe = card
                card.addView(TextView(c).apply { text = "R${i + 1}"; setTextColor(if (next) 0xFFFFFFFF.toInt() else ACCENT); textSize = 16f
                    typeface = f1Font(Typeface.BOLD_ITALIC) }, LinearLayout.LayoutParams(dp(c, 46f), -2))
                card.addView(TextView(c).apply { text = r.name.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 13.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = f1Font(Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(TextView(c).apply { text = (if (next) "NEXT · " else "") + raceDay.format(Date(r.start)) + " – " + raceDay.format(Date(r.end))
                    setTextColor(0xE6FFFFFF.toInt()); textSize = 11.5f }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 8f) })
                row.addView(card, LinearLayout.LayoutParams(0, dp(c, 46f), 1f).apply { if (k == 1) marginStart = dp(c, 16f) })
            }
            if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 16f) })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8f) })
        }
        focusMe?.let { v -> v.post { scroll.smoothScrollTo(0, ((v.parent as View).top - dp(c, 120f)).coerceAtLeast(0)) } }
    }

    /** OK on a race: a reminder before the race on whichever channel the guide has it on. */
    private fun remindRace(r: F1.Race) {
        val app = applicationContext
        LiveGuide.load(this) { guide ->
            Bg.run {
                val raceStart = r.end - 2 * 3_600_000L
                val follow = Sport.Follow(path, null, "Formula 1", "F1", "", null, "")
                val m = Sport.Match("f1_${r.start}", raceStart, r.name, "", "", "", "", "", "", "pre", "", "Formula 1", null, null, false, follow)
                val ch = Sport.channelFor(m, guide.orEmpty())
                val live = ch?.let { Sport.liveFor(app, it) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (live == null) GlassUi.toast(this, "Not on your channels yet - it shows on the guide nearer race weekend")
                    else {
                        Reminders.add(this, Reminders.Item(live.id, live.name, live.category, r.name, raceStart - 10 * 60_000L))
                        GlassUi.toast(this, "We’ll remind you before the race (on ${live.name})")
                    }
                }
            }
        }
    }

    /** The timing tower while a session's running: position, team colour, driver code, gap. */
    private fun showF1Live() {
        message("Checking for a session on track…")
        Bg.run {
            val live = try { F1.live() } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed || tab != "live") return@runOnUiThread
                if (live == null) {
                    val next = f1Races?.firstOrNull { it.end > System.currentTimeMillis() }
                    message("Nothing on track right now." + (next?.let { "\nNext: ${it.name}, ${raceDay.format(Date(it.start))} – ${raceDay.format(Date(it.end))}" } ?: ""))
                    return@runOnUiThread
                }
                content.removeAllViews()
                val c = this
                val tower = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF20E0E14.toInt()) }
                tower.addView(TextView(c).apply { text = live.first.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER
                    setBackgroundColor(ACCENT); typeface = f1Font(Typeface.BOLD_ITALIC); setPadding(dp(c, 10f), dp(c, 6f), dp(c, 10f), dp(c, 6f)) })
                for (r in live.second) {
                    val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; isFocusable = true
                        setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) 0x33FFFFFF else 0) } }
                    row.addView(TextView(c).apply { text = r.pos.toString(); gravity = Gravity.CENTER; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                        typeface = f1Font(Typeface.BOLD) }, LinearLayout.LayoutParams(dp(c, 36f), -1))
                    row.addView(View(c).apply { setBackgroundColor(r.team?.colour ?: 0xFF555555.toInt()) }, LinearLayout.LayoutParams(dp(c, 5f), dp(c, 18f)).apply { marginStart = dp(c, 6f) })
                    row.addView(TextView(c).apply { text = r.code; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); typeface = f1Font(Typeface.BOLD) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 10f) })
                    row.addView(TextView(c).apply { text = if (r.pos == 1) "Leader" else r.gap; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.END },
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 12f) })
                    tower.addView(row, LinearLayout.LayoutParams(-1, dp(c, 30f)))
                }
                content.addView(tower, LinearLayout.LayoutParams(dp(c, 260f), -2))
            }
        }
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
                text = r.pts; gravity = Gravity.CENTER; textSize = 17f; setTextColor(theme.onAccent); typeface = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD)
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
            Bg.run {
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
            }
        }
    }
}
