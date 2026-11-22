package com.morgan.minimallauncher

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

internal fun WatchActivity.firstContentRow(): View? = (0 until rows.childCount).map { rows.getChildAt(it) }
    .firstOrNull { it.visibility == View.VISIBLE && (it.isFocusable || (it as? ViewGroup)?.getFocusables(View.FOCUS_DOWN)?.isNotEmpty() == true) }

internal fun WatchActivity.setHeroCollapsed(collapse: Boolean) {
    if (collapse == heroCollapsed || detail != null) return
    heroCollapsed = collapse
    val lp = scroll.layoutParams as FrameLayout.LayoutParams
    val from = lp.topMargin
    val to = dp(this, if (collapse) 70f else 262f)
    heroAnim?.cancel()
    heroAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
        duration = 380; interpolator = Motion.focusEase
        addUpdateListener { lp.topMargin = it.animatedValue as Int; scroll.layoutParams = lp }
        start()
    }
    info.animate().alpha(if (collapse || searchBar?.visibility == View.VISIBLE) 0f else 1f).setDuration(260).start()
    heroDim.animate().alpha(if (collapse) 0.9f else 0f).setDuration(380).start()
    // Search: the results slide up into the search bar's place, so the bar steps aside until you're back at the top.
    searchBar?.takeIf { it.visibility == View.VISIBLE }?.animate()?.alpha(if (collapse) 0f else 1f)?.setDuration(220)?.start()
}

/** Live TV: just a doorway to the guide (OK or Down opens it), so moving past it loads nothing. */
internal fun WatchActivity.showLiveTab() {
    hideSearchBar()
    tab = "live"
    for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "live"
    val token = ++buildToken
    freshRows()
    setHeroImage(null)
    showPlainHero("LIVE TV", "TV Guide", "Press OK or ↓ to open the full guide")
    // A preview of the guide itself underneath, in the same Liquid Glass as the guide, over a blurred picture of what's on.
    val fill = { list: List<LiveGuide.Channel>? ->
        if (list != null && list.isNotEmpty()) {
            val shown = previewChannels(list)
            rows.addView(guidePreview(shown), LinearLayout.LayoutParams(-1, -2))
            val now = System.currentTimeMillis()
            shown.firstNotNullOfOrNull { ch -> ch.now(now)?.image }?.let { img ->
                setHeroBlur(true)
                setHeroImage(img)
            }
        }
    }
    val ready = LiveGuide.cached()
    if (ready != null) fill(ready)
    else LiveGuide.load(this) { list -> runOnUiThread { if (token == buildToken && tab == "live" && !isFinishing) fill(list) } }
}

/** A soft blur over the big picture (the Live TV tab), or back to sharp (every other page). */
internal fun WatchActivity.setHeroBlur(on: Boolean) {
    if (android.os.Build.VERSION.SDK_INT < 31) return
    val fx = if (on) android.graphics.RenderEffect.createBlurEffect(dp(this, 28f).toFloat(), dp(this, 28f).toFloat(), android.graphics.Shader.TileMode.CLAMP) else null
    for (v in listOf(heroA, heroB)) { v.setRenderEffect(fx); v.alpha = if (on) 0.75f else 1f }
}

private fun previewChannels(all: List<LiveGuide.Channel>): List<LiveGuide.Channel> {
    val now = System.currentTimeMillis()
    val end = now - now % (30 * 60_000L) + 150 * 60_000L
    return all.filter { it.programmes.any { p -> p.stop > now && p.start < end } }.take(5)
}

/** The guide as it opens, drawn like the guide: one glass panel, channels down the left, a time bar, programmes split
 *  by thin lines (what's on now in bold with its progress), and a red line at the time now. */
