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

internal fun WatchActivity.row(key: String, title: String, height: Float = 196f, icon: String? = null): Row {
    val c = this
    val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; tag = key }
    if (title.isNotEmpty()) {
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        if (icon != null) {
            val iv = ImageView(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(c, 5f).toFloat(); setColor(0x33FFFFFF) }
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            ImageLoader.load(iv, icon, ImageLoader.ICON)
            header.addView(iv, LinearLayout.LayoutParams(dp(c, 20f), dp(c, 20f)).apply { marginEnd = dp(c, 9f) })
        }
        header.addView(GlassUi.text(c, title, 15f, R.font.inter_semibold))
        box.addView(header, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, 10f) })
    }
    val list = EdgeStopRecyclerView(c).apply {
        layoutManager = object : LinearLayoutManager(c, HORIZONTAL, false) {
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                val dx = child.left - dp(c, 56f)
                if (dx != 0) parent.smoothScrollBy(dx, 0, DecelerateInterpolator(2f))
                return true
            }
        }
        clipChildren = false; clipToPadding = false
        setPadding(dp(c, 56f), dp(c, 12f), dp(c, 56f), dp(c, 8f))
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(6)
        (layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = 6
    }
    box.addView(list, LinearLayout.LayoutParams(-1, dp(c, height)))
    return Row(box, list)
}

/** A row of titles that loads itself; disappears if empty. */
internal fun WatchActivity.titleRow(
    token: Int, key: String, title: String, icon: String? = null, ranked: Boolean = false, quietFail: Boolean = false,
    more: (() -> Unit)? = null,
    load: ((List<WatchCatalog.Title>?) -> Unit) -> Unit,
): Row {
    val r = row(key, title, icon = icon, height = 196f)
    val moreCard: ((ViewGroup) -> Holder)? = if (more != null) { p -> seeMoreCard(p) } else null
    val adapter = CardAdapter(emptyList<WatchCatalog.Title>(), { p -> posterCard(p, ranked) }, more, moreCard) { h, t -> bindTitle(h, t) }
    r.list.adapter = SkeletonAdapter(104f, 156f)            // soft placeholders until the titles arrive
    val host = rows
    host.addView(r.view)
    load { raw ->
        if (containerToken[host] != token || isDestroyed) return@load
        val liked = raw?.filterNot { Ratings.disliked(this, it.name) }   // "Not for me" titles stay hidden
        // Each title once per page: a row only shows what earlier rows haven't (and goes if little is left).
        if (ranked) liked?.forEach { top10Ids.add(it.id) }
        val titles = if (liked == null || ranked || key == "results" || key == "loved" || key == "because" || key == "soon") liked
            else liked.partition { it.id !in shownTitles }.let { (fresh, seen) -> if (fresh.size >= 12) fresh else fresh + seen.take(12 - fresh.size) }
        titles?.forEach { shownTitles.add(it.id) }
        when {
            titles.isNullOrEmpty() && (quietFail || titles != null) -> host.removeView(r.view)
            titles == null -> { r.list.adapter = adapter; r.view.addView(GlassUi.text(this, "Couldn’t load - check the internet connection", 12f, R.font.inter_regular, 0x99FFFFFF.toInt()),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@titleRow, 56f) }) }
            else -> {
                r.list.adapter = adapter
                adapter.update(titles)
                // A collection page puts the highlight on its first title as soon as it arrives.
                if (focusFirstLoaded) { focusFirstLoaded = false; r.list.post { r.list.getChildAt(0)?.requestFocus() } }
            }
        }
    }
    return r
}

internal fun WatchActivity.staticTitleRow(key: String, title: String, titles: List<WatchCatalog.Title>): Row {
    val r = row(key, title)
    r.list.adapter = CardAdapter(titles, { p -> posterCard(p) }) { h, t -> bindTitle(h, t) }
    return r
}

// ---------- Continue Watching ----------

