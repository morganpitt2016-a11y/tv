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

/** The last card of a row: "See More". */
internal fun WatchActivity.seeMoreCard(parent: ViewGroup): Holder {
    val c = parent.context
    val h = card(parent, 104f, 156f)
    (h.root.background as? GradientDrawable)?.setColor(0x26FFFFFF)
    h.label.text = "See More\n›"
    h.label.textSize = 13f
    h.label.typeface = GlassUi.font(c, R.font.inter_semibold)
    h.label.setTextColor(0xFFFFFFFF.toInt())
    h.label.setLineSpacing(dp(c, 4f).toFloat(), 1f)
    h.root.contentDescription = "See More"
    focusEffect(h.root) { }
    return h

internal fun WatchActivity.card(parent: ViewGroup, w: Float, h: Float, gap: Float = 14f): Holder {
    val c = parent.context
    val root = FrameLayout(c).apply {
        layoutParams = RecyclerView.LayoutParams(dp(c, w), dp(c, h)).apply { marginEnd = dp(c, gap) }
        background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF1E1D22.toInt()) }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        foreground = ContextCompat.getDrawable(c, R.drawable.tile_bezel)
        isFocusable = true; isClickable = true
    }
    val label = GlassUi.text(c, "", 11f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply {
        gravity = Gravity.CENTER; setPadding(dp(c, 8f), 0, dp(c, 8f), 0); maxLines = 3
    }
    root.addView(label, FrameLayout.LayoutParams(-1, -1))
    val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    root.addView(image, FrameLayout.LayoutParams(-1, -1))
    return Holder(root, image, label)

/** Poster; Top 10 posters get a big rank number in the corner. */
internal fun WatchActivity.posterCard(parent: ViewGroup, ranked: Boolean = false): Holder {
    val h = card(parent, 104f, 156f, gap = if (ranked) 22f else 14f)
    h.root.setTag(R.id.card_poster, true)
    if (ranked) {
        val c = parent.context
        val n = GlassUi.text(c, "", 44f, R.font.inter_semibold).apply {
            GlassUi.flex(this, 800, 60)
            setShadowLayer(dp(c, 8f).toFloat(), 0f, 0f, 0xE6000000.toInt())
            includeFontPadding = false
        }
        h.root.addView(n, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(c, 6f), 0, 0, dp(c, 2f)) })
        h.root.setTag(R.id.blur_anim, n)
    }
    return h

/** 16:9 picture card with title, channel/time and a progress line. */
internal fun WatchActivity.wideCard(parent: ViewGroup): Holder {
    val base = card(parent, 200f, 112f)
    val c = parent.context
    base.root.addView(View(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
    }, FrameLayout.LayoutParams(-1, dp(c, 70f), Gravity.BOTTOM))
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    val title = GlassUi.text(c, "", 12.5f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val sub = GlassUi.text(c, "", 10f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    col.addView(title); col.addView(sub, lp(2f))
    base.root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(c, 10f), 0, dp(c, 10f), dp(c, 9f)) })
    val bar = View(c).apply { setBackgroundColor(0xE6FFFFFF.toInt()) }
    base.root.addView(bar, FrameLayout.LayoutParams(0, dp(c, 3f), Gravity.BOTTOM))
    base.label.visibility = View.GONE
    return Holder(base.root, base.image, title, sub, bar)

internal fun WatchActivity.badgeFor(t: WatchCatalog.Title): String? {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return when {
        t.id in top10Ids -> "TOP 10"
        t.type == "SHOW" && ResumeStore.all(this).any { it.series == t.name && it.posSec == 0 && System.currentTimeMillis() - it.at < 7 * 86_400_000L } -> "NEW EPISODE"
        t.year >= year -> if (t.type == "SHOW") "NEW SERIES" else "NEW"
        (t.imdb ?: 0.0) >= 8.3 -> "ACCLAIMED"
        else -> null
    }

internal fun WatchActivity.bindTitle(h: Holder, t: WatchCatalog.Title, inDetail: Boolean = false) {
    h.label.text = t.name
    h.root.contentDescription = t.name
    ImageLoader.load(h.image, t.poster)
    h.root.setOnClickListener { openDetail(t, h.root) }
    // Badge in the corner (Netflix / Disney+ style).
    val c = h.root.context
    val badge = (h.root.getTag(R.id.blur_radius) as? TextView) ?: GlassUi.text(c, "", 8.5f, R.font.inter_semibold).apply {
        letterSpacing = 0.06f; setPadding(dp(c, 6f), dp(c, 2f), dp(c, 6f), dp(c, 2f))
        background = GradientDrawable().apply { cornerRadius = dp(c, 4f).toFloat(); setColor(0xE6E50914.toInt()) }
        h.root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(c, 6f), dp(c, 6f), 0, 0) })
        h.root.setTag(R.id.blur_radius, this)
    }
    val b = badgeFor(t)
    badge.text = b.orEmpty(); badge.visibility = if (b == null) View.GONE else View.VISIBLE
    (badge.background as? GradientDrawable)?.setColor(when (b) { "TOP 10" -> 0xE6E50914.toInt(); "ACCLAIMED" -> 0xE6B8860B.toInt(); else -> 0xE60A84FF.toInt() })
    val poster = h.root.getTag(R.id.card_poster) == true && !inDetail
    if (h.root.getTag(R.id.card_poster) == true) collapseCard(h, t, reload = false)
    h.root.setOnFocusChangeListener { v, has ->
        Motion.focus(v, has, 1.1f, 14f)
        if (has && !inDetail) showTitleHero(t)
        if (!poster) return@setOnFocusChangeListener
        (v.getTag(R.id.card_expand) as? Runnable)?.let { handler.removeCallbacks(it) }
        if (has) {
            // Rest on it: it opens out into a wide preview with the backdrop and a little detail (Netflix).
            val r = Runnable { if (v.isFocused) expandCard(h, t) }
            v.setTag(R.id.card_expand, r); handler.postDelayed(r, 1500)
        } else collapseCard(h, t)
    }

