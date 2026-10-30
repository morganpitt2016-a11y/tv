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

/** Loads poster/backdrop images from the web, cached in memory and on disk. */
object ImageLoader {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String?, fade: Boolean = true, done: ((Bitmap?) -> Unit)? = null) {
        view.tag = url
        if (url == null) { view.setImageDrawable(null); done?.invoke(null); return }
        memory.get(url)?.let { view.setImageBitmap(it); view.alpha = 1f; done?.invoke(it); return }
        view.setImageDrawable(null)
        val c = view.context.applicationContext
        pool.execute {
            val bmp = fetch(c, url)
            main.post {
                if (view.tag != url) return@post
                view.setImageBitmap(bmp)
                if (fade && bmp != null) { view.alpha = 0f; view.animate().alpha(1f).setDuration(250).start() }
                done?.invoke(bmp)
            }
        }
    }

    /** Fetches (blocking) - for preloading. */
    fun fetch(c: Context, url: String): Bitmap? {
        memory.get(url)?.let { return it }
        val dir = File(c.cacheDir, "img").apply { mkdirs() }
        val file = File(dir, (url.hashCode().toLong() and 0xffffffffL).toString() + ".img")
        if (!file.exists() && !Net.download(url, file)) return null
        val bmp = try { BitmapFactory.decodeFile(file.path) } catch (e: OutOfMemoryError) { null }
        if (bmp == null) file.delete() else memory.put(url, bmp)
        // Keep the disk cache modest.
        dir.listFiles()?.takeIf { it.size > 600 }?.sortedBy { it.lastModified() }?.take(200)?.forEach { it.delete() }
        return bmp
    }
}
