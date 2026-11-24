package com.morgan.minimallauncher

import com.morgan.minimallauncher.WatchActivity.Row
import com.morgan.minimallauncher.WatchActivity.Holder
import com.morgan.minimallauncher.WatchActivity.CardAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.animation.ValueAnimator
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp
import java.util.Date

// Part of the Watch screen (WatchActivity), kept in its own file to keep each piece small.

// ---------- Sport ----------

internal fun WatchActivity.buildSport(token: Int) {
    val follows = Sport.follows(this)
    val heads = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; tag = "heads" }
    fun head(label: String, primary: Boolean = false, click: () -> Unit) =
        heads.addView(pillButton(label, primary, click), LinearLayout.LayoutParams(-2, dp(this, 38f)).apply { marginEnd = dp(this@buildSport, 12f) })
    if (follows.isEmpty()) {
        showPlainHero("SPORT", "Follow Your Sport", "Follow any teams and sports - football, F1, rugby, NFL, NBA, golf, tennis, UFC. See what’s coming up, results, which of your channels it’s on, and live scores in the corner.")
        head("Add a Team or Sport", primary = true) { addFollow() }
        rows.addView(heads, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildSport, 56f); topMargin = dp(this@buildSport, 8f) })
        return
    }
    showPlainHero("SPORT", "Your Sport", "What’s on now, what’s coming up, tables and where to watch.")
    setHeroImage(null)
    val heroPaths = follows.map { it.path }.distinct()
    Bg.run {
        val img = Sport.newsImage(heroPaths)
        runOnUiThread { if (tab == "sport" && !isDestroyed && img != null) setHeroImage(img) }
    }
    head("Add") { addFollow() }
    head("Following (${follows.size})") { manageFollows() }
    head("Tables & Fixtures", primary = true) {
        val comps = follows.distinctBy { it.path }
        val names = Sport.GROUPS.flatMap { it.second }.associate { it.path to it.name.substringBefore(" (").substringBefore(" -") }
        if (comps.size == 1) SportTableActivity.open(this, comps[0].path, names[comps[0].path] ?: comps[0].name)
        else {
            val d = GlassDialog(this).title("Which Competition?").scrolling()
            for (f in comps) d.button("${f.icon}  ${names[f.path] ?: f.name}") { SportTableActivity.open(this, f.path, names[f.path] ?: f.name) }
            d.button("Cancel").show()
        }
    }
    head("Choose Tables") { chooseTables() }
    val hidden = Sport.spoilersHidden(this)
    head(if (hidden) "Scores: Hidden" else "Scores: Shown") {
        Sport.setSpoilersHidden(this, !hidden)
        GlassUi.toast(this, if (!hidden) "Scores hidden - no spoilers until you’ve watched" else "Scores shown")
        showTab("sport", focusContent = false)
    }
    val overlayOn = Prefs.bool(this, "sport_overlay", true)
    head(if (overlayOn) "Live Scores in Corner: On" else "Live Scores in Corner: Off") {
        Prefs.setBool(this, "sport_overlay", !overlayOn); showTab("sport", focusContent = false)
    }
    head("Score Pop-ups") { scorePopups() }
    rows.addView(heads, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildSport, 56f); topMargin = dp(this@buildSport, 8f) })
    sportTables(token)?.let { rows.addView(it) }
    val loading = GlassUi.text(this, "Loading…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
    rows.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildSport, 56f); topMargin = dp(this@buildSport, 14f) })
    val host = rows
    LiveGuide.load(this) { guide ->
        Bg.run {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val parts = follows.map { f -> pool.submit<List<Sport.Match>> { try { Sport.matches(f) } catch (e: Exception) { emptyList() } } }
            val all = parts.flatMap { it.get() }.distinctBy { it.id }
            pool.shutdown()
            val now = System.currentTimeMillis()
            val live = all.filter { it.live }.sortedBy { it.start }
            val next = all.filter { !it.live && !it.finished && it.start > now - 3 * 3_600_000L }.sortedBy { it.start }.take(25)
            val done = all.filter { it.finished }.sortedByDescending { it.start }.take(20)
            val g = guide.orEmpty()
            val channels = (live + next.take(15)).associate { m -> m.id to Sport.channelFor(m, g) }
            runOnUiThread {
                if (containerToken[host] != token || isDestroyed) return@runOnUiThread
                host.removeView(loading)
                if (all.isEmpty()) {
                    host.addView(GlassUi.text(this, "Nothing to show yet - check the internet connection, or it may be the off-season.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildSport, 56f); topMargin = dp(this@buildSport, 14f) })
                    return@runOnUiThread
                }
                fun matchRow(key: String, title: String, list: List<Sport.Match>) {
                    if (list.isEmpty()) return
                    val r = row(key, title, height = 150f)
                    r.list.adapter = CardAdapter(list, { p -> wideCard(p) }) { h, m -> bindMatch(h, m, channels[m.id]) }
                    host.addView(r.view)
                }
                matchRow("sp_live", "On Now", live)
                matchRow("sp_fix", "Coming Up", next)
                matchRow("sp_res", "Results", done)
                (live.firstOrNull() ?: next.firstOrNull())?.let { showMatchHero(it, channels[it.id]) }
            }
        }
    }
}

internal fun WatchActivity.bindMatch(h: Holder, m: Sport.Match, ch: LiveGuide.Channel?) {
    val f = m.follow
    val hide = Sport.spoilersHidden(this)
    h.label.text = (if ((m.finished || m.live) && m.teamGame && !hide) m.scoreLine() else m.title)
    val whenText = matchDay.format(java.util.Date(m.start)).replace("AM", "am").replace("PM", "pm")
    h.sub?.text = "${f.icon}  " + when {
        m.live -> "LIVE ${m.detail}" + (ch?.let { "  ·  ${it.name}" } ?: "")
        m.finished -> whenText.substringBefore("  ·") + (if (!m.teamGame && m.detail.isNotBlank()) "  ·  ${m.detail}" else "")
        else -> whenText + "  ·  " + (ch?.name ?: "Not on your channels yet")
    }
    h.image.scaleType = ImageView.ScaleType.FIT_CENTER
    val pad = dp(this, 26f)
    h.image.setPadding(pad, pad / 2, pad, pad)
    val logo = if (m.teamGame) (if (f.isTeam && m.home == f.name) m.awayLogo else m.homeLogo) ?: f.logo else f.logo
    if (logo != null) ImageLoader.load(h.image, logo, ImageLoader.ICON) else h.image.setImageDrawable(null)
    h.progress?.let { it.layoutParams.width = 0 }
    h.root.contentDescription = h.label.text
    h.root.setOnClickListener { openMatch(m, ch) }
    focusEffect(h.root) { showMatchHero(m, ch) }
}

internal fun WatchActivity.showMatchHero(m: Sport.Match, ch: LiveGuide.Channel?) = showPlainHero(
    (if (m.live) "LIVE  ·  ${m.detail}" else if (m.finished) "RESULT" else "COMING UP") + "  ·  " + m.follow.name.uppercase(),
    if ((m.finished || m.live) && m.teamGame && !Sport.spoilersHidden(this)) m.scoreLine() else m.title,
    when {
        m.finished -> m.detail.ifBlank { m.competition }
        ch != null -> "On ${ch.name}" + if (m.live) " - press OK to watch" else " - press OK to be reminded"
        else -> "Not on your channels yet. It may appear on the guide closer to the day."
    })

internal fun WatchActivity.openMatch(m: Sport.Match, ch: LiveGuide.Channel?) {
    if (m.finished) { SportTableActivity.open(this, m.follow.path, Sport.GROUPS.flatMap { it.second }.firstOrNull { it.path == m.follow.path }?.name?.substringBefore(" (")?.substringBefore(" -") ?: m.follow.name); return }
    if (ch == null) { GlassUi.toast(this, "Not on your channels (yet)"); return }
    val app = applicationContext
    Bg.run {
        val live = Sport.liveFor(app, ch)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (live == null) { GlassUi.toast(this, "Couldn’t find ${ch.name} on Dodgy Box"); return@runOnUiThread }
            val soon = m.start - System.currentTimeMillis() < 20 * 60_000L
            if (m.live && !Sport.spoilersHidden(this)) catchUp(m) { playLive(live) }
            else if (m.live || soon) playLive(live)
            else {
                Reminders.add(this, Reminders.Item(live.id, live.name, live.category, m.title, m.start - 5 * 60_000L))
                GlassUi.toast(this, "We’ll remind you 5 minutes before it starts (on ${live.name})")
            }
        }
    }
}