internal fun WatchActivity.bindResume(h: Holder, item: ResumeStore.Item) {
    val left = if (item.durSec > 0) ((item.durSec - item.posSec) / 60).coerceAtLeast(1) else 0
    h.label.text = item.title
    h.sub?.text = when {
        item.kind == "live" -> "Live"
        item.kind == "episode" && item.posSec == 0 -> "Up next · S${item.season} E${item.episode}"
        item.kind == "episode" -> "S${item.season} E${item.episode}  ·  $left min left"
        else -> "$left min left"
    }
    h.progress?.let { bar -> bar.post { bar.layoutParams.width = if (item.durSec > 0) (h.root.width * item.posSec.toFloat() / item.durSec).toInt() else 0; bar.requestLayout() } }
    ImageLoader.load(h.image, item.image, ImageLoader.CARD)
    h.root.contentDescription = "Continue ${item.title}"
    h.root.setOnClickListener { openResume(item) }
    // Hold OK: take it off the row (or start it again).
    h.root.setOnLongClickListener { resumeOptions(item); true }
    focusEffect(h.root) { }
}

internal fun WatchActivity.resumeOptions(item: ResumeStore.Item) {
    val rowName = if (item.kind == "live") "Recently Watched" else "Continue Watching"
    val d = GlassDialog(this).title(item.title)
    if (item.kind != "live" && item.posSec > 0) d.button("Start from the beginning", GlassDialog.Style.BOLD) {
        Prefs.setInt(this, WatchHistory.posKey(this, item.url), 0)
        openResume(item.copy(posSec = 0))
    }
    d.button("Remove from $rowName", GlassDialog.Style.DESTRUCTIVE) {
        ResumeStore.remove(this, item.key)
        if (item.kind != "live") Prefs.setInt(this, WatchHistory.posKey(this, item.url), 0)
        refreshResumeRow()
        GlassUi.toast(this, "Removed from $rowName")
    }
    d.button("Cancel").show()
}

internal fun WatchActivity.openResume(it: ResumeStore.Item) {
    val app = applicationContext
    when (it.kind) {
        "live" -> PlayerActivity.play(this, Xtream.liveUrl(app, it.liveId), it.title, live = true, eyebrow = "LIVE",
            alt = Xtream.liveUrl(app, it.liveId, hls = true), liveId = it.liveId)
        "film" -> PlayerActivity.play(this, it.url, it.title, live = false, eyebrow = "FILM", image = it.image)
        else -> {
            val name = it.series ?: it.title
            // Rebuild the episode list so the next episode can follow on.
            Bg.run {
                val eps = try { Xtream.seriesEpisodes(app, name, 0)?.second } catch (e: Exception) { null }.orEmpty()
                val ep = eps.firstOrNull { e -> e.season == it.season && e.number == it.episode }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (ep != null) { lastEpisodes = eps; playEpisodeNamed(name, it.image, ep) }
                    else PlayerActivity.play(this, it.url, name, live = false, subtitle = "Season ${it.season}, Episode ${it.episode}", eyebrow = "SERIES",
                        series = name, image = it.image, season = it.season, episode = it.episode)
                }
            }
        }
    }
}

/** The row at the top of Home, Films, Shows and Live TV: click to carry on where you left off. */
internal fun WatchActivity.refreshResumeRow() {
    findRow("resume")?.let { rows.removeView(it) }
    if (tab != "home") return
    val items = ResumeStore.forTab(this, tab)
    if (items.isEmpty()) return
    val r = row("resume", if (tab == "live") "Recently Watched" else "Continue Watching", height = 150f)
    r.list.adapter = CardAdapter(items, { p -> wideCard(p) }) { h, item -> bindResume(h, item) }
    rows.addView(r.view, 0)
}

internal fun WatchActivity.findRow(key: String): View? = (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == key }

internal fun WatchActivity.rowOf(v: View): View? {
    var p: Any? = v.parent
    var last: View = v
    while (p is View) {
        if (p === rows) return last
        last = p
        p = p.parent
    }
    return null
}

internal fun WatchActivity.firstItem(): View? {
    for (i in 0 until rows.childCount) {
        val r = rows.getChildAt(i) as? ViewGroup ?: continue
        if (r.getTag(R.id.blur_radius) == "guide") return r.getChildAt(1)
        val list = (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView ?: continue
        list.getChildAt(0)?.let { return it }
    }
    return null
}
