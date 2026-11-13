package com.morgan.minimallauncher

import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/**
 * One motion language for the whole launcher (tvOS-like): quick, decelerating
 * focus changes, calm fades, and panels that settle rather than bounce.
 * Durations are short so animation never holds up the remote.
 */
object Motion {
    /** Fast out, gentle settle - focus and selection. */
    val focusEase = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    /** Standard ease for fades and panels. */
    val standard = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    /** Things arriving on screen. */
    val enter = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    const val FOCUS = 180L
    const val FADE = 220L
    const val PANEL = 300L
    const val HERO = 650L

    // Springs, not timed tweens: motion that is interrupted (focus racing across a row)
    // carries its velocity on instead of restarting, which is what makes iOS feel fluid.
    private class Springs(private val v: View) {
        private fun make(prop: DynamicAnimation.ViewProperty, rest: Float) =
            SpringAnimation(v, prop).setSpring(SpringForce(rest).setStiffness(420f).setDampingRatio(0.78f)).apply { minimumVisibleChange = 1f / 500f }
        val sx = make(DynamicAnimation.SCALE_X, 1f)
        val sy = make(DynamicAnimation.SCALE_Y, 1f)
        val z = make(DynamicAnimation.TRANSLATION_Z, 0f).apply { minimumVisibleChange = 0.5f }
    }
    private val springs = WeakHashMap<View, Springs>()

    /** Spring [v] to [scale] (and [lift] px of elevation). */
    fun spring(v: View, scale: Float, lift: Float = 0f) {
        v.animate().cancel()
        val s = springs.getOrPut(v) { Springs(v) }
        s.sx.animateToFinalPosition(scale)
        s.sy.animateToFinalPosition(scale)
        s.z.animateToFinalPosition(0f)          // no lift shadow: on translucent glass it shows through as a ghost edge
    }

    /** Lift [v] when focused: a small grow and a soft shadow; settle back when not. */
    fun focus(v: View, focused: Boolean, scale: Float = 1.08f, liftDp: Float = 10f) {
        val d = v.resources.displayMetrics.density
        spring(v, if (focused) scale else 1f, if (focused) liftDp * d else 0f)
        GlassUi.spotlight(v, focused)
        tilt(v, focused)
        if (focused) gleam(v)
    }

    // ---------- tvOS touches: a tilt that settles, and a sheen sweeping across ----------

    /** The card leans in as focus arrives and settles flat - the "parallax" feel of tvOS on a D-pad. */
    fun tilt(v: View, focused: Boolean) {
        if (!focused) { v.rotationX = 0f; v.rotationY = 0f; return }
        v.cameraDistance = 12_000f * v.resources.displayMetrics.density
        v.rotationX = 6f; v.rotationY = -4f
        SpringAnimation(v, DynamicAnimation.ROTATION_X).setSpring(SpringForce(0f).setStiffness(260f).setDampingRatio(0.55f)).start()
        SpringAnimation(v, DynamicAnimation.ROTATION_Y).setSpring(SpringForce(0f).setStiffness(260f).setDampingRatio(0.55f)).start()
    }

    /** A soft diagonal light that sweeps across a focused card once. */
    fun gleam(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        val g = (v.foreground as? Gleam) ?: Gleam().also { v.foreground = it }
        android.animation.ValueAnimator.ofFloat(-0.4f, 1.4f).apply {
            duration = 750; interpolator = standard
            addUpdateListener { g.t = it.animatedValue as Float; g.invalidateSelf() }
            start()
        }
    }

    class Gleam : android.graphics.drawable.Drawable() {
        var t = -1f
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: android.graphics.Canvas) {
            if (t < -0.35f || t > 1.35f) return
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            val x = b.left + t * (w + h) - h
            paint.shader = android.graphics.LinearGradient(x, b.top.toFloat(), x + h * 0.9f, b.top + h, intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Standard focus behaviour plus an optional callback when focus arrives. */
    fun focusable(v: View, scale: Float = 1.08f, liftDp: Float = 10f, onFocus: (() -> Unit)? = null) {
        v.setOnFocusChangeListener { view, has ->
            focus(view, has, scale, liftDp)
            if (has) onFocus?.invoke()
        }
    }

    fun fadeIn(v: View, duration: Long = FADE) {
        v.animate().cancel()
        v.animate().alpha(1f).setInterpolator(standard).setDuration(duration).start()
    }
}