/** Joining a match that's under way: the key moments so far first, then live (like Apple's Catch Up). */
internal fun WatchActivity.catchUp(m: Sport.Match, watch: () -> Unit) {
    Bg.run {
        val moments = Sport.keyMoments(m)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (moments.isEmpty()) { watch(); return@runOnUiThread }
            GlassDialog(this).title("Catch Up · ${m.scoreLine()}").message("${m.detail}\n\n" + moments.joinToString("\n"))
                .button("Watch Live", GlassDialog.Style.BOLD) { watch() }
                .button("Cancel").show()
        }
    }
}

/** Add a team, or a whole sport (F1, golf, tennis...). */
internal fun WatchActivity.addFollow() {
    val d = GlassDialog(this).title("Which Sport?").scrolling()
    for ((group, choices) in Sport.GROUPS) d.button(group) {
        val c = GlassDialog(this).title(group).scrolling()
        for (ch in choices) c.button("${ch.icon}  ${ch.name}") {
            if (!ch.teams) {
                Sport.add(this, Sport.Follow(ch.path, null, ch.name, ch.name, "", null, ch.icon))
                GlassUi.toast(this, "Following ${ch.name}"); tabViews.remove("sport"); showTab("sport", focusContent = true)
                return@button
            }
            GlassUi.toast(this, "Loading teams…")
            Bg.run {
                val teams = try { Sport.teams(ch) } catch (e: Exception) { null }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (teams.isNullOrEmpty()) { GlassUi.toast(this, "Couldn’t load the teams - try again"); return@runOnUiThread }
                    val t = GlassDialog(this).title(ch.name).scrolling()
                    for (team in teams) t.button(team.name) {
                        Sport.add(this, team); GlassUi.toast(this, "Following ${team.name}"); showTab("sport", focusContent = true)
                    }
                    t.button("Cancel").show()
                }
            }
        }
        c.button("Cancel").show()
    }
    d.button("Cancel").show()
}

