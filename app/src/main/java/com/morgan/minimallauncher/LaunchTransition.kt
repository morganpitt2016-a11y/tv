package com.morgan.minimallauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Opening an app (or Settings, Watch...): the tile's outline grows out of its place until it fills the screen,
 * while the icon stays sharp and fades. Coming back, the screen shrinks into the tile it came from. Only the
 * outline moves (it is never a stretched picture), so edges and corners stay crisp.
 */
object LaunchTransition {
    private const val OPEN_MS = 420L
    private const val CLOSE_MS = 380L
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val main = Handler(Looper.getMainLooper())

    private class State(
        val root: FrameLayout, val card: FrameLayout, val img: ImageView, val bg: GradientDrawable,
        val rect: Rect, val w: Int, val h: Int, val radius: Float, val avg: Int,
    ) { var progress = 0f }

    private var state: State? = null

    /** Grows [from] to fill the screen, then calls [start] (with options that suppress Android's own animation). */
    fun open(activity: Activity, from: View?, start: (android.os.Bundle?) -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (from == null || from.width <= 0 || from.height <= 0 || content == null || state != null || content.width <= 0) { start(null); return }
        val r = Rect()
        if (!from.getGlobalVisibleRect(r)) { start(null); return }
        val cl = IntArray(2); content.getLocationOnScreen(cl)
        r.offset(-cl[0], -cl[1])
        val bmp = try {
            Bitmap.createBitmap(from.width, from.height, Bitmap.Config.ARGB_8888).also { from.draw(Canvas(it)) }
        } catch (e: Exception) { start(null); return }
        val avg = try { Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt() } catch (e: Exception) { 0xFF2A2A2E.toInt() }

        val d = activity.resources.displayMetrics.density
        val radius = 10f * d
        val bg = GradientDrawable().apply { setColor(avg); cornerRadius = radius }
        val root = FrameLayout(activity).apply { isClickable = true; isFocusable = false }
        val card = FrameLayout(activity).apply { background = bg; clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        val img = ImageView(activity).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        card.addView(img, FrameLayout.LayoutParams(r.width(), r.height(), Gravity.CENTER))      // the icon keeps its own size
        root.addView(card, FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top })
        content.addView(root, ViewGroup.LayoutParams(-1, -1))
        root.elevation = 100f

        val st = State(root, card, img, bg, r, content.width, content.height, radius, avg)
        state = st
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS; interpolator = ease
            addUpdateListener { apply(st, it.animatedValue as Float) }
            start()
        }
        // The app starts underneath while the outline is still growing.
        main.postDelayed({
            try { start(ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()) }
            catch (e: Exception) { start(null) }
        }, 160)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun apply(st: State, f: Float) {
        st.progress = f
        val lp = st.card.layoutParams as FrameLayout.LayoutParams
        val l = lerp(st.rect.left.toFloat(), 0f, f); val t = lerp(st.rect.top.toFloat(), 0f, f)
        val r = lerp(st.rect.right.toFloat(), st.w.toFloat(), f); val b = lerp(st.rect.bottom.toFloat(), st.h.toFloat(), f)
        lp.leftMargin = l.toInt(); lp.topMargin = t.toInt(); lp.width = (r - l).toInt(); lp.height = (b - t).toInt()
        st.card.layoutParams = lp
        st.bg.cornerRadius = st.radius * (1f - f)
        // Settles from the tile's own colour to black, which is what an app's first frame usually is.
        st.bg.setColor(blend(st.avg, 0xFF000000.toInt(), ((f - 0.25f) / 0.75f).coerceIn(0f, 1f)))
        val iconFade = (1f - (f / 0.5f)).coerceIn(0f, 1f)
        st.img.alpha = iconFade
        val s = 1f + 0.12f * f
        st.img.scaleX = s; st.img.scaleY = s
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.argb(255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())

    /** Call when the launcher comes back to the front: shrinks the screen back into the tile it came from. */
    fun close(activity: Activity) {
        val st = state ?: return
        if (!st.root.isAttachedToWindow) { state = null; return }
        state = null
        ValueAnimator.ofFloat(st.progress.coerceAtLeast(0.001f), 0f).apply {
            duration = CLOSE_MS; interpolator = ease
            addUpdateListener { apply(st, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { (st.root.parent as? ViewGroup)?.removeView(st.root) }
            })
            start()
        }
    }

    /** Drops any leftover overlay immediately (e.g. when the screen is rebuilt). */
    fun reset() { state?.let { (it.root.parent as? ViewGroup)?.removeView(it.root) }; state = null }
}