private fun WatchActivity.guidePreview(chans: List<LiveGuide.Channel>): View {
    val c = this
    val now = System.currentTimeMillis()
    val start = now - now % (30 * 60_000L)
    val span = 150 * 60_000L
    val end = start + span
    val side = dp(c, 56f)
    val inner = dp(c, 10f)
    val logoW = dp(c, 132f)
    val rowH = dp(c, 44f)
    val headH = dp(c, 28f)
    val lineW = resources.displayMetrics.widthPixels - side * 2 - inner * 2 - logoW
    fun x(t: Long) = ((t - start).toFloat() / span * lineW).toInt().coerceIn(0, lineW)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
    val outer = FrameLayout(c).apply { setPadding(side, 0, side, 0); isFocusable = false }
    val panel = FrameLayout(c).apply { background = GlassUi.panel(c, 16f, 0x1C); setPadding(inner, 0, inner, inner / 2); clipToOutline = true }
    outer.addView(panel, FrameLayout.LayoutParams(-1, -2))
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    // Time bar.
    val times = FrameLayout(c)
    var t = start
    while (t < end) {
        times.addView(GlassUi.text(c, fmt.format(java.util.Date(t)), 11.5f, R.font.inter_medium, 0x99FFFFFF.toInt()),
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = logoW + x(t) + dp(c, 8f) })
        times.addView(View(c).apply { setBackgroundColor(0x24FFFFFF) }, FrameLayout.LayoutParams(dp(c, 1f), headH / 2, Gravity.BOTTOM).apply { leftMargin = logoW + x(t) })
        t += 30 * 60_000L
    }
    col.addView(times, LinearLayout.LayoutParams(-1, headH))
    for ((i, ch) in chans.withIndex()) {
        val row = FrameLayout(c)
        // Channel column, a shade darker (as in the guide).
        val logoBox = FrameLayout(c).apply { setBackgroundColor(0x22000000) }
        if (ch.logo != null) {
            val logo = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
            logoBox.addView(logo, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(c, 22f), dp(c, 9f), dp(c, 22f), dp(c, 9f)) })
            ImageLoader.load(logo, ch.logo, ImageLoader.ICON)
        } else logoBox.addView(GlassUi.text(c, ch.name, 12.5f, R.font.inter_semibold).apply { gravity = Gravity.CENTER; maxLines = 1 }, FrameLayout.LayoutParams(-1, -1))
        row.addView(logoBox, FrameLayout.LayoutParams(logoW, rowH))
        for (p in ch.programmes.filter { it.stop > start && it.start < end }) {
            val l = x(maxOf(p.start, start)); val r = x(minOf(p.stop, end))
            if (r - l < dp(c, 8f)) continue
            val on = p.isOn(now)
            val left = logoW + l
            if (l > 0) row.addView(View(c).apply { setBackgroundColor(0x4DFFFFFF) }, FrameLayout.LayoutParams(dp(c, 1f), rowH - dp(c, 16f), Gravity.CENTER_VERTICAL).apply { leftMargin = left })
            if (r - l > dp(c, 48f)) row.addView(GlassUi.text(c, p.title, 12.5f, if (on) R.font.inter_semibold else R.font.inter_medium, if (on) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(r - l - dp(c, 22f), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = left + dp(c, 12f) })
            if (on) {
                val frac = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                val w = r - l - dp(c, 16f)
                row.addView(View(c).apply { background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 2f).toFloat(); setColor(0xA6FFFFFF.toInt()) } },
                    FrameLayout.LayoutParams((w * frac).toInt().coerceAtLeast(dp(c, 2f)), dp(c, 2f), Gravity.BOTTOM).apply { leftMargin = left + dp(c, 8f); bottomMargin = dp(c, 5f) })
            }
        }
        // Hairline between channels.
        if (i < chans.size - 1) row.addView(View(c).apply { setBackgroundColor(0x16FFFFFF) }, FrameLayout.LayoutParams(-1, dp(c, 1f), Gravity.BOTTOM).apply { leftMargin = logoW })
        col.addView(row, LinearLayout.LayoutParams(-1, rowH))
    }
    panel.addView(col, FrameLayout.LayoutParams(-1, -2))
    // The red "now" line down every row, with a dot on the time bar.
    val nx = logoW + x(now)
    panel.addView(View(c).apply { setBackgroundColor(0xFFFF453A.toInt()) }, FrameLayout.LayoutParams(dp(c, 2f), -1).apply { leftMargin = nx - dp(c, 1f); topMargin = headH - dp(c, 3f) })
    panel.addView(View(c).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF453A.toInt()) } },
        FrameLayout.LayoutParams(dp(c, 8f), dp(c, 8f)).apply { leftMargin = nx - dp(c, 4f); topMargin = headH - dp(c, 7f) })
    return outer
}

internal fun WatchActivity.openGuide() = startActivity(Intent(this, LiveGuideActivity::class.java))

/** The Search tab: last results, or a hint to press OK. */
internal fun WatchActivity.showSearchTab() {
    val q = lastSearch
    showSearchBar(focus = false)
    if (q != null) { search(q, focusResults = false); return }
    tab = "search"
    for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "search"
    freshRows()
    ++buildToken
    showPlainHero("SEARCH", "Find anything", "")
    showRecentSearches()
}