/** How the corner scoreboard behaves: celebrations, confetti, hiding the match you're watching. */
internal fun WatchActivity.scorePopups() {
    val cel = LiveScores.celebrate(this)
    fun name(v: String) = when (v) { "mine" -> "Only My Teams"; "off" -> "Off"; else -> "All Scores" }
    GlassDialog(this).title("Score Pop-ups").message("Team-coloured live scores in the corner, with what just happened - tries, goals, TMO and VAR calls, cards and full time.")
        .button("Celebrations · ${name(cel)}") {
            val next = when (cel) { "all" -> "mine"; "mine" -> "off"; else -> "all" }
            Prefs.setString(this, "sport_celebrate", next); scorePopups()
        }
        .toggle("Confetti When Someone Scores", LiveScores.confetti(this)) { Prefs.setBool(this, "sport_confetti", !LiveScores.confetti(this)) }
        .toggle("Hide the Match I’m Watching", LiveScores.hideWatching(this)) { Prefs.setBool(this, "sport_hide_watching", !LiveScores.hideWatching(this)) }
        .button("Done", GlassDialog.Style.BOLD).show()
}

/** Pick which league tables appear on the Sport tab. */
internal fun WatchActivity.chooseTables() {
    val picked = SportTables.picked(this).toMutableList()
    val d = GlassDialog(this).title("Tables on the Sport Tab").message("Tick the tables you want to see. Open a table to pick a team and see their page.").scrolling()
    for (ch in SportTables.tableChoices()) d.toggle("${ch.icon}  ${SportTables.nameOf(ch.path)}", ch.path in picked) {
        if (ch.path in picked) picked.remove(ch.path) else picked.add(ch.path)
    }
    d.button("Done", GlassDialog.Style.BOLD) { SportTables.setPicked(this, picked); showTab("sport", focusContent = false) }.show()
}

