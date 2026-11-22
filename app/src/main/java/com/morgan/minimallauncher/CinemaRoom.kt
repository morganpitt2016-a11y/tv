package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread

/**
 * The room becomes the cinema when a film starts in the launcher's player:
 *  - the Magic Hue lights glide from how they were into the film's own colour (taken from its artwork) while the
 *    "Now Showing" card is up, then the picture takes over (TV Colour Lights) - or, with those off, a dim glow
 *    in the film's colour stays for the whole film;
 *  - notifications go quiet (Do Not Disturb) for the film;
 *  - when the credits roll the "house lights" come up slowly, warm, like a real cinema;
 *  - leaving puts the lights and notifications back exactly as they were.
 *
 * Settings › Magic Hue Lights › Cinema Room (on by default once the lights are found).
 */
class CinemaRoom(private val app: Context, host: String) {
    companion object {
        fun enabled(c: Context) = MagicHue.ip(c) != null && Prefs.bool(c, "cinema_room", true)
        private val HOUSE = intArrayOf(255, 170, 95)      // warm white, as the LEDs show it
    }

    val link = MagicHue.Link(host) { Diag.send(app, "lights", it) }
    private val thread = HandlerThread("cinema-room").apply { start() }
    private val bg = Handler(thread.looper)
    private var cur = floatArrayOf(0f, 0f, 0f)
    /** The film's colour, full strength. */
    var palette = intArrayOf(255, 140, 70)
        private set
    private var dndWasOn = false
    @Volatile private var ended = false

    /** Before the picture: lights glide into the film's colour; notifications go quiet. */
    fun begin(artwork: Bitmap?) {
        link.begin()
        artwork?.let { palette = ColourEngine.dominant(it) }
        bg.postDelayed({
            // Start from how the lights were (read by begin() on the link's own thread a moment ago).
            link.before?.third?.let { cur = floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
            fadeTo(scaled(palette, 0.45f), 3000)
        }, 500)
        dndWasOn = CinemaMode.setDnd(app, true, "film_dnd_before")
    }

    /** TV Colour Lights off: a dim glow in the film's colour for the whole film. */
    fun hold() = bg.post { fadeTo(scaled(palette, 0.22f), 2500) }

    /** The credits: house lights up, slowly. */
    fun houseLights() = bg.post { fadeTo(scaled(HOUSE, 0.75f), 20_000) }

    /** Nodded off: fade to dark over a minute; the lights switch off when the film closes. */
    fun sleep() { link.asleep = true; bg.post { fadeTo(intArrayOf(0, 0, 0), 60_000) } }

    /** Leaving: lights and notifications back as they were. */
    fun end() {
        if (ended) return
        ended = true
        bg.removeCallbacksAndMessages(null)
        link.end()
        if (!dndWasOn) CinemaMode.setDnd(app, false, "film_dnd_before")
        thread.quitSafely()
    }

    private fun scaled(c: IntArray, k: Float) = intArrayOf((c[0] * k).toInt(), (c[1] * k).toInt(), (c[2] * k).toInt())

    /** A smooth glide to [to] over [ms], ~30 steps a second, eased in and out. */
    private fun fadeTo(to: IntArray, ms: Long) {
        bg.removeCallbacksAndMessages("fade")
        val from = cur.copyOf()
        val start = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (ended) return
                val t = ((android.os.SystemClock.uptimeMillis() - start) / ms.toFloat()).coerceIn(0f, 1f)
                val e = t * t * (3 - 2 * t)                    // smoothstep
                for (i in 0..2) cur[i] = from[i] + (to[i] - from[i]) * e
                link.colour(cur[0].toInt(), cur[1].toInt(), cur[2].toInt())
                if (t < 1f) bg.postAtTime(this, "fade", android.os.SystemClock.uptimeMillis() + 33)
            }
        }
        bg.postAtTime(step, "fade", android.os.SystemClock.uptimeMillis())
    }

    /** Where the lights are now (for TV Colour Lights to carry on from, without a dip to black). */
    fun current(): IntArray = intArrayOf(cur[0].toInt(), cur[1].toInt(), cur[2].toInt())

    /** Stop any glide (TV Colour Lights takes over the same connection). */
    fun handOver() = bg.removeCallbacksAndMessages("fade")
}
