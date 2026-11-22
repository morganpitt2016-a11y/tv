package com.morgan.minimallauncher

import com.morgan.minimallauncher.WatchActivity.CardAdapter
import com.morgan.minimallauncher.WatchActivity.RowScroll
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

internal fun WatchActivity.openDetail(t: WatchCatalog.Title, from: View?) {
    // The film's genres, for the lights' pace when it plays.
    if (t.genres.isNotEmpty()) Prefs.setString(this, ResumeStore.keyOf("genres", t.name), t.genres.joinToString(","))
    val c = this
    if (Xtream.isSetUp(this) && t.type == "SHOW") Xtream.prefetchSeries(applicationContext, t.name, t.year)   // so the episode list is ready when you press Watch
    if (detail == null) detailReturn = from
    detail?.let { root.removeView(it) }
    // The browse screen fades away underneath, so nothing shows through.
    for (v in listOfNotNull<View>(info, nav, scroll, searchBar?.takeIf { it.visibility == View.VISIBLE })) v.animate().alpha(0f).setDuration(200).withEndAction { if (detail != null) v.visibility = View.INVISIBLE }.start()
    val page = FrameLayout(c).apply { isClickable = true; alpha = 0f }
    page.addView(View(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF7000000.toInt(), 0xD9000000.toInt(), 0x66000000, 0x1A000000))
    }, FrameLayout.LayoutParams(-1, -1))
    page.addView(View(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF000000.toInt(), 0xB3000000.toInt(), 0x00000000))
    }, FrameLayout.LayoutParams(-1, dp(c, 300f), Gravity.BOTTOM))
    detail = page
    root.addView(page, FrameLayout.LayoutParams(-1, -1))
    page.animate().alpha(1f).setDuration(280).start()
    heroUrl = null
    setHeroImage(t.backdrop ?: t.poster, fallback = t.poster)
    // The rows' dimming (scrolled down) mustn't darken the show's own picture.
    heroDim.animate().alpha(0f).setDuration(280).start()

    // One column that glides down to "More Like This".
    val col = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        setPadding(0, dp(c, 46f), 0, dp(c, 60f))
    }
    val sv = RowScroll(c).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false; clipToPadding = false
        addView(col)
    }
    detailScroll = sv
    page.addView(sv, FrameLayout.LayoutParams(-1, -1))
    fun inset(v: View, top: Float, w: Int = -2) = col.addView(v, LinearLayout.LayoutParams(w, -2).apply {
        marginStart = dp(c, 56f); marginEnd = dp(c, 40f); topMargin = dp(c, top)
    })

    val main = t.bestOffer ?: t.offers.firstOrNull()
    inset(GlassUi.text(c, listOfNotNull(main?.provider?.displayName?.uppercase(), if (t.type == "SHOW") "SERIES" else "FILM").joinToString("  ·  "),
        10.5f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f }, 0f)
    inset(GlassUi.text(c, t.name, 38f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, 4f, dp(c, 620f))
    val meta = GlassUi.text(c, metaLine(t, null), 12.5f, R.font.inter_medium, 0xCCFFFFFF.toInt())
    inset(meta, 8f)
    inset(GlassUi.text(c, t.description, 13.5f, R.font.inter_regular, 0xE6FFFFFF.toInt()).apply {
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f)
    }, 12f, dp(c, 540f))
    val people = GlassUi.text(c, "", 11.5f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    inset(people, 8f, dp(c, 540f))

    // Buttons: one Play (through Dodgy Box), and every other way to watch behind "More Ways to Watch".
    val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    val dodgy = Xtream.isSetUp(c) && !Xtream.isExpired(c)
    val play = when {
        dodgy -> pillButton("▶  Play", primary = true) { watchOnDodgyBox(t) }
        main != null && main.provider.installed != null && main.kind != "RENT" && main.kind != "BUY" ->
            pillButton("▶  Play", primary = true) { useOffer(t, main) }
        Xtream.isSetUp(c) -> pillButton("▶  Play", primary = true) { Xtream.showExpired(this) }
        else -> pillButton("Ways to Watch", primary = true) { moreWays(t) }
    }
    buttons.addView(play, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
    if (dodgy || (main != null && main.provider.installed != null)) {
        buttons.addView(pillButton("More Ways to Watch") { moreWays(t) }, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
    }
    val trailerButton = pillButton("Trailer") {}
    trailerButton.visibility = View.GONE
    buttons.addView(trailerButton, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
    val listButton = pillButton(if (WatchCatalog.inMyList(c, t)) "✓  In My List" else "+  My List") {}
    listButton.setOnClickListener {
        val added = WatchCatalog.toggleMyList(c, t)
        (listButton as TextView).text = if (added) "✓  In My List" else "+  My List"
    }
    buttons.addView(listButton, LinearLayout.LayoutParams(-2, dp(c, 40f)))
    if (Xtream.isSetUp(c)) {
        val app = applicationContext
        Bg.run {
            val there = DodgyAlerts.available(app, t.name, t.year, t.type)
            runOnUiThread {
                if (there || isDestroyed || detail == null) return@runOnUiThread
                val remind = pillButton(if (DodgyAlerts.has(c, t)) "🔔  Reminder Set" else "🔔  Remind Me") {}
                remind.setOnClickListener {
                    val on = DodgyAlerts.toggle(c, t)
                    (remind as TextView).text = if (on) "🔔  Reminder Set" else "🔔  Remind Me"
                }
                buttons.addView(remind, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginStart = dp(c, 10f) })
            }
        }
    }
    inset(buttons, 20f)

    // More Like This (filled in when the details arrive).
    val more = row("more", "More Like This", height = 196f)
    more.view.visibility = View.GONE
    col.addView(more.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })
    detailMore = more.view

    play.requestFocus()
    meta.text = AvBadges.withBadges(this, metaLine(t, null), t.name)
    // 4K / Dolby / 5.1 from the server, if we don't know them yet.
    var detailNow: WatchCatalog.Detail? = null
    AvBadges.fetch(this, t.name, t.year, t.type == "SHOW") { if (detail === page) meta.text = AvBadges.withBadges(this, metaLine(t, detailNow), t.name) }
    WatchCatalog.detail(this, t) { d ->
        detailNow = d
        if (detail !== page || d == null) return@detail
        meta.text = AvBadges.withBadges(this, metaLine(t, d), t.name)
        people.text = listOfNotNull(
            d.cast.takeIf { it.isNotEmpty() }?.let { "Starring " + it.joinToString(", ") },
            d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by " + it.joinToString(", ") },
        ).joinToString("\n")      // each on its own line (joined with a dot, a wrap left a stray "·" at a line start)
        d.trailer?.let { id ->
            trailerButton.visibility = View.VISIBLE
            trailerButton.setOnClickListener { WatchCatalog.playTrailer(this, id) }
        }
        if (d.similar.isNotEmpty()) {
            more.list.adapter = CardAdapter(d.similar, { p -> posterCard(p) }) { h, s -> bindTitle(h, s, inDetail = true) }
            more.view.visibility = View.VISIBLE
        }
    }
}

/**
 * Play a film or show from the provider directly (signed in), or through TiviMate's
 * search otherwise. Shows offer their episodes to choose from.
 */
/**
 * While Dodgy Box is searched: a glass card with the title's poster, "Finding ..." and a shimmering
 * placeholder, so it's obvious something is happening. Returns a function that removes it.
 */
internal fun WatchActivity.showFinding(t: WatchCatalog.Title): () -> Unit {
    val c = this
    val overlay = FrameLayout(c).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true; alpha = 0f }
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = dp(c, 24f).toFloat(); setColor(0xE61C1C1F.toInt()) }, GlassUi.panel(c, 24f, 0x20)))
        setPadding(dp(c, 22f), dp(c, 22f), dp(c, 30f), dp(c, 22f))
    }
    val poster = ImageView(c).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { cornerRadius = dp(c, 12f).toFloat(); setColor(0xFF2A2A2F.toInt()) }
        clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
    }
    ImageLoader.load(poster, t.poster, ImageLoader.CARD)
    card.addView(poster, LinearLayout.LayoutParams(dp(c, 104f), dp(c, 156f)))
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    col.addView(GlassUi.text(c, "DODGY BOX", 11f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f })
    col.addView(GlassUi.text(c, "Finding ${t.name}", 21f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
        LinearLayout.LayoutParams(dp(c, 360f), -2).apply { topMargin = dp(c, 6f) })
    col.addView(GlassUi.text(c, if (t.type == "SHOW") "Pirating tf out of this series rn…" else "Pirating tf out of this movie rn…", 13f, R.font.inter_medium, 0xB3FFFFFF.toInt()),
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
    // Placeholder lines that shimmer while it works.
    val bars = listOf(300f, 240f, 270f).map { w ->
        View(c).apply { background = GradientDrawable().apply { cornerRadius = dp(c, 6f).toFloat(); setColor(0x33FFFFFF) } }.also {
            col.addView(it, LinearLayout.LayoutParams(dp(c, w), dp(c, 12f)).apply { topMargin = dp(c, 14f) })
        }
    }
    card.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 22f) })
    overlay.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    overlay.animate().alpha(1f).setDuration(160).start()
    card.scaleX = 0.96f; card.scaleY = 0.96f
    card.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
    val shimmer = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { a -> val f = a.animatedValue as Float; bars.forEachIndexed { i, b -> b.alpha = 0.35f + 0.65f * ((f + i * 0.25f) % 1f) } }
        start()
    }
    var gone = false
    return {
        if (!gone) {
            gone = true
            shimmer.cancel()
            overlay.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(overlay) }.start()
        }
    }
}