/** The chosen tables, side by side: each row opens that team; the heading opens the full table. */
internal fun WatchActivity.sportTables(token: Int): View? {
    val paths = SportTables.picked(this)
    if (paths.isEmpty()) return null
    val c = this
    val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; tag = "sp_tables" }
    box.addView(GlassUi.text(c, "Tables", 15f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, 16f) })
    val strip = LinearLayout(c).apply { clipChildren = false; clipToPadding = false; setPadding(dp(c, 56f), dp(c, 12f), dp(c, 56f), dp(c, 8f)) }
    val hs = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip) }
    box.addView(hs, LinearLayout.LayoutParams(-1, -2))
    val host = rows
    Bg.run {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val parts = paths.map { p -> pool.submit<List<SportTables.Group>> {
            try {
                if (p.startsWith("racing/f1")) F1.standings().first.take(10).let { d -> listOf(SportTables.Group("Drivers", d.map { r ->
                    SportTables.Row(r.rank, r.name, r.code, null, "", "", "", "", "", "", r.pts) })) }
                else SportTables.standings(p)
            } catch (e: Exception) { emptyList() }
        } }
        val tables = paths.zip(parts.map { it.get() })
        pool.shutdown()
        runOnUiThread {
            if (containerToken[host] != token || isDestroyed) return@runOnUiThread
            val seen = HashSet<String>()
            for ((p, groups) in tables) for (g in groups) {
                // The feed sometimes gives one competition's table for another: show each table once.
                if (!seen.add(g.rows.joinToString(",") { it.name + it.pts })) continue
                strip.addView(miniTable(p, g, groups.size > 1), LinearLayout.LayoutParams(dp(c, 300f), -2).apply { marginEnd = dp(c, 16f) })
            }
            if (strip.childCount == 0) box.visibility = View.GONE
        }
    }
    return box
}

