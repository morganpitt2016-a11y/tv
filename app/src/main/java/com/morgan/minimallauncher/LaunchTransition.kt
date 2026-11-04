package com.morgan.minimallauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Opening an app (or Settings, Watch...): the tile's outline grows out of its place until it fills the screen,
 * the icon fades in place, and only then does the app take over - so the whole motion is always seen.
 * Coming back, the screen shrinks into the tile it came from.
 *
 * Smoothness: nothing is resized or re-laid-out while it moves. One full-screen surface is clipped to an
 * animated rounded outline (a cheap redraw), and the icon only changes alpha and scale.
 */
object LaunchTransition {
    private const val OPEN_MS = 300L
    private const val CLOSE_MS = 340L
    private val ease = PathInterpolator(0.3f, 0f, 0f, 1f)

    private class State(
        val root: FrameLayout, val surface: View, val icon: ImageView, val fill: ColorDrawable,
        val rect: Rect, val w: Int, val h: Int, val radius: Float, val avg: Int,
    ) {
        var progress = 0f
        val cur = Rect()
        var curRadius = radius
    }

    private var state: State? = null

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

        val radius = 10f * activity.resources.displayMetrics.density
        val root = FrameLayout(activity).apply { isClickable = true; isFocusable = false }
        val fill = ColorDrawable(avg)
        val surface = View(activity).apply { background = fill; clipToOutline = true }
        val icon = ImageView(activity).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(icon, FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top })
        content.addView(root, ViewGroup.LayoutParams(-1, -1))
        root.elevation = 100f

        val st = State(root, surface, icon, fill, r, content.width, content.height, radius, avg)
        surface.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(st.cur, st.curRadius) }
        }
        state = st
        apply(st, 0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS; interpolator = ease
            addUpdateListener { apply(st, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    // The screen is now one plain colour: the app appears over it with no jump.
                    try { start(ActivityOptions.makeCustomAnimation(activity, 0, android.R.anim.fade_out).toBundle()) }
                    catch (e: Exception) { start(null) }
                }
            })
            start()
        }
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    private fun apply(st: State, f: Float) {
        st.progress = f
        st.cur.set(lerp(st.rect.left, 0, f), lerp(st.rect.top, 0, f), lerp(st.rect.right, st.w, f), lerp(st.rect.bottom, st.h, f))
        st.curRadius = st.radius * (1f - f)
        st.surface.invalidateOutline()
        val k = ((f - 0.3f) / 0.7f).coerceIn(0f, 1f)
        st.fill.color = Color.rgb(lerp(Color.red(st.avg), 0, k), lerp(Color.green(st.avg), 0, k), lerp(Color.blue(st.avg), 0, k))
        st.icon.alpha = (1f - f / 0.45f).coerceIn(0f, 1f)
        val s = 1f + 0.1f * f
        st.icon.scaleX = s; st.icon.scaleY = s
    }

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

    fun reset() { state?.let { (it.root.parent as? ViewGroup)?.removeView(it.root) }; state = null }
}
