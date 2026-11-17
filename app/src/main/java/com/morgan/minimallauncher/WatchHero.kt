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
}

/** Live TV: just a doorway to the guide (OK or Down opens it), so moving past it loads nothing. */
internal fun WatchActivity.showLiveTab() {
    hideSearchBar()
    tab = "live"
    for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "live"
    ++buildToken
    freshRows()
    setHeroImage(null)
    val now = LiveGuide.nowNext(this, "BBC One")
    showPlainHero("LIVE TV", "TV Guide", "Press OK or ↓ to open the guide - every channel, what’s on now and next, pause and rewind." +
        (now?.let { "\n\nBBC One: $it" } ?: ""))
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
    if (!::trailerHost.isInitialized) return
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
    heroMeta.text = metaLine(t, null)
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

internal fun WatchActivity.setHeroImage(url: String?) {
    if (url == heroUrl) return
    heroUrl = url
    val incoming = if (heroShowingA) heroB else heroA
    val outgoing = if (heroShowingA) heroA else heroB
    heroShowingA = !heroShowingA
    if (url == null) { outgoing.animate().alpha(0f).setDuration(500).start(); return }
    ImageLoader.load(incoming, url, ImageLoader.HERO, fade = false) { bmp ->
        if (bmp == null || heroUrl != url) return@load
        incoming.bringToFront()
        incoming.alpha = 0f
        incoming.scaleX = 1.05f; incoming.scaleY = 1.05f
        incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.HERO).setInterpolator(Motion.enter).start()
        outgoing.animate().alpha(0f).setDuration(Motion.HERO).setInterpolator(Motion.standard).start()
    }
}
