package com.morgan.minimallauncher

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

internal fun WatchActivity.playLive(ch: Xtream.Live) {
    val app = applicationContext
    PlayerActivity.play(this, Xtream.liveUrl(app, ch.id), ch.name, live = true, subtitle = LiveGuide.nowNext(app, ch.name),
        eyebrow = "LIVE", alt = Xtream.liveUrl(app, ch.id, hls = true), liveId = ch.id, liveCat = ch.category)
}

/** Live TV: a big "TV Guide" button (opens the full-screen guide) and the popular channels, ready at once. */
internal fun WatchActivity.buildLiveProvider(token: Int) {
    val host = rows
    val app = applicationContext
    val t0 = Perf.now()
    // The guide button first: it works immediately, whatever else is still loading.
    val guideBtn = GlassUi.text(this, "Open the TV Guide", 17f, R.font.inter_semibold, GlassUi.INK).apply {
        gravity = Gravity.CENTER
        background = GlassUi.rowBackground(this@buildLiveProvider, 16f)
        isFocusable = true; isClickable = true
        setPadding(dp(context, 30f), 0, dp(context, 30f), 0)
        setOnClickListener { startActivity(android.content.Intent(this@buildLiveProvider, LiveGuideActivity::class.java)) }
        setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.05f else 1f) }
        contentDescription = "Open the TV Guide"
    }
    host.addView(guideBtn, LinearLayout.LayoutParams(-2, dp(this, 46f)).apply { setMargins(dp(this@buildLiveProvider, 56f), dp(this@buildLiveProvider, 6f), 0, dp(this@buildLiveProvider, 10f)) })
    val loading = GlassUi.text(this, "Loading channels…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
    host.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@buildLiveProvider, 56f), dp(this@buildLiveProvider, 8f), 0, 0) })
    Bg.run {
        val ready = java.util.concurrent.CountDownLatch(1)
        runOnUiThread { LiveGuide.load(this) { ready.countDown() } }
        try { ready.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        val cats = Xtream.liveCategories(app)
        val first = cats.entries.firstOrNull { it.value.trim().startsWith("UK", true) && it.value.contains("entertainment", true) }?.key
            ?: cats.keys.firstOrNull { cats[it].orEmpty().trim().startsWith("UK", true) } ?: cats.keys.firstOrNull()
        val all = if (first == null) emptyList() else try { Xtream.liveGroup(app, first) } catch (e: Exception) { emptyList() }
        runOnUiThread {
            if (containerToken[host] != token || isDestroyed) return@runOnUiThread
            host.removeView(loading)
            val popular = popularNames.mapNotNull { n ->
                val k = TextMatch.normalise(n)
                all.firstOrNull { TextMatch.normalise(it.name) == k } ?: all.firstOrNull { TextMatch.normalise(it.name).let { x -> TextMatch.matches(x, k) } }
            }.distinctBy { it.id }
            if (popular.isNotEmpty()) {
                val r = row("popular", "Popular", height = 150f)
                r.list.adapter = CardAdapter(popular, { p -> wideCard(p) }) { h, ch -> bindLiveCard(h, ch) }
                host.addView(r.view)
            }
            Perf.note("Live TV: front page ready", t0)
            if (host === rows) focusContentIfIdle()
        }
    }
}

internal fun WatchActivity.bindLiveCard(h: Holder, ch: Xtream.Live) {
    val g = LiveGuide.guideFor(ch.name)
    val now = g?.now()
    h.label.text = now?.title ?: ch.name
    h.sub?.text = if (now != null) "${g.name}  ·  ${LiveGuide.nowNext(applicationContext, ch.name)?.substringAfter("  ·  ", "") ?: ""}".trimEnd(' ', '·') else ch.name
    h.progress?.let { bar -> bar.post { bar.layoutParams.width = ((h.root.width) * (now?.progress() ?: 0f)).toInt(); bar.requestLayout() } }
    ImageLoader.load(h.image, now?.image ?: g?.logo ?: ch.icon, ImageLoader.CARD)
    h.root.contentDescription = ch.name
    h.root.setOnClickListener { playLive(ch) }
    focusEffect(h.root) { }
}

