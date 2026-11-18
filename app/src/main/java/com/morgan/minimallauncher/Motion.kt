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

    /** The last D-pad direction pressed (set for every screen by UiSounds' key hook): which way focus is travelling. */
    @Volatile var lastKey = 0
    private fun dir(): Pair<Float, Float> = when (lastKey) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
        android.view.KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
        else -> 0.6f to 0.6f
    }

    private fun settle(v: View, prop: DynamicAnimation.ViewProperty, stiffness: Float = 240f, damping: Float = 0.5f) =
        SpringAnimation(v, prop).setSpring(SpringForce(0f).setStiffness(stiffness).setDampingRatio(damping)).start()

    /**
     * tvOS parallax on a D-pad: the card arrives leaning the way you moved and settles flat with a little bounce,
     * while the picture inside slides the other way and back - the layered "depth" of an Apple TV poster.
     */
    fun tilt(v: View, focused: Boolean) {
        // Only pictures inside a clipped card (posters, tiles) - not icons in buttons.
        val pic = (v as? android.view.ViewGroup)?.takeIf { it.clipToOutline }?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is android.widget.ImageView } }
        if (!focused) {
            v.rotationX = 0f; v.rotationY = 0f
            pic?.animate()?.scaleX(1f)?.scaleY(1f)?.translationX(0f)?.translationY(0f)?.setDuration(200)?.start()
            return
        }
        val d = v.resources.displayMetrics.density
        val (dx, dy) = dir()
        v.cameraDistance = 12_000f * d
        // Lean with the movement: moving right, the right edge dips away first.
        v.rotationY = 7f * dx
        v.rotationX = -6f * dy
        settle(v, DynamicAnimation.ROTATION_X); settle(v, DynamicAnimation.ROTATION_Y)
        if (pic != null && pic.width > 0) {
            pic.animate().cancel()
            // Slightly larger so the slide never shows an edge; it stays at this size while focused, as on tvOS.
            pic.scaleX = 1.06f; pic.scaleY = 1.06f
            pic.translationX = -6f * d * dx; pic.translationY = -5f * d * dy
            settle(pic, DynamicAnimation.TRANSLATION_X, 200f, 0.6f); settle(pic, DynamicAnimation.TRANSLATION_Y, 200f, 0.6f)
        }
    }

    /** A soft diagonal light that sweeps across a focused card once, in the direction you moved. */
    fun gleam(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        val g = (v.foreground as? Gleam) ?: Gleam().also { v.foreground = it }
        val back = dir().first < 0f || dir().second < 0f
        android.animation.ValueAnimator.ofFloat(if (back) 1.4f else -0.4f, if (back) -0.4f else 1.4f).apply {
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
