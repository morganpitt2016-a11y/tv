package com.morgan.minimallauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Opening an app (or Settings, Watch...) grows its tile out to fill the screen; coming back shrinks it into
 * the tile it came from. The tile is the same picture throughout, so it reads as one object moving.
 */
object LaunchTransition {
    private const val OPEN_MS = 380L
    private const val CLOSE_MS = 360L
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val main = Handler(Looper.getMainLooper())

    private class State(val root: FrameLayout, val card: ImageView, val scrim: View, val rect: Rect, val scale: Float, val tx: Float, val ty: Float, val radius: Float) {
        var progress = 0f
    }
    private var state: State? = null

    /** Grows [from] to fill the screen, then calls [start] (with options that suppress Android's own animation). */
    fun open(activity: Activity, from: View?, start: (android.os.Bundle?) -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (from == null || from.width <= 0 || from.height <= 0 || content == null || state != null) { start(null); return }
        val r = Rect()
        if (!from.getGlobalVisibleRect(r)) { start(null); return }
        val cl = IntArray(2); content.getLocationOnScreen(cl)
        r.offset(-cl[0], -cl[1])
        val bmp = try {
            Bitmap.createBitmap(from.width, from.height, Bitmap.Config.ARGB_8888).also { from.draw(Canvas(it)) }
        } catch (e: Exception) { start(null); return }

        val d = activity.resources.displayMetrics.density
        val radius = 10f * d
        val root = FrameLayout(activity).apply { isClickable = true; isFocusable = false }
        val scrim = View(activity).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        val card = ImageView(activity).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
            clipToOutline = true
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(card, FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top })
        content.addView(root, ViewGroup.LayoutParams(-1, -1))
        root.elevation = 100f

        val s = maxOf(content.width.toFloat() / r.width(), content.height.toFloat() / r.height())
        val tx = content.width / 2f - r.exactCenterX()
        val ty = content.height / 2f - r.exactCenterY()
        val st = State(root, card, scrim, r, s, tx, ty, radius)
        state = st
        apply(st, 0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS; interpolator = ease
            addUpdateListener { apply(st, it.animatedValue as Float) }
            start()
        }
        // The app starts underneath while the tile is still growing.
        main.postDelayed({
            try { start(ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()) }
            catch (e: Exception) { start(null) }
        }, 140)
    }

    private fun apply(st: State, f: Float) {
        st.progress = f
        val sc = 1f + (st.scale - 1f) * f
        st.card.pivotX = st.card.width / 2f; st.card.pivotY = st.card.height / 2f
        st.card.scaleX = sc; st.card.scaleY = sc
        st.card.translationX = st.tx * f; st.card.translationY = st.ty * f
        val rad = st.radius * (1f - f) / sc.coerceAtLeast(1f)
        st.card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, (st.radius * (1f - f)) / sc + rad * 0f) }
        }
        st.card.invalidateOutline()
        st.scrim.alpha = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
    }

    /** Call when the launcher comes back to the front: shrinks the tile back into place, if one was opened. */
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