/** Rest on a title for 3 seconds and its trailer plays quietly behind the details. */
internal fun WatchActivity.queueTrailer(t: WatchCatalog.Title?) {
    handler.removeCallbacks(trailerJob)
    if (t?.id != trailerFor) stopTrailer()
    trailerTitle = t
    if (t != null && Prefs.bool(this, "hero_trailers", true) && !Kids.isOn(this)) handler.postDelayed(trailerJob, 3000)
}

    internal fun WatchActivity.startTrailer() {
        val t = trailerTitle ?: return
        if (detail != null || isFinishing || t.id.startsWith("dodgy:")) return
        WatchCatalog.detail(this, t) { d ->
            val id = d?.trailer ?: return@detail
            if (trailerTitle?.id != t.id || detail != null || isFinishing) return@detail
            trailerFor = t.id
            val w = trailerView ?: android.webkit.WebView(this).apply {
                isFocusable = false; isFocusableInTouchMode = false
                setBackgroundColor(0xFF000000.toInt())
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                trailerView = this
                trailerHost.addView(this, FrameLayout.LayoutParams(-1, -1))
            }
            val html = """<html><body style="margin:0;background:#000;overflow:hidden">
                <iframe width="100%" height="100%" frameborder="0" allow="autoplay; encrypted-media"
                src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&controls=0&rel=0&iv_load_policy=3&modestbranding=1&disablekb=1&fs=0&playsinline=1&start=8&loop=1&playlist=$id"></iframe>
                </body></html>"""
            w.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            // Fade in once the video has had a moment to start, so there's no black flash.
            trailerHost.animate().cancel()
            trailerHost.animate().alpha(1f).setStartDelay(1800).setDuration(900).start()
        }
    }

internal fun WatchActivity.stopTrailer() {
    handler.removeCallbacks(trailerJob)
    trailerFor = null
    if (!trailerHostReady) return
    trailerHost.animate().cancel()
    trailerHost.animate().alpha(0f).setStartDelay(0).setDuration(250).withEndAction { trailerView?.loadUrl("about:blank") }.start()
}

internal fun WatchActivity.showTitleHero(t: WatchCatalog.Title, now: Boolean = false) = setHero(now) {
    queueTrailer(t)
    // Resting on a show: fetch its episodes now, so its page opens straight away when OK is pressed.
    if (t.type == "SHOW" && Xtream.isSetUp(this) && prefetchFor != t.id) { prefetchFor = t.id; Xtream.prefetchSeries(applicationContext, t.name, t.year) }
    val on = t.offers.filter { it.provider.installed != null }.map { it.provider.displayName }.distinct()
    eyebrow.text = listOfNotNull(on.firstOrNull()?.uppercase(), if (t.type == "SHOW") "SERIES" else "FILM").joinToString("  ·  ")
    heroTitle.text = t.name
    heroMeta.text = AvBadges.withBadges(this, metaLine(t, null), t.name)
    heroText.text = t.description
    heroWhere.text = when {
        on.isNotEmpty() -> "On " + on.joinToString(", ")
        t.offers.isNotEmpty() -> "On ${t.offers.first().provider.displayName} (not installed)"
        else -> ""
    }
    setHeroImage(t.backdrop ?: t.poster)
}

internal fun WatchActivity.showProgrammeHero(ch: LiveGuide.Channel, p: LiveGuide.Programme) = setHero(false) {
    queueTrailer(null)
    eyebrow.text = (if (p.isOn()) "ON NOW  ·  " else "LATER  ·  ") + ch.name.uppercase()
    heroTitle.text = p.title
    heroMeta.text = listOf(timeRange(p), p.episode).filter { it.isNotBlank() }.joinToString("  ·  ")
    heroText.text = p.desc
    heroWhere.text = if (LiveGuide.hasTiviMate(this) && p.isOn()) "Press OK to watch on Dodgy Box · hold OK for more" else if (LiveGuide.hasTiviMate(this)) "Hold OK for options" else ""
    setHeroImage(p.image)
}

internal fun WatchActivity.showPlainHero(top: String, title: String, text: String) = setHero(true) {
    queueTrailer(null)
    eyebrow.text = top
    heroTitle.text = title
    heroMeta.text = ""
    heroText.text = text
    heroWhere.text = ""
}

/** Debounced, so whizzing along a row doesn't flicker through every title. */
internal fun WatchActivity.setHero(now: Boolean, update: () -> Unit) {
    pendingHero?.let { handler.removeCallbacks(it) }
    val r = Runnable { if (detail == null || now) update() }
    pendingHero = r
    if (now) r.run() else handler.postDelayed(r, 260)
}

internal fun WatchActivity.setHeroImage(url: String?, fallback: String? = null) {
    if (url == heroUrl) return
    heroUrl = url
    val incoming = if (heroShowingA) heroB else heroA
    val outgoing = if (heroShowingA) heroA else heroB
    heroShowingA = !heroShowingA
    if (url == null) { outgoing.animate().alpha(0f).setDuration(500).start(); return }
    ImageLoader.load(incoming, url, ImageLoader.HERO, fade = false) { bmp ->
        // The wide artwork failed to load: the poster instead, rather than a black page.
        if (bmp == null && heroUrl == url && fallback != null && fallback != url) { heroUrl = null; setHeroImage(fallback); return@load }
        if (bmp == null || heroUrl != url) return@load
        incoming.bringToFront()
        incoming.alpha = 0f
        incoming.scaleX = 1.05f; incoming.scaleY = 1.05f
        incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.HERO).setInterpolator(Motion.enter).start()
        outgoing.animate().alpha(0f).setDuration(Motion.HERO).setInterpolator(Motion.standard).start()
    }
}
