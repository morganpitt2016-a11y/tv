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
    private val engine = ColourEngine(link)
    private val px = IntArray(48 * 27)
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
        frame.getPixels(px, 0, frame.width, 0, 0, frame.width, frame.height)
        engine.frame(px, px.size)
    }
}