internal fun WatchActivity.watchOnDodgyBox(t: WatchCatalog.Title) {
    val show = t.type == "SHOW"
    if (!Xtream.isSetUp(this)) {
        val last = if (show) WatchHistory.last(this, t.name) else null
        if (!show && WatchHistory.playedFilm(this, t.name)) {
            // Seen (some of) it before: TiviMate remembers how far, so the choice is just continue or restart.
            GlassDialog(this).title(t.name).message("You've watched part of this before.")
                .button("Continue", GlassDialog.Style.BOLD) { TiviTuner.playVod(this, t.name, t.year, show) }
                .button("Start from the beginning") { TiviTuner.playVod(this, t.name, t.year, show, fromStart = true) }
                .button("Cancel").show()
            return
        }
        if (last == null) { TiviTuner.playVod(this, t.name, t.year, show); return }
        // Watched before: pick up where you were, move on, or start again - TiviMate alone starts at episode one.
        fun go(s: Int, e: Int) { TiviTuner.playVod(this, t.name, t.year, show, episode = s to e) }
        GlassDialog(this).title(t.name).message("You last watched S${last.season} E${last.episode}.")
            .button("Continue S${last.season} E${last.episode}", GlassDialog.Style.BOLD) { go(last.season, last.episode) }
            .button("Next episode · S${last.season} E${last.episode + 1}") { go(last.season, last.episode + 1) }
            .button("Start from the beginning") { go(1, 1) }
            .button("Choose in TiviMate") { TiviTuner.playVod(this, t.name, t.year, show) }
            .button("Cancel").show()
        return
    }
    val dismiss = showFinding(t)
    val app = applicationContext
    Bg.run {
        var film: Xtream.Vod? = null
        var series: Xtream.Series? = null
        var episodes: List<Xtream.Episode> = emptyList()
        val t0 = Perf.now()
        try {
            if (show) { Xtream.seriesEpisodes(app, t.name, t.year)?.let { (sr, eps) -> series = sr; episodes = eps } }
            else film = Xtream.findVod(app, t.name, t.year)
        } catch (e: Exception) {}
        Perf.note("Dodgy Box: found ${t.name}", t0)
        runOnUiThread {
            dismiss()
            if (isDestroyed) return@runOnUiThread
            val f = film
            when {
                f != null -> playFilm(t, Xtream.vodUrl(app, f))
                series != null && episodes.isNotEmpty() -> chooseEpisode(t, episodes)
                else -> moreWays(t, "${t.name} isn’t on Dodgy Box.")
            }
        }
    }
}