internal fun WatchActivity.channelRow(ch: Xtream.Live, group: List<Xtream.Live>): View {
    val c = this
    val row = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 56f), dp(c, 4f), dp(c, 56f), dp(c, 4f))
        isFocusable = true; isClickable = true
        background = GlassUi.panel(c, 12f, 0x14)
    }
    row.addView(GlassUi.text(c, ch.num.toString(), 13f, R.font.inter_medium, 0x80FFFFFF.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(c, 36f), -2))
    val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    ImageLoader.load(logo, ch.icon, ImageLoader.ICON)
    row.addView(logo, LinearLayout.LayoutParams(dp(c, 64f), dp(c, 36f)).apply { marginStart = dp(c, 8f); marginEnd = dp(c, 14f) })
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    col.addView(GlassUi.text(c, ch.name, 14f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    col.addView(GlassUi.text(c, LiveGuide.nowNext(c, ch.name) ?: "", 11.5f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    row.contentDescription = ch.name
    row.setOnClickListener {
        val app = applicationContext
        PlayerActivity.play(this, Xtream.liveUrl(app, ch.id), ch.name, live = true, subtitle = LiveGuide.nowNext(app, ch.name),
            eyebrow = "LIVE", alt = Xtream.liveUrl(app, ch.id, hls = true), liveId = ch.id)
    }
    focusEffect(row) { }
    return row
}

internal fun WatchActivity.buildLive(token: Int) {
    showPlainHero("LIVE TV", "What’s on", "Popular channels first, then every group, with a full guide.")
    if (Xtream.isSetUp(this)) buildLiveProvider(token) else buildLiveGuide(token)
}

internal fun WatchActivity.buildLiveGuide(token: Int) {
    val t0 = Perf.now()
    val host = rows
    // Just the TV guide (the live TV apps are on the home screen).
    val loading = GlassUi.text(this, "Loading the TV guide…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
    host.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@buildLiveGuide, 56f), dp(this@buildLiveGuide, 20f), 0, 0) })
    LiveGuide.load(this) { channels ->
        if (containerToken[host] != token || isDestroyed) return@load
        host.removeView(loading)
        if (channels.isNullOrEmpty()) {
            host.addView(GlassUi.text(this, "The TV guide couldn’t be loaded. Check the internet connection.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@buildLiveGuide, 56f), dp(this@buildLiveGuide, 20f), 0, 0) })
            return@load
        }
        // "On Now": big picture cards for the main channels.
        val onNow = channels.take(24).mapNotNull { ch -> ch.now()?.let { ch to it } }
        if (onNow.isNotEmpty()) {
            val r = row("onnow", "On Now", height = 150f)
            r.list.adapter = CardAdapter(onNow, { p -> wideCard(p) }) { h, (ch, prog) -> bindProgramme(h, ch, prog) }
            host.addView(r.view)
        }
        host.addView(GlassUi.text(this, "Guide", 15f, R.font.inter_semibold),
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@buildLiveGuide, 56f), dp(this@buildLiveGuide, 16f), 0, dp(this@buildLiveGuide, 4f)) })
        // Channel rows are built as you reach them: a first screenful now, then more each time the
        // highlight gets near the bottom (building all 150 up front made the tab stutter).
        val list = channels.take(150)
        var next = 0
        fun addBatch(count: Int) {
            if (containerToken[host] != token || isDestroyed) return
            val first = next == 0
            val end = minOf(next + count, list.size)
            while (next < end) {
                val i = next
                try {
                    host.addView(guideRow(list[i]).also { r ->
                        // Reaching one of the last few built rows builds the next batch.
                        r.setTag(R.id.blur_anim, Runnable { if (i >= next - 6 && next < list.size) addBatch(12) })
                    })
                } catch (e: Exception) {}
                next++
            }
            if (first) {
                Perf.note("Live TV: first rows on screen", t0)
                if (host === rows) focusContentIfIdle()
            }
        }
        addBatch(16)
    }
}

