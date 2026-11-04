package com.morgan.minimallauncher

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * Opening an app (or Settings, Watch...): Android zooms the app's own window out of the tile you pressed - the
 * real page, from its very first frame, with nothing in between - while the home screen zooms forward into that
 * tile and fades. Coming back, the home screen settles back out to its normal size.
 */
object LaunchTransition {
    private val ease = PathInterpolator(0.3f, 0f, 0f, 1f)
    private const val ZOOM = 1.12f

    private var zoomed: View? = null

    fun open(activity: Activity, from: View?, start: (android.os.Bundle?) -> Unit) {
        val options = from?.takeIf { it.width > 0 && it.height > 0 }?.let {
            try { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() } catch (e: Exception) { null }
        }
        // The home screen leans in towards the tile while the app grows out of it.
        val content = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        if (content != null && from != null) {
            val r = Rect(); val c = IntArray(2)
            if (from.getGlobalVisibleRect(r)) {
                content.getLocationOnScreen(c)
                content.pivotX = (r.exactCenterX() - c[0]); content.pivotY = (r.exactCenterY() - c[1])
                content.animate().cancel()
                content.animate().scaleX(ZOOM).scaleY(ZOOM).alpha(0f).setDuration(260).setInterpolator(ease).start()
                zoomed = content
            }
        }
        start(options)
    }

    /** Call when the screen comes back to the front: it settles back out from the tile it zoomed into. */
    fun close(activity: Activity) {
        val v = zoomed ?: return
        zoomed = null
        if (!v.isAttachedToWindow) return
        v.animate().cancel()
        v.scaleX = ZOOM; v.scaleY = ZOOM; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(ease).start()
    }

    /** Puts the screen straight back (e.g. the app couldn't be opened). */
    fun reset() {
        zoomed?.let { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f }
        zoomed = null
    }
}
