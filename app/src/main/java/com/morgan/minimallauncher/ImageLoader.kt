package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads poster/backdrop/guide images from the web, cached in memory and
 * on disk. Every image is decoded at the size it's shown at (TV guide
 * pictures can be huge originals - decoding those full size runs the TV
 * out of memory).
 */
object ImageLoader {
    const val POSTER = 320
    const val CARD = 440
    const val HERO = 1280
    const val ICON = 128

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String?, maxWidth: Int = POSTER, fade: Boolean = true, done: ((Bitmap?) -> Unit)? = null) {
        view.tag = url
        if (url == null) { view.setImageDrawable(null); done?.invoke(null); return }
        val key = "$maxWidth|$url"
        memory.get(key)?.let { view.setImageBitmap(it); view.alpha = 1f; done?.invoke(it); return }
        view.setImageDrawable(null)
        val c = view.context.applicationContext
        pool.execute {
            val bmp = try { fetch(c, url, maxWidth) } catch (e: Throwable) { null }
            main.post {
                if (view.tag != url) return@post
                view.setImageBitmap(bmp)
                if (fade && bmp != null) { view.alpha = 0f; view.animate().alpha(1f).setDuration(220).start() }
                done?.invoke(bmp)
            }
        }
    }

    /** Downloads and caches in the background, for images about to be shown. */
    fun prefetch(c: Context, url: String, maxWidth: Int = POSTER) {
        val app = c.applicationContext
        pool.execute { try { fetch(app, url, maxWidth) } catch (e: Throwable) {} }
    }

    /** Fetches (blocking), scaled down to at most [maxWidth] pixels wide. */
    fun fetch(c: Context, url: String, maxWidth: Int): Bitmap? {
        val key = "$maxWidth|$url"
        memory.get(key)?.let { return it }
        val dir = File(c.cacheDir, "img").apply { mkdirs() }
        val file = File(dir, (url.hashCode().toLong() and 0xffffffffL).toString() + ".img")
        if (!file.exists() && !Net.download(url, file)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val decoded = try {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) { null } ?: return null
        // Finish the job exactly so the memory cache stays small.
        val bmp = if (decoded.width > maxWidth * 1.3f) {
            Bitmap.createScaledBitmap(decoded, maxWidth, (decoded.height * maxWidth.toFloat() / decoded.width).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        memory.put(key, bmp)
        file.setLastModified(System.currentTimeMillis())
        dir.listFiles()?.takeIf { it.size > 800 }?.sortedBy { it.lastModified() }?.take(300)?.forEach { it.delete() }
        return bmp
    }
}
