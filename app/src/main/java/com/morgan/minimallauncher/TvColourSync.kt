package com.morgan.minimallauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min

/**
 * TV Colour Lights: about 30 times a second, a tiny copy of the picture playing in the launcher's player is read
 * (PixelCopy - only our own player's video, never other apps), its main colour worked out the way Ambilight-style
 * systems do it (vivid, well-lit areas count most; black bars and dark shadows barely count), smoothed so the room
 * doesn't flicker, and sent to the Magic Hue lights. Dark scenes dim the lights; bright ones lift them.
 */
private const val FRAME_MS = 33L

class TvColourSync(private val app: android.content.Context, private val surface: SurfaceView, host: String) {
    private val link = MagicHue.Link(host) { msg -> Diag.send(app, "lights", msg) }
    private var copies = 0; private var fails = 0
    private val thread = HandlerThread("tv-colour").apply { start() }
    private val bg = Handler(thread.looper)
    private val frame = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
    @Volatile private var running = false
    private var r = 0f; private var g = 0f; private var b = 0f
    private var sent = intArrayOf(-1, -1, -1)
    private var started = false

    // As fast as the picture can be read, about 30 times a second: each read starts when the last one is done
    // (never piling up), aiming for one every FRAME_MS.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val holder = surface.holder
            if (holder.surface?.isValid != true || surface.width <= 0) { bg.postDelayed(this, 200); return }
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                PixelCopy.request(surface, frame, { result ->
                    if (result == PixelCopy.SUCCESS) { copies++; analyse() } else fails++
                    if (copies + fails == 150) Diag.send(app, "lights", "TV Colour Lights: $copies frames read in ${(android.os.SystemClock.uptimeMillis() - startedAt) / 1000.0}s, $fails failed")
                    if (running) bg.postDelayed(this, (FRAME_MS - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(0))
                }, bg)
            } catch (e: Exception) {
                if (fails++ == 0) Diag.send(app, "lights", "PixelCopy error: ${e.message}")
                bg.postDelayed(this, 200)
            }
        }
    }
    private var startedAt = 0L
    private var lastBright = 0f

    fun start() {
        if (running) return
        running = true
        if (!started) { started = true; link.begin(); startedAt = android.os.SystemClock.uptimeMillis() }
        bg.post(tick)
    }

    /** Paused: hold the last colour. */
    fun pause() { running = false; bg.removeCallbacks(tick) }

    /** Leaving the player: the lights go back to how they were. */
    fun stop() {
        pause()
        if (started) link.end()
        bg.post { frame.recycle(); thread.quitSafely() }
    }

    private fun analyse() {
        var wr = 0.0; var wg = 0.0; var wb = 0.0; var wsum = 0.0; var lum = 0.0; var n = 0
        val hsv = FloatArray(3)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val px = frame.getPixel(x, y)
            val pr = Color.red(px); val pg = Color.green(px); val pb = Color.blue(px)
            Color.RGBToHSV(pr, pg, pb, hsv)
            lum += (0.2126 * pr + 0.7152 * pg + 0.0722 * pb) / 255.0; n++
            if (hsv[2] < 0.08f) continue                                // black bars, deep shadow
            val w = (0.15 + hsv[1] * hsv[1] * 1.6) * hsv[2]            // colourful and lit counts most
            wr += pr * w; wg += pg * w; wb += pb * w; wsum += w
        }
        val bright = (lum / max(n, 1)).toFloat()
        var tr = 0f; var tg = 0f; var tb = 0f
        if (wsum > 0) {
            Color.RGBToHSV((wr / wsum).toInt(), (wg / wsum).toInt(), (wb / wsum).toInt(), hsv)
            // LED strips turn pale colours into a cold blue-white, so colours get a strong push, and a scene with
            // hardly any colour (news studios, grey skies) becomes a warm white glow rather than icy blue.
            if (hsv[1] < 0.10f) { hsv[0] = 32f; hsv[1] = 0.45f }
            else hsv[1] = min(1f, hsv[1] * 1.9f + 0.15f)
            // Brightness follows the scene: a dim glow in dark scenes, full in bright ones.
            hsv[2] = (0.10f + bright * 1.6f).coerceIn(0.06f, 1f)
            val c = Color.HSVToColor(hsv)
            // LED white balance: their blue and green are much stronger than their red.
            tr = Color.red(c).toFloat(); tg = Color.green(c) * 0.86f; tb = Color.blue(c) * 0.70f
        }
        // A flash (gunshot, explosion, lightning): the scene suddenly much brighter - full brightness, at once.
        val flash = bright - lastBright > 0.10f
        lastBright = bright
        if (flash && wsum > 0) {
            Color.RGBToHSV(tr.toInt().coerceIn(0, 255), tg.toInt().coerceIn(0, 255), tb.toInt().coerceIn(0, 255), hsv)
            hsv[2] = 1f
            val c = Color.HSVToColor(hsv)
            tr = Color.red(c).toFloat(); tg = Color.green(c).toFloat(); tb = Color.blue(c).toFloat()
        }
        // Big changes (a cut, a flash) land instantly; small drifts are eased so the room doesn't flicker.
        val jump = kotlin.math.abs(tr - r) + kotlin.math.abs(tg - g) + kotlin.math.abs(tb - b)
        val k = if (flash || jump > 110f) 1f else if (jump > 45f) 0.7f else 0.45f
        r += (tr - r) * k; g += (tg - g) * k; b += (tb - b) * k
        val out = intArrayOf(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        if (kotlin.math.abs(out[0] - sent[0]) + kotlin.math.abs(out[1] - sent[1]) + kotlin.math.abs(out[2] - sent[2]) < 3) return
        sent = out
        link.colour(out[0], out[1], out[2])
    }
}
