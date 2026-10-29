package com.morgan.minimallauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Apple-TV-style ambient background: a full-screen photo that slowly
 * zooms/pans (Ken Burns effect) and gets swapped for a new one every few
 * minutes, crossfading between them.
 *
 * Photo source right now: picsum.photos, a free keyless placeholder-photo
 * service - good for getting the EFFECT working end to end without needing
 * any account or API key. Swap `fetchRandomPhotoBytes()` for a real photo
 * library (your own Google Photos album, a local folder on the TV, etc.)
 * whenever you're ready - everything else (crossfade, Ken Burns, rotation
 * timer) stays the same.
 */
class BackgroundPhotoHelper(private val imageView: ImageView) {

    private val handler = Handler(Looper.getMainLooper())
    private var currentAnimator: AnimatorSet? = null
    private val rotateEveryMs = 5 * 60 * 1000L // new photo every 5 minutes

    private val rotateRunnable = object : Runnable {
        override fun run() {
            loadNextPhoto()
            handler.postDelayed(this, rotateEveryMs)
        }
    }

    fun start() {
        handler.post(rotateRunnable)
    }

    fun stop() {
        handler.removeCallbacks(rotateRunnable)
        currentAnimator?.cancel()
    }

    private fun loadNextPhoto() {
        Thread {
            val bitmap = fetchRandomPhoto()
            if (bitmap != null) {
                handler.post { showPhoto(bitmap) }
            }
            // If the fetch fails (no internet, service down) we simply
            // keep whatever photo is already showing rather than blanking
            // the screen.
        }.start()
    }

    private fun showPhoto(bitmap: Bitmap) {
        currentAnimator?.cancel()

        imageView.alpha = 0f
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.translationX = 0f
        imageView.setImageBitmap(bitmap)

        val fadeIn = ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).apply {
            duration = 1500
        }

        // Slow zoom-in + gentle horizontal drift over the photo's full
        // display time - this is the "Ken Burns" part of the effect.
        val zoom = ObjectAnimator.ofFloat(imageView, "scaleX", 1f, 1.12f).apply {
            duration = rotateEveryMs
        }
        val zoomY = ObjectAnimator.ofFloat(imageView, "scaleY", 1f, 1.12f).apply {
            duration = rotateEveryMs
        }
        val drift = ObjectAnimator.ofFloat(imageView, "translationX", 0f, -40f).apply {
            duration = rotateEveryMs
        }

        val set = AnimatorSet()
        set.playTogether(fadeIn, zoom, zoomY, drift)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentAnimator = null
            }
        })
        set.start()
        currentAnimator = set
    }

    private fun fetchRandomPhoto(): Bitmap? {
        return try {
            val url = URL("https://picsum.photos/1920/1080")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
