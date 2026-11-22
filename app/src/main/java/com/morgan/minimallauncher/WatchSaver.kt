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
    // What you're watching first, then your list, then what's featured - always the wide artwork when there is one.
    val slides = ResumeStore.forTab(this, "home").mapNotNull { r ->
        val name = r.series ?: r.title
        (resumeTitles[name]?.backdrop ?: Prefs.string(this, "rart_$name") ?: r.image)?.let { Triple(it, name, r as Any) }
    } + WatchCatalog.myList(this).mapNotNull { t -> (t.backdrop ?: t.poster)?.let { Triple(it, t.name, t as Any) } } +
        featured.mapNotNull { t -> t.backdrop?.let { Triple(it, t.name, t as Any) } }
    if (slides.isEmpty()) return
    stopTrailer()
    val c = this
    val sv = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
    // Two slots to cross-fade between. Each: a blurred, dimmed fill behind, and the picture itself.
    fun slot() = FrameLayout(c).apply {
        addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f
            if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60f, 60f, android.graphics.Shader.TileMode.CLAMP))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(ImageView(c).apply { scaleType = ImageView.ScaleType.MATRIX }, FrameLayout.LayoutParams(-1, -1))
    }
    val a = slot(); val b = slot().apply { alpha = 0f }
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
    var advance = Runnable {}
    fun next() {
        if (saver !== sv) return
        val (img, name, what) = slides[i % slides.size]; i++
        val into = if (useA) a else b; val out = if (useA) b else a; useA = !useA
        val back = into.getChildAt(0) as ImageView; val pic = into.getChildAt(1) as ImageView
        ImageLoader.load(back, img, ImageLoader.HERO, fade = false)
        val turn = i
        ImageLoader.load(pic, img, ImageLoader.HERO, fade = false) { bmp ->
            if (saver !== sv || turn != i) return@load
            // No picture: on to the next one rather than showing this name over the last picture.
            if (bmp == null) { useA = !useA; handler.removeCallbacks(advance); handler.postDelayed(advance, 300); return@load }
            frameArt(pic, bmp.width, bmp.height, sv.width, sv.height)
            into.animate().alpha(1f).setDuration(1500).start(); out.animate().alpha(0f).setDuration(1500).start()
            // Slow drift, like Apple's screensavers - zooming from the upper third, where the faces are.
            pic.scaleX = 1f; pic.scaleY = 1f; pic.pivotX = sv.width / 2f; pic.pivotY = sv.height * 0.3f
            pic.animate().scaleX(1.05f).scaleY(1.05f).setDuration(9000).start()
            // The name changes with its picture (it used to change first, so a slow picture showed under the next name).
            col.animate().cancel()
            col.animate().alpha(0f).setDuration(400).withEndAction {
                title.text = name
                hint.text = if (what is ResumeStore.Item) "Press OK to carry on watching" else "Press any button to come back"
                col.animate().alpha(1f).setDuration(700).start()
            }.start()
            sv.setTag(R.id.card_info, what as? ResumeStore.Item)
        }
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, 9000)
    }
    advance = Runnable { next() }
    next()
}

/**
 * Places artwork on the full screen without cutting heads off: wide pictures fill the screen with the crop kept to the
 * upper third (where faces are); tall posters are shown whole, centred, over their own blurred fill.
 */
private fun frameArt(iv: ImageView, bw: Int, bh: Int, vw: Int, vh: Int) {
    if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return
    val m = android.graphics.Matrix()
    val wide = bw.toFloat() / bh >= 1.3f
    if (wide) {
        val s = maxOf(vw.toFloat() / bw, vh.toFloat() / bh)
        val extraY = bh * s - vh
        m.setScale(s, s)
        m.postTranslate((vw - bw * s) / 2f, -extraY * 0.25f)      // keep more of the top than the bottom
    } else {
        val s = minOf(vw.toFloat() / bw, vh.toFloat() / bh) * 0.92f
        m.setScale(s, s)
        m.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f)
    }
    iv.imageMatrix = m
}

internal fun WatchActivity.hideSaver() {
    val sv = saver ?: return
    saver = null
    sv.animate().alpha(0f).setDuration(300).withEndAction { root.removeView(sv) }.start()
    armSaver()
}
