package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat

/**
 * tvOS-style app tiles: wide (5:3) branded artwork instead of square Android
 * icons. Installed apps use their own TV banner (cropped to fit) or, without
 * one, their icon centred on a tile in the icon's own colour. Apps you don't
 * have yet are drawn from their service logo or brand colour and offer a
 * download.
 */
object AppTiles {

    const val RATIO = 5f / 3f

    /** A well-known TV app, shown in the app grid whether installed or not. */
    class StoreApp(val name: String, val packages: List<String>, val colour: Int, val textColour: Int = Color.WHITE, val service: String? = null)

    val STORE = listOf(
        StoreApp("Netflix", listOf("com.netflix.ninja"), 0xFF000000.toInt(), 0xFFE50914.toInt(), "netflix"),
        StoreApp("Disney+", listOf("com.disney.disneyplus"), 0xFF0B1E4E.toInt(), service = "disneyplus"),
        StoreApp("Prime Video", listOf("com.amazon.amazonvideo.livingroom"), 0xFF1A98FF.toInt(), service = "amazonprime"),
        StoreApp("Apple TV", listOf("com.apple.atve.androidtv.appletv"), 0xFF000000.toInt(), service = "appletvplus"),
        StoreApp("YouTube", listOf("com.google.android.youtube.tv"), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt()),
        StoreApp("BBC iPlayer", listOf("uk.co.bbc.iplayer"), 0xFFF54997.toInt(), service = "bbc"),
        StoreApp("ITVX", listOf("com.itv.loki", "air.ITVMobilePlayer"), 0xFF101010.toInt(), 0xFFB9F23F.toInt(), "itv"),
        StoreApp("Channel 4", listOf("com.channel4.ondemand"), 0xFF1D1D1B.toInt(), service = "all4"),
        StoreApp("5", listOf("com.channel5.my5", "com.channel5.androidtv"), 0xFFE5007D.toInt(), service = "my5"),
        StoreApp("NOW", listOf("com.nowtv.player", "com.nowtv.player.androidtv"), 0xFF102C3F.toInt(), 0xFF35F2B4.toInt(), "nowtv"),
        StoreApp("Paramount+", listOf("com.cbs.ott"), 0xFF0064FF.toInt(), service = "paramountplus"),
        StoreApp("Sky Go", listOf("com.bskyb.skygo"), 0xFF0A2C6E.toInt(), service = "skygo"),
        StoreApp("Plex", listOf("com.plexapp.android"), 0xFF1F1F1F.toInt(), 0xFFE5A00D.toInt(), "plex"),
        StoreApp("Spotify", listOf("com.spotify.tv.android"), 0xFF121212.toInt(), 0xFF1DB954.toInt()),
        StoreApp("Twitch", listOf("tv.twitch.android.app"), 0xFF9146FF.toInt()),
        StoreApp("DAZN", listOf("com.dazn"), 0xFF0C161C.toInt()),
        StoreApp("Crunchyroll", listOf("com.crunchyroll.crunchyroid"), 0xFFF47521.toInt(), service = "crunchyroll"),
        StoreApp("Hayu", listOf("com.hayu.android"), 0xFFF2385A.toInt(), service = "hayu"),
        StoreApp("Pluto TV", listOf("tv.pluto.android"), 0xFF000000.toInt(), 0xFFFFF200.toInt()),
        StoreApp("Tubi", listOf("com.tubitv"), 0xFF7408FF.toInt(), service = "tubitv"),
        StoreApp("Rakuten TV", listOf("tv.wuaki"), 0xFFBF0000.toInt(), service = "wuaki"),
        StoreApp("MUBI", listOf("com.mubi"), 0xFF000000.toInt(), service = "mubi"),
        StoreApp("TiviMate", listOf("ar.tvplayer.tv"), 0xFF13202E.toInt()),
        StoreApp("Kodi", listOf("org.xbmc.kodi"), 0xFF17B2E7.toInt()),
    )

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun clear() = cache.evictAll()

    // ---------- Installed apps ----------

    /** Wide tile for an installed app: its TV banner, or its icon on a tile of its own colour. */
    fun forApp(c: Context, app: AppEntry, w: Int, h: Int): Bitmap {
        val key = "a|${app.packageName}|$w"
        cache.get(key)?.let { return it }
        // Drawn before (and the app hasn't updated since)? Read it back from disk - far quicker than drawing it again.
        val stamp = try { c.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
        val file = java.io.File(java.io.File(c.cacheDir, "tiles").apply { mkdirs() }, "${app.packageName}_${w}x${h}_${stamp}_g2.png")
        if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.path)?.let { cache.put(key, it); return it }
        val bmp = drawApp(c, app, w, h)
        cache.put(key, bmp)
        Thread { try { file.parentFile?.listFiles { f -> f.name.startsWith("${app.packageName}_${w}x${h}_") }?.forEach { it.delete() }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {} }.start()
        return bmp
    }

    private fun drawApp(c: Context, app: AppEntry, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val banner = banner(c, app)
        if (banner != null) {
            drawCropped(canvas, banner, w, h)
        } else {
            val icon = TileRenderer.forApp(app, (h * 0.62f).toInt())
            canvas.drawColor(tileColour(icon))
            val left = (w - icon.width) / 2f
            val top = (h - icon.height) / 2f
            // Rounded icon on its colour, like a tvOS app tile.
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = icon.width * 0.22f
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(RectF(left, top, left + icon.width, top + icon.height), r, r, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(icon, left, top, p)
            canvas.restore()
        }
        finish(canvas, w, h)
        return bmp
    }

    /**
     * The tvOS finish on every tile: a faint sheen across the top (light falling on glass) and a hairline edge, so
     * dark tiles don't melt into the background and every tile reads as the same kind of object.
     */
    private fun finish(canvas: Canvas, w: Int, h: Int) {
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.55f, Color.argb(38, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.55f, sheen)
        val r = h * 0.135f
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = (h / 70f).coerceAtLeast(1f); color = Color.argb(46, 255, 255, 255) }
        val inset = edge.strokeWidth / 2
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r, r, edge)
    }

