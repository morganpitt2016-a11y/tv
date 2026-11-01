package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import java.util.concurrent.Executors

/**
 * Choose a wallpaper with a big preview: the list is on the left, and the picture
 * you're on is shown on the right (a 2x2 collage when the choice is several photos).
 */
class WallpaperPickerActivity : Activity() {

    private lateinit var preview: ImageView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Bitmap>(10)
    private var wanted = ""
    private var pending: Runnable? = null

    private class Option(val key: String, val name: String, val detail: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        GlassUi.installBackdrop(root)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(this@WallpaperPickerActivity, 56f), dp(this@WallpaperPickerActivity, 30f), dp(this@WallpaperPickerActivity, 56f), dp(this@WallpaperPickerActivity, 30f))
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        UiSounds.attach(this)

        // ----- Left: the list -----
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(GlassUi.text(this, "Wallpaper", 26f, R.font.inter_semibold))
        left.addView(GlassUi.text(this, "for ${ProfileManager.getActiveProfile(this)}", 14f, R.font.inter_medium, 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(this@WallpaperPickerActivity, 12f) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(left, LinearLayout.LayoutParams(dp(this, 330f), -1))

        // ----- Right: the preview -----
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val card = FrameLayout(this).apply {
            background = GlassUi.panel(context, 22f, 0x26)
            setPadding(dp(this@WallpaperPickerActivity, 10f), dp(this@WallpaperPickerActivity, 10f), dp(this@WallpaperPickerActivity, 10f), dp(this@WallpaperPickerActivity, 10f))
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33000000)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(this@WallpaperPickerActivity, 14f).toFloat()) }
            }
        }
        card.addView(preview, FrameLayout.LayoutParams(dp(this, 440f), dp(this, 248f)))
        right.addView(card, LinearLayout.LayoutParams(-2, -2))
        title = GlassUi.text(this, "", 20f, R.font.inter_semibold)
        sub = GlassUi.text(this, "", 14f, R.font.inter_medium, 0x99FFFFFF.toInt())
        right.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WallpaperPickerActivity, 16f) })
        right.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WallpaperPickerActivity, 4f) })
        page.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(this@WallpaperPickerActivity, 24f) })

        // ----- Options -----
        val current = ProfileManager.getWallpaper(this)
        val albumCount = WallpaperLibrary.album(this).size
        val groups = listOf(
            "COLLECTIONS" to (listOf(Option("all", "All Photos", "${WallpaperLibrary.bundled.size} photos, changing slowly")) +
                WallpaperLibrary.categories.map { Option("cat:${it.id}", it.title, "${it.numbers.size} photos") }),
            "YOUR PHOTOS" to listOf(Option("album", if (albumCount == 0) "My Photos" else "My Photos ($albumCount)",
                if (albumCount == 0) "Add photos from the iPhone remote’s Photos tab" else "$albumCount of your own")),
            "ONE PHOTO" to WallpaperLibrary.bundled.map { Option("photo:${it.number}", it.place, "One photo, always") },
        )
        var currentRow: View? = null
        for ((heading, options) in groups) {
            list.addView(GlassUi.text(this, heading, 11f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WallpaperPickerActivity, 14f); bottomMargin = dp(this@WallpaperPickerActivity, 6f); marginStart = dp(this@WallpaperPickerActivity, 6f) })
            for (o in options) {
                val row = row(o, o.key == current)
                list.addView(row, LinearLayout.LayoutParams(-1, dp(this, 40f)).apply { bottomMargin = dp(this@WallpaperPickerActivity, 4f) })
                if (o.key == current) currentRow = row
            }
        }
        (currentRow ?: list.getChildAt(1))?.requestFocus()
    }

    private fun row(o: Option, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(context, 12f)
            setPadding(dp(context, 14f), 0, dp(context, 14f), 0)
            isFocusable = true; isClickable = true
            contentDescription = o.name
        }
        row.addView(GlassUi.rowText(this, o.name, 14f, R.font.inter_medium, GlassUi.primaryText()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(GlassUi.rowText(this, "✓", 14f, R.font.inter_semibold, GlassUi.primaryText()))
        row.setOnFocusChangeListener { _, has -> if (has) show(o) }
        row.setOnClickListener {
            ProfileManager.setWallpaper(this, o.key)
            if (o.key == "album" && WallpaperLibrary.album(this).isEmpty()) GlassUi.toast(this, "Add photos from the iPhone remote’s Photos tab")
            else GlassUi.toast(this, "Wallpaper set to ${o.name}")
            finish()
        }
        return row
    }

    // ---------- Preview ----------

    private fun show(o: Option) {
        title.text = o.name
        sub.text = o.detail
        wanted = o.key
        pending?.let { handler.removeCallbacks(it) }
        cache.get(o.key)?.let { preview.setImageBitmap(it); return }
        // Wait a beat so scrolling quickly through the list doesn't decode every picture.
        val r = Runnable {
            exec.execute(Runnable {
                val bmp = try { build(o.key) } catch (e: Throwable) { null }
                if (bmp != null) { cache.put(o.key, bmp); handler.post { if (wanted == o.key && !isDestroyed) preview.setImageBitmap(bmp) } }
            })
        }
        pending = r
        handler.postDelayed(r, 120)
    }

    private fun thumb(photo: WallpaperLibrary.Photo, width: Int): Bitmap? = try {
        when (photo) {
            is WallpaperLibrary.Photo.Bundled -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, photo.res, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                BitmapFactory.decodeResource(resources, photo.res, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            is WallpaperLibrary.Photo.Local -> WallpaperLibrary.decodeFile(photo.file, width)
        }
    } catch (e: Throwable) { null }

    /** One photo, or a 2x2 collage of the first four, at 16:9. */
    private fun build(key: String): Bitmap? {
        val photos = WallpaperLibrary.photosFor(this, key)
        if (photos.isEmpty()) return null
        val w = 880; val h = 496
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        fun drawCropped(b: Bitmap, dst: RectF) {
            val dstRatio = dst.width() / dst.height()
            val srcRatio = b.width.toFloat() / b.height
            val src = if (srcRatio > dstRatio) {
                val sw = (b.height * dstRatio).toInt(); Rect((b.width - sw) / 2, 0, (b.width + sw) / 2, b.height)
            } else {
                val sh = (b.width / dstRatio).toInt(); Rect(0, (b.height - sh) / 2, b.width, (b.height + sh) / 2)
            }
            cv.drawBitmap(b, src, dst, null)
        }
        if (photos.size == 1) {
            thumb(photos[0], w)?.let { drawCropped(it, RectF(0f, 0f, w.toFloat(), h.toFloat())) } ?: return null
        } else {
            val gap = 6f
            val cw = (w - gap) / 2f; val ch = (h - gap) / 2f
            photos.take(4).forEachIndexed { i, p ->
                val l = (i % 2) * (cw + gap); val t = (i / 2) * (ch + gap)
                thumb(p, 480)?.let { drawCropped(it, RectF(l, t, l + cw, t + ch)) }
            }
        }
        return out
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); exec.shutdownNow(); super.onDestroy() }
}