internal fun WatchActivity.expandCard(h: Holder, t: WatchCatalog.Title) {
    val c = this
    val lp = h.root.layoutParams ?: return
    if (lp.width != dp(c, 104f)) return
    val rv = h.root.parent as? RecyclerView ?: return
    if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
    // Room for it to open to the right? (after the row slides it to the left edge, or where it is now)
    val room = rv.width - dp(c, 56f)
    val canSlide = rv.canScrollHorizontally(1) || h.root.left <= dp(c, 60f)
    if (!canSlide && h.root.left + dp(c, 262f) > room) return
    val to = dp(c, 262f)
    android.animation.ValueAnimator.ofInt(lp.width, to).apply {
        duration = 320; interpolator = Motion.focusEase
        addUpdateListener { lp.width = it.animatedValue as Int; h.root.layoutParams = lp }
        start()
    }
    // Opens out to the right from where it sits (no extra zoom), and the row lines it up at the left edge.
    h.root.pivotX = 0f
    Motion.spring(h.root, 1.0f)
    (h.root.parent as? RecyclerView)?.let { rv -> h.root.post { rv.smoothScrollBy(h.root.left - dp(c, 56f), 0) } }
    (t.backdrop ?: t.poster)?.let { ImageLoader.load(h.image, it, ImageLoader.CARD) }
    val info = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x80000000.toInt(), 0x00000000))
        setPadding(dp(c, 10f), 0, dp(c, 10f), dp(c, 9f)); alpha = 0f
    }
    info.addView(GlassUi.text(c, t.name, 13f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    info.addView(GlassUi.text(c, listOfNotNull(t.year.takeIf { it > 0 }?.toString(), t.imdb?.let { "IMDb %.1f".format(it) }, t.genres.firstOrNull()).joinToString("  ·  "),
        10f, R.font.inter_medium, 0xCCFFFFFF.toInt()))
    info.addView(GlassUi.text(c, "▶  OK to open", 10f, R.font.inter_semibold, 0xFFFFFFFF.toInt()), lp(4f))
    h.root.addView(info, FrameLayout.LayoutParams(-1, -1))
    h.root.setTag(R.id.card_info, info)
    info.animate().alpha(1f).setStartDelay(150).setDuration(250).start()

internal fun WatchActivity.collapseCard(h: Holder, t: WatchCatalog.Title, reload: Boolean = true) {
    val lp = h.root.layoutParams ?: return
    (h.root.getTag(R.id.card_info) as? View)?.let { h.root.removeView(it) }
    h.root.setTag(R.id.card_info, null)
    h.root.pivotX = h.root.width / 2f
    if (lp.width != dp(this, 104f)) {
        h.root.pivotX = dp(this, 52f).toFloat()
        lp.width = dp(this, 104f); h.root.layoutParams = lp
        if (reload) ImageLoader.load(h.image, t.poster)
    }

internal fun WatchActivity.bindApp(h: Holder, app: AppEntry) {
    h.label.text = app.label
    h.root.contentDescription = "Open ${app.label}"
    h.image.scaleType = ImageView.ScaleType.CENTER_CROP
    h.image.setPadding(0, 0, 0, 0)
    h.image.setImageBitmap(AppTiles.forApp(this, app, dp(this, 200f), dp(this, 120f)))

internal fun WatchActivity.focusEffect(v: View, onFocus: () -> Unit) {
    Motion.focusable(v, 1.1f, 14f, onFocus)