internal fun WatchActivity.guideRow(ch: LiveGuide.Channel): View {
    val c = this
    val row = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 56f), dp(c, 4f), dp(c, 40f), dp(c, 4f))
        clipChildren = false; clipToPadding = false
        setTag(R.id.blur_radius, "guide")
    }
    val logo = FrameLayout(c).apply {
        background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0x1AFFFFFF) }
    }
    val logoImg = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    logo.addView(logoImg, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(c, 8f), dp(c, 6f), dp(c, 8f), dp(c, 6f)) })
    if (ch.logo != null) ImageLoader.load(logoImg, ch.logo, ImageLoader.ICON)
    else logo.addView(GlassUi.text(c, ch.name, 10f, R.font.inter_semibold).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
    row.addView(logo, LinearLayout.LayoutParams(dp(c, 88f), dp(c, 46f)))
    val progs = ch.upcoming().take(3)
    progs.forEachIndexed { i, p ->
        row.addView(guideBlock(ch, p, i == 0), LinearLayout.LayoutParams(dp(c, if (i == 0) 290f else 205f), dp(c, 46f)).apply { marginStart = dp(c, 8f) })
    }
    return row
}

internal fun WatchActivity.guideBlock(ch: LiveGuide.Channel, p: LiveGuide.Programme, first: Boolean): View {
    val c = this
    val block = FrameLayout(c).apply {
        background = GlassUi.rowBackground(c, 10f, if (first && p.isOn()) 0x2EFFFFFF else 0x14FFFFFF)
        isFocusable = true; isClickable = true
        contentDescription = "${p.title}, ${ch.name}"
        setOnClickListener { playOrAsk(ch, p) }
        setOnLongClickListener { programmeDialog(ch, p); true }
    }
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
    col.addView(GlassUi.rowText(c, p.title, 12.5f, R.font.inter_semibold, GlassUi.primaryText()))
    col.addView(GlassUi.rowText(c, timeRange(p) + if (p.isOn()) "  ·  On now" else "", 10.5f, R.font.inter_medium, GlassUi.secondaryText()), lp(2f))
    block.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dp(c, 12f), 0, dp(c, 10f), 0) })
    if (p.isOn()) {
        val bar = View(c).apply { setBackgroundColor(0xCCFFFFFF.toInt()) }
        block.addView(bar, FrameLayout.LayoutParams((dp(c, if (first) 290f else 205f) * p.progress()).toInt(), dp(c, 2f), Gravity.BOTTOM))
    }
    block.setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
            .setInterpolator(Motion.focusEase).setDuration(Motion.FOCUS).start()
        if (has) showProgrammeHero(ch, p)
    }
    return block
}

internal fun WatchActivity.bindProgramme(h: Holder, ch: LiveGuide.Channel, p: LiveGuide.Programme) {
    h.label.text = p.title
    h.sub?.text = "${ch.name}  ·  ${timeRange(p)}"
    h.progress?.let { bar -> bar.post { bar.layoutParams.width = ((h.root.width) * p.progress()).toInt(); bar.requestLayout() } }
    ImageLoader.load(h.image, p.image ?: ch.logo, ImageLoader.CARD)
    h.root.contentDescription = "${p.title} on ${ch.name}"
    h.root.setOnClickListener { playOrAsk(ch, p) }
    h.root.setOnLongClickListener { programmeDialog(ch, p); true }
    focusEffect(h.root) { showProgrammeHero(ch, p) }
}

/** On now: straight to that channel in TiviMate. Later (or no TiviMate): the options. Hold OK for options any time. */
internal fun WatchActivity.playOrAsk(ch: LiveGuide.Channel, p: LiveGuide.Programme) {
    if (p.isOn() && LiveGuide.hasTiviMate(this)) LiveGuide.openTiviMate(this, ch) else programmeDialog(ch, p)
}