/** A league table card in the style of Apple Sports: dark glass, a thin colour line, clean numbers. */
internal fun WatchActivity.miniTable(path: String, g: SportTables.Group, showGroup: Boolean): View {
    val c = this
    val theme = SportTableActivity.themeFor(path)
    val f1 = path.startsWith("racing/f1")
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false
        background = GradientDrawable().apply { cornerRadius = dp(c, 18f).toFloat(); setColor(0xE614151A.toInt()); setStroke(dp(c, 1f), 0x1AFFFFFF) }
        setPadding(dp(c, 16f), dp(c, 14f), dp(c, 16f), dp(c, 12f))
        isFocusable = true; isClickable = true
        setOnClickListener { SportTableActivity.open(c, path, SportTables.nameOf(path)) }
        setOnFocusChangeListener { v, has ->
            Motion.focus(v, has, 1.04f)
            (v.background as? GradientDrawable)?.setStroke(dp(c, if (has) 2f else 1f), if (has) 0xE6FFFFFF.toInt() else 0x1AFFFFFF)
        }
    }
    card.addView(View(c).apply { background = GradientDrawable().apply { cornerRadius = dp(c, 2f).toFloat(); setColor(theme.accent) } },
        LinearLayout.LayoutParams(dp(c, 28f), dp(c, 4f)).apply { bottomMargin = dp(c, 10f) })
    card.addView(GlassUi.text(c, SportTables.nameOf(path), 16f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    val sub = when {
        f1 -> "Drivers"
        showGroup && g.name.isNotBlank() -> g.name.replace(Regex("(?i)\\b(20\\d\\d(-\\d\\d)?)\\b"), "").trim()
        else -> ""
    }
    if (sub.isNotBlank()) card.addView(GlassUi.text(c, sub, 12f, R.font.inter_medium, 0x8CFFFFFF.toInt()).apply { maxLines = 1 })
    fun num(t: String, colour: Int = 0xB3FFFFFF.toInt(), bold: Boolean = false) = GlassUi.text(c, t, 12.5f, if (bold) R.font.inter_semibold else R.font.inter_medium, colour).apply {
        gravity = Gravity.END; maxLines = 1; fontFeatureSettings = "tnum"
    }
    val hdr = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
    hdr.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
    if (!f1) { hdr.addView(num("P", 0x66FFFFFF), LinearLayout.LayoutParams(dp(c, 26f), -2)); hdr.addView(num("GD".takeIf { path.startsWith("soccer") } ?: "PD", 0x66FFFFFF), LinearLayout.LayoutParams(dp(c, 38f), -2)) }
    hdr.addView(num("PTS", 0x66FFFFFF), LinearLayout.LayoutParams(dp(c, 38f), -2))
    card.addView(hdr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12f); bottomMargin = dp(c, 4f) })
    val shown = g.rows.take(8)
    shown.forEachIndexed { i, r ->
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(num(r.rank.toString(), 0x8CFFFFFF.toInt()).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(dp(c, 22f), -2))
        if (!f1) row.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; r.logo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } },
            LinearLayout.LayoutParams(dp(c, 20f), dp(c, 20f)).apply { marginEnd = dp(c, 10f) })
        else row.addView(View(c).apply { setBackgroundColor(F1.teamFor(r.abbr)?.colour ?: 0x55FFFFFF) }, LinearLayout.LayoutParams(dp(c, 3f), dp(c, 16f)).apply { marginEnd = dp(c, 10f) })
        val nm = if (f1) r.name.substringAfterLast(' ') else r.name.replace(" & Hove Albion", "").replace("Wolverhampton Wanderers", "Wolves").ifBlank { r.abbr }
        row.addView(GlassUi.text(c, nm, 13.5f, R.font.inter_medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!f1) { row.addView(num(r.p), LinearLayout.LayoutParams(dp(c, 26f), -2)); row.addView(num(r.pd), LinearLayout.LayoutParams(dp(c, 38f), -2)) }
        row.addView(num(r.pts, 0xFFFFFFFF.toInt(), bold = true), LinearLayout.LayoutParams(dp(c, 38f), -2))
        card.addView(row, LinearLayout.LayoutParams(-1, dp(c, 30f)))
        if (i < shown.size - 1) card.addView(View(c).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
    }
    card.addView(GlassUi.text(c, if (g.rows.size > shown.size) "Full table  ›" else "Table & fixtures  ›", 12f, R.font.inter_semibold, 0x99FFFFFF.toInt()),
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 10f) })
    return card
}

