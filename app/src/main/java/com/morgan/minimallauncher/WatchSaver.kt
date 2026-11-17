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

internal fun WatchActivity.armSaver() { handler.removeCallbacks(showSaver); handler.postDelayed(showSaver, SAVER_MS) }

internal fun WatchActivity.startSaver() {
    if (saver != null || isFinishing || detail != null) return
    // Typing on the keyboard or a pop-up open: those keys never reach this screen, so wait rather than cover them.
    if (currentFocus is android.widget.EditText || !hasWindowFocus()) { armSaver(); return }
    // What you're watching first, then your list, then what's featured.
    val slides = ResumeStore.forTab(this, "home").filter { it.image != null }.map { Triple(it.image!!, it.title, it as Any) } +
        WatchCatalog.myList(this).mapNotNull { t -> (t.backdrop ?: t.poster)?.let { Triple(it, t.name, t as Any) } } +
        featured.mapNotNull { t -> t.backdrop?.let { Triple(it, t.name, t as Any) } }
    if (slides.isEmpty()) return
    stopTrailer()
    val c = this
    val sv = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
    val a = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val b = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    sv.addView(a, FrameLayout.LayoutParams(-1, -1)); sv.addView(b, FrameLayout.LayoutParams(-1, -1))
    sv.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000)) },
        FrameLayout.LayoutParams(-1, dp(c, 220f), Gravity.BOTTOM))
    val title = GlassUi.text(c, "", 30f, R.font.inter_semibold)
    val hint = GlassUi.text(c, "", 13f, R.font.inter_medium, 0xB3FFFFFF.toInt())
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(hint) }
    sv.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(c, 56f), 0, 0, dp(c, 44f)) })
    root.addView(sv, FrameLayout.LayoutParams(-1, -1))
    saver = sv
    sv.animate().alpha(1f).setDuration(900).start()
    var i = 0; var useA = true
    fun next() {
        if (saver !== sv) return
        val (img, name, what) = slides[i % slides.size]; i++
        val into = if (useA) a else b; val out = if (useA) b else a; useA = !useA
        ImageLoader.load(into, img, ImageLoader.HERO, fade = false) {
            into.scaleX = 1f; into.scaleY = 1f
            into.animate().alpha(1f).setDuration(1500).start(); out.animate().alpha(0f).setDuration(1500).start()
            into.animate().scaleX(1.08f).scaleY(1.08f).setDuration(9000).start()          // slow drift, like Apple's screensavers
        }
        title.text = name
        hint.text = if (what is ResumeStore.Item) "Press OK to carry on watching" else "Press any button to come back"
        sv.setTag(R.id.card_info, what as? ResumeStore.Item)
        handler.postDelayed({ next() }, 9000)
    }
    next()

internal fun WatchActivity.hideSaver() {
    val sv = saver ?: return
    saver = null
    sv.animate().alpha(0f).setDuration(300).withEndAction { root.removeView(sv) }.start()
    armSaver()