internal fun WatchActivity.playFilm(t: WatchCatalog.Title, url: String) {
    fun go(fromStart: Boolean) {
        if (fromStart) WatchHistory.forget(applicationContext, url)
        PlayerActivity.play(this, url, t.name, live = false, subtitle = t.year.takeIf { it > 0 }?.toString(), eyebrow = "FILM", fromStart = fromStart, image = t.backdrop ?: t.poster)
    }
    val at = WatchHistory.seconds(this, url)
    Xtream.preResolve(url)            // the stream's connection gets ready while you choose
    if (at <= 30) return go(false)
    GlassDialog(this).title(t.name).message("You stopped at ${WatchHistory.clock(at)}.")
        .button("Continue from ${WatchHistory.clock(at)}", GlassDialog.Style.BOLD) { go(false) }
        .button("Start from the beginning") { go(true) }
        .button("Cancel").show()
}

internal fun WatchActivity.playEpisode(t: WatchCatalog.Title, e: Xtream.Episode, fromStart: Boolean = false) =
    playEpisodeNamed(t.name, t.backdrop ?: t.poster, e, fromStart)

internal fun WatchActivity.playEpisodeNamed(name: String, image: String?, e: Xtream.Episode, fromStart: Boolean = false) {
    val app = applicationContext
    val url = Xtream.episodeUrl(app, e)
    if (fromStart) WatchHistory.forget(app, url)
    WatchHistory.record(app, name, e.season, e.number)
    val all = lastEpisodes.takeIf { l -> l.any { it.id == e.id } } ?: listOf(e)
    val rest = all.dropWhile { it.id != e.id }.drop(1).map { Triple(Xtream.episodeUrl(app, it), it.season, it.number) }
    PlayerActivity.play(this, url, name, live = false, subtitle = "Season ${e.season}, Episode ${e.number}", eyebrow = "SERIES",
        fromStart = fromStart, series = name, queue = rest, image = image, season = e.season, episode = e.number)
}