internal fun WatchActivity.manageFollows() {
    val list = Sport.follows(this)
    val d = GlassDialog(this).title("Following").message("Choose one to stop following it.").scrolling()
    for (f in list) d.button("${f.icon}  ${f.name}") {
        GlassDialog(this).title(f.name).button("Stop Following", GlassDialog.Style.DESTRUCTIVE) {
            Sport.remove(this, f)
            showTab("sport", focusContent = false)
            GlassUi.toast(this, "Stopped following ${f.name}")
            // Stay on Sport: the button you pressed is rebuilt, so focus would otherwise fall to the first tab (Home) and open it.
            val keep = { nav.findViewWithTag<View>("sport")?.requestFocus() }
            keep(); handler.post { keep() }; handler.postDelayed({ keep() }, 120)
            // Back to the list, to stop following another (or Done).
            if (Sport.follows(this).isNotEmpty()) handler.postDelayed({ if (tab == "sport" && !isFinishing) manageFollows() }, 250)
        }.button("Cancel").show()
    }
    d.button("Add a Team or Sport", GlassDialog.Style.BOLD) { addFollow() }
    d.button("Done").show()
}

internal fun WatchActivity.downloadsRow(): View? {
    val items = Downloads.all(this)
    if (items.isEmpty()) return null
    val r = row("downloads", "Downloads", height = 150f)
    r.list.adapter = CardAdapter(items, { p -> wideCard(p) }) { h, d ->
        val (st, ready) = Downloads.status(this, d)
        h.label.text = d.label
        h.sub?.text = st
        h.progress?.visibility = View.GONE
        ImageLoader.load(h.image, d.image, ImageLoader.CARD)
        h.root.contentDescription = d.label
        h.root.setOnClickListener {
            if (!ready) { GlassUi.toast(this, st); return@setOnClickListener }
            PlayerActivity.play(this, android.net.Uri.fromFile(java.io.File(d.file)).toString(), d.series ?: d.title, live = false,
                subtitle = if (d.series != null) "Season ${d.season}, Episode ${d.episode}" else null, eyebrow = "DOWNLOADED",
                series = d.series, season = d.season, episode = d.episode, image = d.image)
        }
        h.root.setOnLongClickListener {
            GlassDialog(this).title(d.label).message(st)
                .button("Delete Download", GlassDialog.Style.DESTRUCTIVE) { Downloads.delete(this, d); showTab(tab, focusContent = true) }
                .button("Cancel").show()
            true
        }
        focusEffect(h.root) { showPlainHero("DOWNLOADED", d.label, if (ready) "Plays from the TV itself - no buffering." else st) }
    }
    return r.view
}

internal fun WatchActivity.buildList() {
    showPlainHero("MY STUFF", "Everything You’re Watching", "Continue Watching, your list, reminders, downloads and recordings - all in one place.")
    // Its own picture (what you're watching most recently), not the last tab's.
    setHeroImage(ResumeStore.forTab(this, "home").firstOrNull()?.image)
    ResumeStore.forTab(this, "home").takeIf { it.isNotEmpty() }?.let { items ->
        val r = row("resume_ms", "Continue Watching", height = 150f)
        r.list.adapter = CardAdapter(items, { p -> wideCard(p) }) { h, item -> bindResume(h, item) }
        rows.addView(r.view)
    }
    DodgyAlerts.all(this).takeIf { it.isNotEmpty() }?.let { wants ->
        rows.addView(staticTitleRow("wants", "Remind Me · Waiting for Dodgy Box", wants.map {
            WatchCatalog.Title(it.id, it.type, it.name, it.year, it.poster, null, "", null, emptyList(), emptyList()) }).view)
    }
    downloadsRow()?.let { rows.addView(it) }
    recordingsRow("recordings")?.let { rows.addView(it) }
    val list = WatchCatalog.myList(this)
    if (list.isEmpty() && rows.childCount > 0) return
    if (list.isEmpty()) {
        showPlainHero("MY STUFF", "Nothing here yet", "Open any film or show and choose “+ My List” or “Remind Me”. What you’re watching shows here too.")
        rows.addView(GlassUi.text(this, "Your saved films and shows appear here.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@buildList, 56f), dp(this@buildList, 20f), 0, 0) })
        return
    }
    list.chunked(7).forEachIndexed { i, chunk -> rows.addView(staticTitleRow("list$i", if (i == 0) "My List" else "", chunk).view) }
}