internal fun WatchActivity.programmeDialog(ch: LiveGuide.Channel, p: LiveGuide.Programme) {
    val d = GlassDialog(this).title(p.title)
    d.message(listOf("${ch.name}  ·  ${timeRange(p)}" + (if (p.episode.isNotBlank()) "  ·  ${p.episode}" else ""), p.desc.take(300))
        .filter { it.isNotBlank() }.joinToString("\n\n"))
    if (LiveGuide.hasTiviMate(this)) {
        d.button("Watch on Dodgy Box", GlassDialog.Style.BOLD) { LiveGuide.openTiviMate(this, ch) }
        when {
            p.isOn() -> d.button("Record on Dodgy Box") { TiviRecorder.recordNow(this, ch) }
            TiviRecorder.isScheduled(this, ch, p) -> d.button("Cancel This Recording", GlassDialog.Style.DESTRUCTIVE) {
                TiviRecorder.list(this).filter { it.channel == ch.name && it.start == p.start }.forEach { TiviRecorder.cancel(this, it.id) }
                GlassUi.toast(this, "Recording cancelled")
            }
            p.start > System.currentTimeMillis() -> d.button("Record This Episode") {
                TiviRecorder.schedule(this, ch, p)
                GlassUi.toast(this, "TiviMate will record ${p.title} - keep the TV on")
            }
        }
        // Series link: every episode on this channel, as the guide shows them.
        if (TiviRecorder.isSeries(this, ch, p)) {
            d.button("Cancel Series Recording", GlassDialog.Style.DESTRUCTIVE) {
                TiviRecorder.removeSeries(this, ch, p)
                GlassUi.toast(this, "Stopped recording ${p.title}")
            }
        } else {
            d.button("Record Series") {
                LiveGuide.load(this) { channels ->
                    val n = TiviRecorder.addSeries(this, ch, p, channels ?: emptyList())
                    GlassUi.toast(this, "Recording every ${p.title} on ${ch.name}" +
                        if (n > 0) " - $n episode${if (n == 1) "" else "s"} scheduled" else " - new episodes are added as the guide updates")
                }
                if (p.isOn()) TiviRecorder.recordNow(this, ch)
            }
        }
    }
    LiveGuide.broadcasterApp(this, ch)?.let { (pkg, name) ->
        d.button("Watch in $name") { LiveGuide.openApp(this, pkg) }
    }
    d.button("Find on Demand") { search(p.title) }
    d.button("Cancel")
    d.show()
}

// ---------- My List ----------

/** Programmes the app recorded: newest first; OK plays, hold OK to delete. */
internal fun WatchActivity.recordingsRow(key: String): View? {
    val recs = Recordings.shown(this)
    if (recs.isEmpty()) return null
    val r = row(key, "Recordings", height = 150f)
    val f = DateFormat.getMediumDateFormat(this)
    val time = DateFormat.getTimeFormat(this)
    r.list.adapter = CardAdapter(recs, { p -> wideCard(p) }) { h, rec ->
        h.label.text = rec.title
        h.sub?.text = if (Recordings.isRecording(rec)) "● Recording now · ${rec.channel}" else "${rec.channel}  ·  ${f.format(Date(rec.start))} ${time.format(Date(rec.start))}"
        h.progress?.visibility = View.GONE
        ImageLoader.load(h.image, rec.image, ImageLoader.CARD)
        h.root.contentDescription = rec.title
        h.root.setOnClickListener {
            PlayerActivity.play(this, android.net.Uri.fromFile(java.io.File(rec.file)).toString(), rec.title, live = false,
                subtitle = rec.channel, eyebrow = "RECORDING")
        }
        h.root.setOnLongClickListener {
            GlassDialog(this).title(rec.title).message(if (Recordings.isRecording(rec)) "Still recording." else "Recorded from ${rec.channel}.")
                .button(if (Recordings.isRecording(rec)) "Stop and Delete" else "Delete Recording", GlassDialog.Style.DESTRUCTIVE) {
                    Recordings.delete(this, rec); showTab(tab, focusContent = true)
                }
                .button("Cancel").show()
            true
        }
        focusEffect(h.root) { showPlainHero("RECORDING", rec.title, rec.channel) }
    }
    return r.view
}

