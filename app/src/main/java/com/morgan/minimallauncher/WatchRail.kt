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

/** The horizontal list inside a row (if it is one). */
internal fun WatchActivity.rowList(v: View): RecyclerView? = (v as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView }

internal fun WatchActivity.railItems(): List<Pair<String, String>> =
    listOf("trend" to "Trending", "new" to "New", "best" to "Best Rated", "pop" to "Everything") + railGenres.map { "g_${it.first}" to it.second }

/** Opens the rail; picking a category jumps to its row. */
internal fun WatchActivity.openRail() {
    if (rail != null) return
    railReturn = currentFocus
    val c = this
    val panel = FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5101014.toInt(), 0xE6101014.toInt(), 0x00101014))
        alpha = 0f; translationX = -dp(c, 40f).toFloat()
    }
    val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 40f), dp(c, 70f), dp(c, 30f), dp(c, 40f)); clipChildren = false; clipToPadding = false }
    list.addView(GlassUi.text(c, if (tab == "films") "Films" else "Shows", 22f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(c, 12f); marginStart = dp(c, 14f) })
    // The category whose row you're on is highlighted.
    val here = rowOf(currentFocus ?: nav)?.tag as? String
    var focusMe: View? = null
    for ((key, label) in railItems()) {
        val item = GlassUi.text(c, label, 15f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 14f), 0, dp(c, 14f), 0)
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { v, has ->
                v.background = if (has) GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFFFFFFFF.toInt()) } else null
                (v as TextView).setTextColor(if (has) 0xFF000000.toInt() else if (key == here) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
                Motion.spring(v, if (has) 1.04f else 1f)
            }
            setOnClickListener { closeRail(); jumpToRow(key) }
            if (key == here) setTextColor(0xFFFFFFFF.toInt())
        }
        list.addView(item, LinearLayout.LayoutParams(dp(c, 200f), dp(c, 34f)).apply { bottomMargin = dp(c, 2f) })
        if (key == here) focusMe = item
    }
    val sv = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false; addView(list) }
    panel.addView(sv, FrameLayout.LayoutParams(dp(c, 300f), -1))
    root.addView(panel, FrameLayout.LayoutParams(dp(c, 340f), -1, Gravity.START))
    rail = panel
    panel.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Motion.focusEase).start()
    (focusMe ?: list.getChildAt(1))?.let { f -> f.post { f.requestFocus() } }
}

internal fun WatchActivity.closeRail() {
    val r = rail ?: return
    rail = null
    r.animate().alpha(0f).translationX(-dp(this, 40f).toFloat()).setDuration(180).withEndAction { root.removeView(r) }.start()
}

/** Scroll to a category's row and put the highlight on its first title (it loads if it hasn't yet). */
internal fun WatchActivity.jumpToRow(key: String) {
    // Rows further down are only built as you reach them: build up to this one first.
    var guard = 0
    while (findRow(key) == null && moreRowsFor[rows] != null && guard++ < 20) moreRowsFor[rows]?.invoke()
    val r = findRow(key)
    if (r == null) {
        // Not on the page (e.g. empty): show that category's whole collection instead.
        val g = key.removePrefix("g_")
        val name = railGenres.firstOrNull { it.first == g }?.second ?: return
        openCollection("$name ${if (tab == "films") "Films" else "Series"}", catalogue(shorts(), types = listOf(if (tab == "films") "MOVIE" else "SHOW"), genre = g))
        return
    }
    setHeroCollapsed(true)
    scroll.smoothScrollTo(0, r.top)
    r.post { ((r as? ViewGroup)?.getFocusables(View.FOCUS_DOWN)?.firstOrNull() ?: r).requestFocus() }
}
