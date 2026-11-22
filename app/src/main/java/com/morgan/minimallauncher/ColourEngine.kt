package com.morgan.minimallauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns frames of the picture into a light colour, the way Ambilight / Hue Sync feel: the main colour on screen
 * (vivid, well-lit areas count most; black bars and shadows barely), pushed richer for LEDs, and moved with an
 * envelope - FAST ATTACK, SMOOTH DECAY. Light rises to a flash or a cut within a few frames (a gunshot pops), then
 * glides back over about a third of a second, and calm scenes drift gently - punchy without strobing.
 *
 * Fed with ARGB pixels (any small size) about 30 times a second; sends to the Magic Hue [MagicHue.Link].
 */
class ColourEngine(private val link: MagicHue.Sink) {
    private var r = 0f; private var g = 0f; private var b = 0f
    internal var level = 0f           // smoothed scene brightness 0..1
    internal var lastBright = 0f
    private var lastAt = 0L
    private val sent = intArrayOf(-1, -1, -1)
    private val hsv = FloatArray(3)

    /** Carry on from where the lights already are (no dip to black when taking over). */
    fun seed(rgb: IntArray, brightness: Float) {
        val m = max(rgb[0], max(rgb[1], rgb[2])).coerceAtLeast(1)
        r = rgb[0] * 255f / m; g = rgb[1] * 255f / m; b = rgb[2] * 255f / m
        level = brightness.coerceIn(0f, 1f)
        lastAt = SystemClock.uptimeMillis()
    }

    companion object {
        /** A picture's main colour, at full strength - the film's "palette" for the room. */
        fun dominant(bmp: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(bmp, 32, 18, true)
            val px = IntArray(32 * 18); small.getPixels(px, 0, 32, 0, 0, 32, 18)
            if (small !== bmp) small.recycle()
            val sink = object : MagicHue.Sink { var last = intArrayOf(255, 140, 70); override fun colour(r: Int, g: Int, b: Int) { last = intArrayOf(r, g, b) } }
            val e = ColourEngine(sink)
            e.level = 1f; e.lastBright = 1f
            repeat(40) { e.frame(px, px.size) }          // let it settle on the picture's colour
            val m = max(sink.last[0], max(sink.last[1], sink.last[2])).coerceAtLeast(1)
            return intArrayOf(sink.last[0] * 255 / m, sink.last[1] * 255 / m, sink.last[2] * 255 / m)
        }
    }

    /** One frame. [px] is ARGB, [n] pixels long. */
    fun frame(px: IntArray, n: Int) {
        var wr = 0.0; var wg = 0.0; var wb = 0.0; var wsum = 0.0; var lum = 0.0
        for (i in 0 until n) {
            val p = px[i]
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
            val v = mx / 255f
            lum += (0.2126 * pr + 0.7152 * pg + 0.0722 * pb) / 255.0
            if (v < 0.08f) continue                                  // black bars, deep shadow
            val s = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            val w = (0.15 + s * s * 1.6) * v                         // colourful and lit counts most
            wr += pr * w; wg += pg * w; wb += pb * w; wsum += w
        }
        val bright = (lum / max(n, 1)).toFloat()

        // Target colour at full strength (brightness is handled by the envelope below).
        var tr = 255f * 0.9f; var tg = 255f * 0.62f; var tb = 255f * 0.35f   // warm white when there's no colour
        if (wsum > 0) {
            Color.RGBToHSV((wr / wsum).toInt(), (wg / wsum).toInt(), (wb / wsum).toInt(), hsv)
            // LEDs turn pale colours into icy blue-white: push colour hard, and make colourless scenes warm white.
            if (hsv[1] < 0.10f) { hsv[0] = 32f; hsv[1] = 0.45f } else hsv[1] = min(1f, hsv[1] * 1.9f + 0.15f)
            hsv[2] = 1f
            val c = Color.HSVToColor(hsv)
            // LED white balance: their blue and green run much stronger than their red.
            tr = Color.red(c).toFloat(); tg = Color.green(c) * 0.86f; tb = Color.blue(c) * 0.70f
        }

        // Time-based easing, so it feels the same however fast frames arrive.
        val now = SystemClock.uptimeMillis()
        val dt = if (lastAt == 0L) 33f else (now - lastAt).coerceIn(5, 200).toFloat()
        lastAt = now
        fun ease(tauMs: Float) = 1f - exp(-dt / tauMs)

        // Brightness: a sudden jump (flash, explosion) attacks in ~40 ms; rising scenes in ~90 ms; fading in ~350 ms.
        val targetLevel = (0.10f + bright * 1.6f).coerceIn(0.06f, 1f).let { if (bright - lastBright > 0.10f) 1f else it }
        lastBright = bright
        val kl = when {
            targetLevel >= 1f && targetLevel > level -> ease(40f)
            targetLevel > level -> ease(90f)
            else -> ease(350f)
        }
        level += (targetLevel - level) * kl

        // Colour: a cut (big change) moves in ~110 ms, ordinary drift in ~260 ms - never a hard snap.
        val jump = abs(tr - r) + abs(tg - g) + abs(tb - b)
        val kc = if (jump > 150f) ease(110f) else ease(260f)
        r += (tr - r) * kc; g += (tg - g) * kc; b += (tb - b) * kc

        val out0 = (r * level).toInt().coerceIn(0, 255)
        val out1 = (g * level).toInt().coerceIn(0, 255)
        val out2 = (b * level).toInt().coerceIn(0, 255)
        // Every small step goes out, so fades look continuous rather than stepped.
        if (abs(out0 - sent[0]) + abs(out1 - sent[1]) + abs(out2 - sent[2]) < 2) return
        sent[0] = out0; sent[1] = out1; sent[2] = out2
        link.colour(out0, out1, out2)
    }

    /** Nothing worth lighting (paused, no picture): ease down to a dim warm glow rather than holding the last colour. */
    fun idle() {
        val px = IntArray(1)
        frame(px, 1)
    }
}