internal fun WatchActivity.buildTonight(token: Int, onHome: Boolean = false) {
    if (!onHome) showPlainHero("TONIGHT", "What’s On Tonight", "The best of tonight’s TV, the newest films on Dodgy Box, and your new episodes - all in one place.")
    val host = rows
    val loading = GlassUi.text(this, "Finding tonight’s best…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
    if (!onHome) host.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildTonight, 56f); topMargin = dp(this@buildTonight, 14f) })
    val app = applicationContext
    handler.postDelayed({
        if (containerToken[host] == token && loading.parent != null) loading.text = "Still loading the TV guide… (it can take a minute the first time)"
    }, 12_000)
    LiveGuide.load(this) { guide ->
        Thread {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 18); cal.set(java.util.Calendar.MINUTE, 0)
            val from = maxOf(now - 20 * 60_000L, cal.timeInMillis)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1); cal.set(java.util.Calendar.HOUR_OF_DAY, 1)
            val to = cal.timeInMillis
            val all = guide.orEmpty().flatMap { ch -> ch.programmes.filter { it.stop > from && it.start < to }.map { ch to it } }
            fun big(ch: LiveGuide.Channel) = TextMatch.normalise(ch.name).let { n -> BIG_CHANNELS.any { TextMatch.matches(n, it) } }
            val films = all.filter { (ch, p) -> p.stop - p.start >= 85 * 60_000L && !NOT_FILM.containsMatchIn(p.title) &&
                    (p.start >= from - 30 * 60_000L) && (big(ch) || ch.name.contains("film", true) || ch.name.contains("cinema", true) || ch.name.contains("movie", true)) }
                .distinctBy { it.second.title.lowercase() }.sortedBy { it.second.start }.take(30)
            val sport = all.filter { (_, p) -> SPORT.containsMatchIn(p.title) && p.stop - p.start >= 60 * 60_000L }
                .distinctBy { it.second.title.lowercase() }.sortedBy { it.second.start }.take(20)
            val picks = all.filter { (ch, p) -> big(ch) && p.start in (from - 5 * 60_000L)..(to - 2 * 3_600_000L) && !NOT_FILM.containsMatchIn(p.title) &&
                    p.stop - p.start in 25 * 60_000L..85 * 60_000L }
                .distinctBy { it.second.title.lowercase() }.sortedBy { it.second.start }.take(30)
            runOnUiThread {
                if (containerToken[host] != token || isDestroyed) return@runOnUiThread
                host.removeView(loading)
                var homeAt = 3
                fun progRow(key: String, title: String, list: List<Pair<LiveGuide.Channel, LiveGuide.Programme>>) {
                    if (list.isEmpty()) return
                    val r = row(key, title, height = 150f)
                    r.list.adapter = CardAdapter(list, { p -> wideCard(p) }) { h, (ch, p) -> bindProgramme(h, ch, p); h.root.setOnClickListener { tonightOpen(ch, p) } }
                    // On Home, tonight's TV sits just under the first few rows.
                    if (onHome) host.addView(r.view, minOf(host.childCount, homeAt++)) else host.addView(r.view)
                }
                progRow("tn_films", "Films on TV Tonight", films)
                progRow("tn_picks", "Tonight’s Best on the Main Channels", picks)
                progRow("tn_sport", "Sport Tonight", sport)
                if (!onHome && films.isEmpty() && picks.isEmpty() && sport.isEmpty() && guide == null)
                    host.addView(GlassUi.text(this, "The TV guide hasn’t loaded yet - try again in a moment.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@buildTonight, 56f); topMargin = dp(this@buildTonight, 14f) })
                focusContentIfIdle()
            }
            // Then what's new on Dodgy Box (that list can be slow to fetch). Home has its own Dodgy Box rows.
            if (onHome) return@Thread
            val newFilms = if (Xtream.isSetUp(app) && !Xtream.isExpired(app)) try { kidsFilms(Xtream.newestFilms(app, 30)) } catch (e: Exception) { emptyList() } else emptyList()
            runOnUiThread {
                if (containerToken[host] != token || isDestroyed || newFilms.isEmpty()) return@runOnUiThread
                host.addView(libRow("tn_new", "Just Added to Dodgy Box", newFilms) { h, v -> bindVod(h, v) }.view)
                focusContentIfIdle()
            }
        }.start()
    }
}

/** On now: watch it here. Later: a reminder (or the usual options). */
internal fun WatchActivity.tonightOpen(ch: LiveGuide.Channel, p: LiveGuide.Programme) {
    if (!Xtream.isSetUp(this)) { playOrAsk(ch, p); return }
    val app = applicationContext
    Bg.run {
        val live = Sport.liveFor(app, ch)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            when {
                live == null -> programmeDialog(ch, p)
                p.isOn() -> playLive(live)
                else -> GlassDialog(this).title(p.title).message("${ch.name}  ·  ${timeRange(p)}" + if (p.desc.isNotBlank()) "\n\n" + p.desc.take(260) else "")
                    .button(if (Reminders.has(this, live.id, p.start)) "Reminder Set ✓" else "Remind Me", GlassDialog.Style.BOLD) {
                        Reminders.add(this, Reminders.Item(live.id, live.name, live.category, p.title, p.start))
                        GlassUi.toast(this, "We’ll remind you when ${p.title} starts")
                    }
                    .button("Watch ${ch.name} Now") { playLive(live) }
                    .button("Cancel").show()
            }
        }
    }
}