/** A Dodgy Box show: its full-screen page (Play / Resume, seasons, episodes) - like Apple TV. */
internal fun WatchActivity.chooseEpisode(t: WatchCatalog.Title, episodes: List<Xtream.Episode>) {
    lastEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.number }))
    episodeList(t, lastEpisodes)
}

/** The episode chooser: a full page with each episode's picture, name, description and length. */
internal fun WatchActivity.episodeList(t: WatchCatalog.Title, episodes: List<Xtream.Episode>) {
    lastEpisodes = episodes
    startActivity(Intent(this, EpisodesActivity::class.java)
        .putExtra(EpisodesActivity.EXTRA_NAME, t.name).putExtra(EpisodesActivity.EXTRA_IMAGE, t.backdrop ?: t.poster))
}

/** Inside the detail page: glide down to More Like This, or back up to the buttons. */
internal fun WatchActivity.detailFocusMoved(v: View) {
    val sv = detailScroll ?: return
    val more = detailMore ?: return
    var p: Any? = v.parent
    var inMore = false
    while (p is View) { if (p === more) { inMore = true; break }; p = p.parent }
    sv.smoothScrollTo(0, if (inMore) (more.top - dp(this, 24f)).coerceAtLeast(0) else 0)
}

internal fun WatchActivity.closeDetail() {
    val page = detail ?: return
    detail = null
    detailScroll = null
    detailMore = null
    // Back from a title's page: the hero text only comes back if the hero isn't tucked away (else it showed through the rows).
    searchBar?.takeIf { tab == "search" }?.let { it.visibility = View.VISIBLE; it.animate().alpha(1f).setDuration(240).start() }
    for (v in listOf<View>(info, nav, scroll)) {
        v.visibility = View.VISIBLE
        v.animate().alpha(if (v === info && (heroCollapsed || searchBar?.visibility == View.VISIBLE)) 0f else 1f).setDuration(240).start()
    }
    page.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(page) }.start()
    heroDim.animate().alpha(if (heroCollapsed) 0.9f else 0f).setDuration(280).start()
    heroUrl = null
    // Something may have been watched from the page: bring Continue Watching and My List up to date.
    if (rows.childCount > 0) { if (tab == "home") refreshPersonalRows(); refreshResumeRow() }
    detailReturn?.takeIf { it.isAttachedToWindow }?.requestFocus() ?: firstItem()?.requestFocus()
    detailReturn = null
}

