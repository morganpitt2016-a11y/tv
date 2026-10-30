package com.morgan.minimallauncher

import android.view.View
import android.view.animation.PathInterpolator

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

    /** Lift [v] when focused: a small grow and a soft shadow; settle back when not. */
    fun focus(v: View, focused: Boolean, scale: Float = 1.08f, liftDp: Float = 10f) {
        val d = v.resources.displayMetrics.density
        v.animate().cancel()
        v.animate()
            .scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
            .translationZ(if (focused) liftDp * d else 0f)
            .setInterpolator(focusEase)
            .setDuration(FOCUS)
            .start()
        GlassUi.spotlight(v, focused)
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
