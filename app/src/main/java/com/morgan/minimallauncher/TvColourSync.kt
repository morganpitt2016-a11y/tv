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

class TvColourSync(private val app: android.content.Context, private val surface: SurfaceView, host: String,
                   shared: MagicHue.Link? = null, seed: IntArray? = null) {
    /** A film's Cinema Room already holds the connection (and puts the lights back itself). */
    private val owns = shared == null
    private val link = shared ?: MagicHue.Link(host) { msg -> Diag.send(app, "lights", msg) }
    private val engine = ColourEngine(link, Prefs.int(app, "lights_style", 1)).also { e -> seed?.let { e.seed(it, 0.45f) } }
    private val px = IntArray(96 * 54)
    private var copies = 0; private var fails = 0
    private val thread = HandlerThread("tv-colour").apply { start() }
    private val bg = Handler(thread.looper)
    private val frame = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
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
                    // The picture always comes first: if reading a frame takes long (the TV is busy), or the video
                    // is heavy (10-bit / HDR / 4K), read less often.
                    val took = android.os.SystemClock.uptimeMillis() - t0
                    slow = slow * 0.8f + took * 0.2f
                    val every = when { gentle || slow > 18f -> 80L; slow > 10f -> 50L; else -> FRAME_MS }
                    if (running) bg.postDelayed(this, (every - took).coerceAtLeast(5))
                }, bg)
            } catch (e: Exception) {
                if (fails++ == 0) Diag.send(app, "lights", "PixelCopy error: ${e.message}")
                bg.postDelayed(this, 200)
            }
        }
    }
    private var startedAt = 0L
    private var slow = 0f
    /** Heavy video (10-bit / HDR / 4K): about 12 reads a second instead of 30. */
    @Volatile var gentle = false
    private var lastBright = 0f

    fun start() {
        if (running) return
        running = true
        bg.removeCallbacks(glowTick)
        if (!started) { started = true; if (owns) link.begin(); startedAt = android.os.SystemClock.uptimeMillis() }
        bg.post(tick)
    }

    /** A 4D Mode moment (see [SoundEvents]). */
    fun impulse(kind: String) = bg.post { if (running) engine.impulse(kind) }

    // Paused by you: the room comes up to a soft warm glow, like a cinema's house lights between reels; pressing
    // play lets the film's colours drift back in.
    private val warm = intArrayOf(255, 150, 70)
    private var glowSteps = 0
    private val glowTick = object : Runnable {
        override fun run() {
            if (running) return
            engine.glide(warm, 0.45f, 0.06f)
            if (++glowSteps < 75) bg.postDelayed(this, 33)
        }
    }

    /** Nodded off: the lights sink to dark over [ms], and switch off when the player closes. */
    fun sleep(ms: Long) {
        running = false; bg.removeCallbacks(tick); bg.removeCallbacks(glowTick)
        link.asleep = true
        val steps = (ms / 50).toInt().coerceAtLeast(1)
        var i = 0
        bg.post(object : Runnable { override fun run() { if (running) return; engine.glide(intArrayOf(0, 0, 0), 0f, 3f / steps); if (++i < steps) bg.postDelayed(this, 50) } })
    }
    fun awake() { link.asleep = false }

    /** Paused: hold the last colour - or, when you paused it, glow warm. */
    fun pause(glow: Boolean = false) {
        running = false; bg.removeCallbacks(tick); bg.removeCallbacks(glowTick)
        if (glow && started) { glowSteps = 0; bg.postDelayed(glowTick, 400) }
    }

    /** Leaving the player: the lights go back to how they were. */
    fun stop() {
        pause()
        if (started && owns) link.end()
        bg.post { frame.recycle(); thread.quitSafely() }
    }

    private fun analyse() {
        frame.getPixels(px, 0, frame.width, 0, 0, frame.width, frame.height)
        engine.tension = SoundEvents.tension; engine.rain = SoundEvents.rain
        engine.frame(px, px.size, frame.width)
    }
}
