package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Rotating real-photo wallpaper: mountain/lake/forest landscapes from
 * Unsplash (free under the Unsplash License), crossfading every few
 * minutes with a slow zoom. Photos are cached on the device, so after the
 * first download they show instantly and offline. Until a photo is
 * ready (or with no internet) the bundled painted scene stays visible.
 */
class WallpaperRotator(
    context: Context,
    private val viewA: ImageView,
    private val viewB: ImageView,
    /** Called with true once a real photo is showing (hide the painted scene). */
    private val onPhotoShowing: (Boolean) -> Unit,
) {
    companion object {
        private const val ROTATE_EVERY_MS = 5 * 60 * 1000L
        private const val FADE_MS = 1800L
        private const val ZOOM_TO = 1.06f
        private const val MAX_BYTES = 15 * 1024 * 1024
        private const val TARGET_WIDTH = 1920

        /**
         * Unsplash photo IDs (unsplash.com/photos/<id>). All are landscape
         * mountain/lake scenes in the spirit of the reference.
         */
        val PHOTOS = listOf(
            "rmX6kki4Ht4", // lake and snow-capped mountain at sunset, pink sky
            "N7snRVB4usc", // Glencoe, Scotland: sunset over a lake, mountain silhouettes
            "m07cD8ChI1A", // misty mountains reflected in a calm lake at dusk
            "D3odArjJIzc", // turquoise lake reflecting snow-capped mountains and pine forest
            "XY9tbPYhR34", // mountain across a lake at night
            "U_mKUrPMXW0", // lake with a mountain behind it
            "7Z94A-v9kvw", // snow-covered mountains reflected in a clear lake
            "OtokIOPILX8", // Bannalpsee, Switzerland: lake reflecting snowy peaks
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cacheDir = File(context.cacheDir, "wallpapers").apply { mkdirs() }
    private val badIds = mutableSetOf<String>()
    private var order = PHOTOS.shuffled()
    private var index = 0
    private var showingA = false
    private var running = false

    private val tick = Runnable { showNext() }

    fun start() {
        if (running) return
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
        Thread {
            val bitmap = loadNextBitmap()
            handler.post {
                if (!running) return@post
                if (bitmap != null) crossfadeTo(bitmap)
                handler.postDelayed(tick, if (bitmap != null) ROTATE_EVERY_MS else 60_000L)
            }
        }.start()
    }

    /** Tries each remaining photo once; null if none could be loaded. */
    private fun loadNextBitmap(): Bitmap? {
        repeat(PHOTOS.size) {
            if (index >= order.size) {
                order = PHOTOS.shuffled()
                index = 0
            }
            val id = order[index++]
            if (id in badIds) return@repeat
            val bmp = try { load(id) } catch (e: Exception) { null }
            if (bmp != null) return bmp
        }
        return null
    }

    private fun load(id: String): Bitmap? {
        val file = File(cacheDir, "$id.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bytes = download(id) ?: return null
            file.writeBytes(bytes)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth <= bounds.outHeight) {
            // Not a landscape image: never use it on a 16:9 screen.
            badIds += id
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(id: String): ByteArray? {
        // Unsplash's public download link, resized server-side to 1920px.
        val conn = URL("https://unsplash.com/photos/$id/download?force=true&w=$TARGET_WIDTH")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentType?.startsWith("image/") != true) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun crossfadeTo(bitmap: Bitmap) {
        val incoming = if (showingA) viewB else viewA
        val outgoing = if (showingA) viewA else viewB
        showingA = !showingA

        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.bringToFront()
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
