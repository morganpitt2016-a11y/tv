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
        cache.put(key, bmp)
        return bmp
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
            // Service logos are square with their own background: extend that colour across the tile.
            canvas.drawColor(logo.getPixel(logo.width / 2, 1) or 0xFF000000.toInt())
            val size = (h * 0.86f).toInt()
            val dst = Rect((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(app.colour)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = app.textColour
                textAlign = Paint.Align.CENTER
                typeface = ResourcesCompat.getFont(c, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
                textSize = h * 0.26f
            }
            while (p.measureText(app.name) > w * 0.84f) p.textSize *= 0.92f
            val y = h / 2f - (p.descent() + p.ascent()) / 2f
            canvas.drawText(app.name, w / 2f, y, p)
        }
        cache.put(key, bmp)
        return bmp
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