    private fun banner(c: Context, app: AppEntry): Drawable? = try {
        c.packageManager.getApplicationBanner(app.packageName)
            ?: app.launchIntent?.let { c.packageManager.getActivityBanner(it) }
    } catch (e: Exception) { null }

    /** Draw a 16:9 banner filling a 5:3 tile (centre-cropped, never stretched). */
    private fun drawCropped(canvas: Canvas, d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
        val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
        val l = (w - dw) / 2; val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(canvas)
    }

    /** A calm background colour taken from the icon itself (its dominant, darkened a touch). */
    private fun tileColour(icon: Bitmap): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = (icon.width / 12).coerceAtLeast(1)
        for (y in 0 until icon.height step step) for (x in 0 until icon.width step step) {
            val px = icon.getPixel(x, y)
            if (Color.alpha(px) < 200) continue
            r += Color.red(px); g += Color.green(px); b += Color.blue(px); n++
        }
        if (n == 0) return 0xFF2C2C2E.toInt()
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.75f)
        hsv[2] = (hsv[2] * 0.62f).coerceIn(0.14f, 0.55f)
        return Color.HSVToColor(hsv)
    }

    // ---------- Apps to download ----------

    /** Tile for an app you don't have: its service logo (if known) on its brand colour, else its name. */
    fun forStore(c: Context, app: StoreApp, logo: Bitmap?, w: Int, h: Int): Bitmap {
        val key = "s|${app.name}|$w|${logo != null}"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (logo != null) {
            // Service logos are squares with their own background: the tile takes the colour of the logo's edges (all
            // round, not one pixel), and the logo sits on it as a rounded app icon - no hard square edge showing.
            canvas.drawColor(edgeColour(logo))
            val size = (h * 0.72f).toInt()
            val dst = RectF((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f)
            val r = size * 0.22f
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // The brand colour as a soft vertical gradient (lighter at the top), like a lit app tile.
            val hsv = FloatArray(3); Color.colorToHSV(app.colour, hsv)
            val lighter = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.92f, (hsv[2] * 1.18f + 0.04f).coerceAtMost(1f)))
            val darker = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2] * 0.78f))
            canvas.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), lighter, darker, android.graphics.Shader.TileMode.CLAMP) })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = app.textColour
                textAlign = Paint.Align.CENTER
                typeface = ResourcesCompat.getFont(c, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
                textSize = h * 0.24f
                letterSpacing = -0.02f
            }
            while (p.measureText(app.name) > w * 0.8f) p.textSize *= 0.92f
            val y = h / 2f - (p.descent() + p.ascent()) / 2f
            canvas.drawText(app.name, w / 2f, y, p)
        }
        getBadge(c, canvas, w, h)
        finish(canvas, w, h)
        cache.put(key, bmp)
        return bmp
    }

    /** The average colour round a logo's border - the colour its own background has. */
    private fun edgeColour(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val last = b.width - 1; val bottom = b.height - 1
        for (i in 0 until b.width step 2) for (y in intArrayOf(0, 1, bottom - 1, bottom)) { val p = b.getPixel(i, y); r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
        for (j in 0 until b.height step 2) for (x in intArrayOf(0, 1, last - 1, last)) { val p = b.getPixel(x, j); r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
        return if (n == 0) 0xFF1C1C1E.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    /** A small "GET" pill in the corner of an app you don't have yet, like the App Store's button. */
    private fun getBadge(c: Context, canvas: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = h * 0.095f; letterSpacing = 0.08f
            typeface = ResourcesCompat.getFont(c, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
        }
        val label = "GET"
        val tw = p.measureText(label)
        val ph = h * 0.16f; val pw = tw + ph * 1.1f
        val r = RectF(w - pw - h * 0.06f, h * 0.06f, w - h * 0.06f, h * 0.06f + ph)
        canvas.drawRoundRect(r, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) })
        canvas.drawText(label, r.centerX() - tw / 2, r.centerY() - (p.descent() + p.ascent()) / 2, p)
    }

    fun installedPackage(installed: Set<String>, app: StoreApp) = app.packages.firstOrNull { it in installed }

    /** Offer to download a TV app from Google Play. */
    fun offerDownload(activity: android.app.Activity, app: StoreApp) {
        GlassDialog(activity).title("Get ${app.name}")
            .message("${app.name} isn’t installed on this TV. Download it from Google Play?")
            .button("Get from Google Play", GlassDialog.Style.BOLD) {
                val tries = listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packages.first()}")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(app.name))),
                )
                for (i in tries) {
                    try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@button } catch (e: Exception) {}
                }
                GlassUi.toast(activity, "Open Google Play and search for ${app.name}")
            }
            .button("Cancel")
            .show()
    }
}
