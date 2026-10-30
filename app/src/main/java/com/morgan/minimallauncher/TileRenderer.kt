package com.morgan.minimallauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Turns any app icon into a full-bleed square tile bitmap, so every app
 * in the dock reads as a uniform rounded-square badge (the view clips
 * the corners).
 */
object TileRenderer {

    private const val FALLBACK_TILE = 0xFFF3F3F3.toInt()

    /** Rendered tiles are reused (drawing an adaptive icon each time the home screen shows is slow). */
    private val cache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun forApp(app: AppEntry, size: Int): Bitmap {
        val key = "${app.packageName}|$size"
        cache.get(key)?.let { return it }
        return render(app.icon, size).also { cache.put(key, it) }
    }

    fun clear() = cache.evictAll()

    fun render(source: Drawable, size: Int): Bitmap {
        val icon = source.mutate()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // Adaptive icon layers are 108dp with only the inner 72dp meant
            // to be visible, so draw them 1.5x larger: the visible part
            // then fills the tile edge to edge.
            val inset = size / 4
            for (layer in listOf(icon.background, icon.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(canvas)
            }
            return bmp
        }

        // Legacy icon: if it already fills its square, use it as-is.
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)
        if (cornersOpaque(bmp)) return bmp

        // Otherwise it's a shape with transparent edges (circle, logo):
        // sit it on a light tile so the row stays uniform.
        bmp.eraseColor(FALLBACK_TILE)
        val pad = (size * 0.16f).toInt()
        icon.setBounds(pad, pad, size - pad, size - pad)
        icon.draw(canvas)
        return bmp
    }

    private fun cornersOpaque(bmp: Bitmap): Boolean {
        val i = (bmp.width * 0.1f).toInt()
        val far = bmp.width - 1 - i
        return listOf(i to i, far to i, i to far, far to far).all { (x, y) ->
            Color.alpha(bmp.getPixel(x, y)) > 200
        }
    }
}
