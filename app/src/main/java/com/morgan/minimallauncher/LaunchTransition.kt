package com.morgan.minimallauncher

import android.app.Activity
import android.app.ActivityOptions
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Opening an app (or Settings, Watch...): the tile gives a quick press-bounce so you know it registered, and
 * Android zooms the app's own window out of that tile as soon as the app is ready. The home screen itself
 * stays put - nothing dims or zooms while an app is starting, so there is never a strange pause.
 */
object LaunchTransition {

    fun open(activity: Activity, from: View?, start: (android.os.Bundle?) -> Unit) {
        val options = from?.takeIf { it.width > 0 && it.height > 0 }?.let {
            try { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() } catch (e: Exception) { null }
        }
        from?.let { v ->
            val sx = v.scaleX; val sy = v.scaleY
            v.animate().cancel()
            v.animate().scaleX(sx * 0.94f).scaleY(sy * 0.94f).setDuration(90).withEndAction {
                v.animate().scaleX(sx).scaleY(sy).setInterpolator(OvershootInterpolator(2f)).setDuration(220).start()
            }.start()
        }
        start(options)
    }

    fun close(activity: Activity) {}
    fun reset() {}
}