internal fun WatchActivity.metaLine(t: WatchCatalog.Title, d: WatchCatalog.Detail?): CharSequence {
    val parts = listOfNotNull(
        t.year.takeIf { it > 0 }?.toString(),
        d?.seasons?.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" },
        d?.age?.takeIf { it.isNotBlank() }?.let { "\u0000$it" },          // the age rating, in a box like tvOS
        d?.runtime?.takeIf { it > 0 }?.let { if (it >= 60 && t.type == "MOVIE") "${it / 60}h ${it % 60}m" else "$it min" },
        t.imdb?.let { "IMDb %.1f".format(it) },
        t.genres.take(3).joinToString(", ").ifEmpty { null },
    )
    val b = android.text.SpannableStringBuilder()
    parts.forEachIndexed { i, p ->
        if (i > 0) b.append("  ·  ")
        if (p.startsWith("\u0000")) {
            val start = b.length; b.append(p.substring(1))
            b.setSpan(AvBadges.Box(resources.displayMetrics.density), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else b.append(p)
    }
    return b
}

/** Every other way to watch: each service that has it (included, rent, buy or not installed), then Google TV. */
internal fun WatchActivity.moreWays(t: WatchCatalog.Title, note: String? = null) {
    val d = GlassDialog(this).title("Ways to Watch")
    d.message((note?.let { "$it\n\n" } ?: "") + if (t.offers.isEmpty()) "No streaming service has ${t.name} in the UK right now." else "Pick where to watch ${t.name}.")
    for (o in t.offers.distinctBy { it.provider.displayName }.take(6)) {
        val how = when {
            o.provider.installed == null -> "Get the app"
            o.kind == "FLATRATE" -> "Included"
            o.kind == "FREE" || o.kind == "ADS" -> "Free"
            o.kind == "RENT" -> "Rent"
            o.kind == "BUY" -> "Buy"
            else -> "Available"
        }
        d.button("${o.provider.displayName}  ·  $how") { useOffer(t, o) }
    }
    d.button("Search Google TV") { WatchCatalog.searchGoogleTv(this, t.name) }
    d.button("Cancel").show()
}

/** Watch / rent / buy, or offer to install the app. */
internal fun WatchActivity.useOffer(t: WatchCatalog.Title, o: WatchCatalog.Offer) {
    if (o.provider.installed != null) { WatchCatalog.open(this, t, o); return }
    GlassDialog(this).title("Get ${o.provider.displayName}")
        .message("${t.name} is on ${o.provider.displayName}, which isn’t installed on this TV yet.")
        .button("Get the App", GlassDialog.Style.BOLD) { WatchCatalog.getApp(this, o.provider) }
        .button("Cancel")
        .show()
}

internal fun WatchActivity.offerChip(t: WatchCatalog.Title, o: WatchCatalog.Offer): View {
    val c = this
    val chip = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = GlassUi.rowBackground(c, 12f, 0x1FFFFFFF)
        setPadding(dp(c, 6f), 0, dp(c, 14f), 0)
        isFocusable = true; isClickable = true
        contentDescription = o.provider.displayName
        setOnClickListener { useOffer(t, o) }
    }
    val icon = ImageView(c).apply {
        background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0x33FFFFFF) }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }
    ImageLoader.load(icon, o.provider.icon, ImageLoader.ICON)
    chip.addView(icon, LinearLayout.LayoutParams(dp(c, 32f), dp(c, 32f)))
    val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
    text.addView(GlassUi.rowText(c, o.provider.displayName, 12f, R.font.inter_semibold, GlassUi.primaryText()))
    text.addView(GlassUi.rowText(c, when {
        o.provider.installed == null -> "Not installed"
        o.kind == "FLATRATE" -> "Included"
        o.kind == "FREE" || o.kind == "ADS" -> "Free"
        o.kind == "RENT" -> "Rent"
        o.kind == "BUY" -> "Buy"
        else -> "Available"
    }, 10f, R.font.inter_medium, GlassUi.secondaryText()), lp(1f))
    chip.addView(text, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 9f) })
    GlassUi.focusLift(chip, 1.05f)
    return chip
}

internal fun WatchActivity.pillButton(label: String, primary: Boolean = false, onClick: () -> Unit): View {
    val c = this
    return GlassUi.rowText(c, label, 13.5f, R.font.inter_semibold, GlassUi.primaryText()).apply {
        isDuplicateParentStateEnabled = false
        gravity = Gravity.CENTER
        setPadding(dp(c, 20f), 0, dp(c, 20f), 0)
        background = GlassUi.rowBackground(c, 20f, if (primary) 0x40FFFFFF else 0x1FFFFFFF)
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        GlassUi.focusLift(this, 1.05f)
    }
}
