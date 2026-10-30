package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

/**
 * Rotating photo wallpaper: real mountain/lake landscape photos (from
 * Unsplash, Unsplash License) bundled in the app and listed in
 * res/values/wallpapers.xml. Crossfades to the next one every few minutes
 * with a slow zoom. No network needed.
 */
class WallpaperRotator(
    private val context: Context,
    private val viewA: ImageView,
    private val viewB: ImageView,
    /** Called with true once a photo is showing (hide the painted scene). */
    private val onPhotoShowing: (Boolean) -> Unit,
) {
    companion object {
        private const val ROTATE_EVERY_MS = 5 * 60 * 1000L
        private const val FADE_MS = 1800L
        private const val ZOOM_TO = 1.06f

        /** The photo currently on the home screen; Settings/Apps blur it as their backdrop. */
        @JvmStatic var currentRes: Int = R.drawable.wp_01
    }

    private val handler = Handler(Looper.getMainLooper())
    private val photos: List<Int> = context.resources.obtainTypedArray(R.array.wallpapers).let { arr ->
        List(arr.length()) { arr.getResourceId(it, 0) }.filter { it != 0 }.also { arr.recycle() }
    }
    // The first photo in the list always shows first, then the rest shuffled.
    private var order = photos.take(1) + photos.drop(1).shuffled()
    private var index = 0
    private var showingA = false
    private var running = false

    private val tick = Runnable { showNext() }

    fun start() {
        if (running || photos.isEmpty()) return
        running = true
        showNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        viewA.animate().cancel()
        viewB.animate().cancel()
    }

    private fun showNext() {
        if (!running) return
        if (index >= order.size) {
            order = photos.shuffled()
            index = 0
        }
        val res = order[index++]
        currentRes = res
        Thread {
            val bitmap: Bitmap? = try {
                BitmapFactory.decodeResource(context.resources, res)
            } catch (e: OutOfMemoryError) {
                null
            }
            handler.post {
                if (!running) return@post
                if (bitmap != null) crossfadeTo(bitmap)
                handler.postDelayed(tick, ROTATE_EVERY_MS)
            }
        }.start()
    }

    private fun crossfadeTo(bitmap: Bitmap) {
        val incoming = if (showingA) viewB else viewA
        val outgoing = if (showingA) viewA else viewB
        showingA = !showingA

        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.bringToFront() // only reorders within the photo layer
        onPhotoShowing(true)

        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            outgoing.animate().cancel()
            outgoing.alpha = 0f
            outgoing.setImageDrawable(null)
        }.start()
        // Slow zoom across the photo's time on screen.
        incoming.animate().scaleX(ZOOM_TO).scaleY(ZOOM_TO).setDuration(ROTATE_EVERY_MS).start()
    }
}
