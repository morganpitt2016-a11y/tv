package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView

/**
 * Rotating photo wallpaper: real landscape photos (Wikimedia Commons)
 * bundled in the app and listed in res/values/wallpapers.xml. Crossfades
 * to the next one every few minutes with a slow zoom. No network needed.
 *
 * Some photos have a "front" cut-out (the land with the sky removed,
 * listed in wallpaper_fronts). It sits above the clock and zooms in step
 * with the photo, so mountain tops pass in front of the digits.
 */
class WallpaperRotator(
    private val context: Context,
    private val viewA: ImageView,
    private val viewB: ImageView,
    private val frontA: ImageView,
    private val frontB: ImageView,
    /** Called with true once a photo is showing (hide the painted scene). */
    private val onPhotoShowing: (Boolean) -> Unit,
) {
    companion object {
        private const val ROTATE_EVERY_MS = 5 * 60 * 1000L
        private const val FADE_MS = 2600L
        private const val ZOOM_TO = 1.06f

        /** The photo currently on the home screen; Settings/Apps blur it as their backdrop. */
        @JvmStatic var current: WallpaperLibrary.Photo = WallpaperLibrary.bundled[2]
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Called with each new photo as it starts to show (the dock blurs it for its glass). */
    var onNewPhoto: ((Bitmap) -> Unit)? = null
    private var photos: List<WallpaperLibrary.Photo> = WallpaperLibrary.forProfile(context)
    // The first photo in the list always shows first, then the rest shuffled.
    private var order = photos.take(1) + photos.drop(1).shuffled()
    private var index = 0
    private var showingA = false
    private var running = false
    private var loading = false

    private val tick = Runnable { showNext() }

    fun start() {
        if (running) return
        // The profile (or its wallpaper choice) may have changed while away.
        val latest = WallpaperLibrary.forProfile(context)
        if (latest != photos) {
            photos = latest
            order = photos.take(1) + photos.drop(1).shuffled()
            index = 0
        }
        running = true
        showNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        listOf(viewA, viewB, frontA, frontB).forEach { it.animate().cancel() }
    }

    /** Skip to the next photo now (quick settings). */
    fun next() {
        if (!running || loading) return
        handler.removeCallbacks(tick)
        showNext()
    }

    /** Switch to the active profile's wallpaper right away. */
    fun reload() {
        photos = WallpaperLibrary.forProfile(context)
        order = photos.take(1) + photos.drop(1).shuffled()
        index = 0
        if (running && !loading) {
            handler.removeCallbacks(tick)
            showNext()
        }
    }

    private fun showNext() {
        if (!running || photos.isEmpty()) return
        if (index >= order.size) {
            order = photos.shuffled()
            index = 0
        }
        val photo = order[index++]
        current = photo
        loading = true
        Thread {
            val bitmap = WallpaperLibrary.decode(context, photo)
            val front = if (bitmap != null) WallpaperLibrary.decodeFront(context, photo) else null
            handler.post {
                loading = false
                if (!running) return@post
                if (bitmap != null) crossfadeTo(bitmap, front)
                handler.postDelayed(tick, ROTATE_EVERY_MS)
            }
        }.start()
    }

    private fun crossfadeTo(bitmap: Bitmap, front: Bitmap?) {
        val incoming = if (showingA) viewB else viewA
        val outgoing = if (showingA) viewA else viewB
        val inFront = if (showingA) frontB else frontA
        val outFront = if (showingA) frontA else frontB
        showingA = !showingA

        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.bringToFront() // only reorders within the photo layer
        onPhotoShowing(true)
        onNewPhoto?.invoke(bitmap)

        inFront.animate().cancel()
        if (front != null) {
            inFront.setImageBitmap(front)
            inFront.visibility = View.VISIBLE
            inFront.alpha = 0f
            inFront.bringToFront()
        } else {
            inFront.setImageDrawable(null)
            inFront.visibility = View.GONE
        }

        // Both layers zoom together so the cut-out stays lined up.
        val layers = if (front != null) listOf(incoming, inFront) else listOf(incoming)
        for (v in layers) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Motion.standard).withEndAction {
                // Slow zoom across the photo's time on screen.
                v.animate().scaleX(ZOOM_TO).scaleY(ZOOM_TO).setInterpolator(LinearInterpolator())
                    .setDuration(ROTATE_EVERY_MS).start()
            }.start()
        }
        outFront.animate().cancel()
        outFront.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            outFront.setImageDrawable(null)
        }.start()
        handler.postDelayed({
            if (incoming.drawable != null && (if (showingA) viewA else viewB) === incoming) {
                outgoing.animate().cancel()
                outgoing.alpha = 0f
                outgoing.setImageDrawable(null)
            }
        }, FADE_MS + 50)
    }
}
