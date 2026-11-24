package com.morgan.minimallauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    private val engine = ColourEngine(link, Prefs.int(app, "lights_style", 1).also { ColourEngine.loadWb(app) }).also { e ->
        // [seed] is what the strip is showing (LED output): carry on from it as the same colour, not re-converted.
        seed?.let { ColourEngine.fromLed(it).let { (screen, lvl) -> e.seed(screen, lvl) } }
        e.maxLevel = floatArrayOf(0.6f, 0.85f, 1f)[Prefs.int(app, "lights_bright", 1).coerceIn(0, 2)] }
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
            if (!running || soundOnly || kfMode) return
            val holder = surface.holder
            if (holder.surface?.isValid != true || surface.width <= 0) { bg.postDelayed(this, 200); return }
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                PixelCopy.request(surface, frame, { result ->
                    if (result == PixelCopy.SUCCESS) { copies++; analyse() } else fails++
                    if (copies + fails == 150 || (copies + fails) % 1800 == 0) Diag.send(app, "lights", "TV Colour Lights: $copies frames read in ${(android.os.SystemClock.uptimeMillis() - startedAt) / 1000.0}s, $fails failed, level ${"%.2f".format(engine.level)}, gentle=$gentle")
                    // The picture always comes first: if reading a frame takes long (the TV is busy), or the video
                    // is heavy (10-bit / HDR / 4K), read less often.
                    val took = android.os.SystemClock.uptimeMillis() - t0
                    slow = slow * 0.8f + took * 0.2f
                    val every = when { slowRead -> 380L; gentle || slow > 18f -> 80L; slow > 10f -> 50L; else -> FRAME_MS }
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
    /** 10-bit video: every read costs the TV a frame now and then, so read only ~2½ times a second. */
    @Volatile var slowRead = false
    private var lastBright = 0f

    fun start() {
        if (running) return
        running = true
        bg.removeCallbacks(glowTick)
        if (soundOnly) { if (!started) { started = true; if (owns) link.begin(); startedAt = android.os.SystemClock.uptimeMillis() }
            engine.driven = true
            if (kfMode) { bg.removeCallbacks(stepTick); bg.post(stepTick) } else bg.post(soundTick); return }
        val first = !started
        if (!started) { started = true; if (owns) link.begin(); startedAt = android.os.SystemClock.uptimeMillis() }
        engine.driven = true
        bg.removeCallbacks(stepTick); bg.post(stepTick)
        // Heavy video: let the film settle in (frame-rate switch, decoder warm-up) before reading its picture.
        if (first && gentle) bg.postDelayed(tick, 4000) else bg.post(tick)
    }

    // The light's glide, on a steady beat between picture reads (smooth, not stepping each time a frame arrives).
    private val stepTick = object : Runnable {
        override fun run() {
            if (!running || soundOnly && !kfMode) return
            engine.step()
            bg.postDelayed(this, 40)
        }
    }

    /** The film's genres set the lights' starting pace and the mood of its neutral scenes. */
    fun mood(genres: String) { bg.post { engine.genrePace = ColourEngine.paceFor(genres); engine.neutralTint = ColourEngine.tintFor(genres) } }

    /** A 4D Mode moment (see [SoundEvents]). */
    fun impulse(kind: String) = bg.post {
        if (!running) return@post
        if (soundOnly && !kfMode) kick = max(kick, when (kind) { "boom" -> 0.6f; "scare" -> 0.9f; else -> 0.35f }) else engine.impulse(kind)
    }

    // ---------- When the picture can't be read (tunnelled 10-bit films, or reading it would cost the film frames) ----
    // The lights hold the film's colour and breathe with its soundtrack: louder scenes lift the room, quiet ones let it
    // settle, and bangs and booms flash it.
    @Volatile var soundOnly = false
    private var palette = intArrayOf(255, 214, 180)
    private var kick = 0f
    private var soundTicks = 0
    /** Several real stills from the film: the room moves slowly between their colours (one every ~40 s). */
    @Volatile private var looks: List<IntArray> = emptyList()
    private var lookAt = 0L; private var lookI = 0
    fun stills(colours: List<IntArray>) = bg.post { if (colours.isNotEmpty()) { looks = colours; lookI = 0; lookAt = 0L; palette = colours[0] } }

    private val soundTick = object : Runnable {
        override fun run() {
            if (!running || !soundOnly || kfMode) return
            val now = android.os.SystemClock.uptimeMillis()
            if (looks.size > 1 && now - lookAt > 40_000L) { lookAt = now; lookI = (lookI + 1) % looks.size; palette = looks[lookI] }
            // A steady glow in the film's colours (we can't see how dark the scene is), lifted by the soundtrack when
            // it's being heard, and flashed by bangs and booms.
            val target = (0.3f + 0.35f * SoundEvents.loudness + kick).coerceIn(0f, 1f)
            if (++soundTicks % 600 == 0) Diag.send(app, "lights", "following the sound: loudness ${"%.2f".format(SoundEvents.loudness)}, level ${"%.2f".format(engine.level)}, colour ${palette.joinToString(",")}")
            engine.glide(palette, target, if (kick > 0.05f) 0.55f else 0.08f)
            kick *= 0.82f
            bg.postDelayed(this, 50)
        }
    }

    /** Keyframe colour (see [KeyframeTap]): the picture of each new shot, for video that can't be read off the screen. */
    @Volatile var kfMode = false
    fun keyframe(px: IntArray, w: Int) = bg.post {
        if (!running) return@post
        if (!kfMode) { kfMode = true; bg.removeCallbacks(soundTick); bg.removeCallbacks(stepTick); bg.post(stepTick); Diag.send(app, "lights", "following the picture from its keyframes") }
        engine.frame(px, px.size, w)
    }

    /** Back to reading the picture (the frame guard is trying again). */
    fun backToPicture() {
        soundOnly = false; kfMode = false
        bg.post { bg.removeCallbacks(soundTick); if (running) { bg.removeCallbacks(tick); bg.post(tick); bg.removeCallbacks(stepTick); bg.post(stepTick) } }
    }

    /** Stop reading the picture; follow the sound in the film's [colour] instead. */
    fun followSound(colour: IntArray?) {
        soundOnly = true
        bg.post { followSoundNow(colour) }
    }
    private fun followSoundNow(colour: IntArray?) {
        // [colour] is the film's own colour (a screen colour) - or none: a neutral warm white.
        palette = colour ?: intArrayOf(255, 214, 180)
        bg.removeCallbacks(tick)
        if (running) { bg.removeCallbacks(soundTick); bg.post(soundTick) }
    }

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
        running = false; bg.removeCallbacks(tick); bg.removeCallbacks(glowTick); bg.removeCallbacks(soundTick); bg.removeCallbacks(stepTick)
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
        val fourD = SoundEvents.listener != null
        engine.tension = if (fourD) SoundEvents.tension else 0f; engine.rain = fourD && SoundEvents.rain
        engine.frame(px, px.size, frame.width)
    }
}
