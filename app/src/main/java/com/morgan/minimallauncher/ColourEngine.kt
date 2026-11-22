package com.morgan.minimallauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns frames of the picture into the room's light - faithfully, not decoratively:
 *  - the scene's real colour: an average weighted by brightness (so a small bright logo can't take over, and
 *    nothing is invented), with only a light saturation lift for the LEDs; grey and white scenes stay neutral white;
 *  - real brightness: a black screen is black lights (no glow floor), a dim night scene is a dim glow, and
 *    letterbox bars are ignored;
 *  - flashes from the picture itself: when any part of the frame suddenly gets much brighter (lightning, a muzzle
 *    flash, an explosion, headlights) the lights jump to the colour of that bright part, at full strength;
 *  - 4D Mode's sound cues ([impulse]) do the same on the beat of the sound - a blast lights the room in the
 *    fireball's own colour, taken from the frame;
 *  - small flashing lights (police lights, sirens, muzzle flashes) take the room for a moment in their own colour;
 *  - motion like Ambilight: flashes land instantly, rises in ~70 ms, falls glide over ~¼ s.
 *
 * Fed with ARGB pixels (a small frame, [w] pixels wide) about 30 times a second; sends to [link].
 */
class ColourEngine(private val link: MagicHue.Sink) {
    private var r = 0f; private var g = 0f; private var b = 0f     // current colour at full strength
    internal var level = 0f                                         // current brightness 0..1
    internal var lastBright = 0f                                    // last scene brightness
    private var lastHi = 0f                                         // last highlight brightness
    private var lastAt = 0L
    private val sent = intArrayOf(-1, -1, -1)
    private val hsv = FloatArray(3)
    private var ys = FloatArray(0)
    private var prevY = FloatArray(0)                              // last frame's brightness per pixel (what just lit up)
    private val hist = IntArray(64)
    private val accW = DoubleArray(18); private val accR = DoubleArray(18); private val accG = DoubleArray(18); private val accB = DoubleArray(18)
    // A small, vivid light in the picture that's flashing (police lights, a muzzle flash, a neon sign flickering on):
    // the room takes its colour for a moment - the red, then the blue, snapping between them like the real thing.
    private var accentUntil = 0L
    private var accR0 = 0f; private var accG0 = 0f; private var accB0 = 0f; private var accLevel = 0f
    private val binW = DoubleArray(18); private val binR = DoubleArray(18); private val binG = DoubleArray(18); private val binB = DoubleArray(18)

    // 4D Mode: while a sound cue's window is open, the lights follow the frame's brightest part at full strength.
    private var cueUntil = 0L
    private var blackUntil = 0L

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
            repeat(40) { e.frame(px, px.size, 32) }
            val m = max(sink.last[0], max(sink.last[1], sink.last[2])).coerceAtLeast(1)
            return intArrayOf(sink.last[0] * 255 / m, sink.last[1] * 255 / m, sink.last[2] * 255 / m)
        }

        private fun y(p: Int) = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
    }

    /** A 4D Mode sound cue: "boom" / "bang" light the room in the frame's brightest colour; "scare" blacks out first. */
    fun impulse(kind: String) {
        val now = SystemClock.uptimeMillis()
        when (kind) {
            "boom" -> cueUntil = now + 320
            "bang" -> cueUntil = now + 140
            "scare" -> { blackUntil = now + 140; cueUntil = now + 480; level = 0f; send(force = true) }
        }
    }

    /** One frame: [px] is ARGB, [n] pixels long, [w] pixels wide. */
    fun frame(px: IntArray, n: Int, w: Int = 96) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastAt == 0L) 33f else (now - lastAt).coerceIn(5, 200).toFloat()
        lastAt = now
        fun ease(tauMs: Float) = 1f - exp(-dt / tauMs)
        if (now < blackUntil) { level = 0f; send(); return }

        // Brightness of every pixel; rows that are black all the way across are letterbox bars - left out.
        if (ys.size < n) ys = FloatArray(n)
        val rows = if (w > 0) n / w else 1
        var lum = 0.0; var counted = 0
        var wr = 0.0; var wg = 0.0; var wb = 0.0; var wsum = 0.0
        for (row in 0 until rows) {
            var rowMax = 0f
            for (x in 0 until w) { val yv = y(px[row * w + x]); ys[row * w + x] = yv; if (yv > rowMax) rowMax = yv }
            if (rowMax < 0.04f) { for (x in 0 until w) ys[row * w + x] = -1f; continue }
            for (x in 0 until w) {
                val i = row * w + x; val yv = ys[i]; val p = px[i]
                lum += yv; counted++
                if (yv < 0.03f) continue
                val wt = yv * yv                                    // brighter areas carry the scene's colour
                wr += ((p shr 16) and 0xFF) * wt; wg += ((p shr 8) and 0xFF) * wt; wb += (p and 0xFF) * wt; wsum += wt
            }
        }
        val bright = if (counted == 0) 0f else (lum / counted).toFloat()

        // The brightest tenth of the picture: its colour and brightness (the fireball, the lightning, the lamp).
        var hr = 0.0; var hg = 0.0; var hb = 0.0; var hn = 0; var hiY = 0f
        if (counted > 0) {
            java.util.Arrays.fill(hist, 0)
            for (i in 0 until n) if (ys[i] >= 0f) hist[(ys[i] * 63f).toInt().coerceIn(0, 63)]++
            var above = 0; var k = 63
            while (k > 0 && above + hist[k] < counted / 10) { above += hist[k]; k-- }
            val cut = k / 63f
            for (i in 0 until n) if (ys[i] >= cut && ys[i] > 0.2f) {
                val p = px[i]; hr += (p shr 16) and 0xFF; hg += (p shr 8) and 0xFF; hb += p and 0xFF; hn++; hiY += ys[i]
            }
            if (hn > 0) hiY /= hn
        }

        // The scene's colour: its DOMINANT colour, not its average - an average of Marvel red and white titles is a
        // washed-out pink. Pixels are grouped by hue (18 groups), weighted by how colourful and lit they are; the
        // strongest group (with its neighbours) is the colour. White only when the scene really is mostly neutral.
        var tr: Float; var tg: Float; var tb: Float
        fun richer(cr: Double, cg: Double, cb: Double) {
            Color.RGBToHSV(cr.toInt().coerceIn(0, 255), cg.toInt().coerceIn(0, 255), cb.toInt().coerceIn(0, 255), hsv)
            // LEDs need colour pushed well up to look coloured at all (0.3 → 0.56, 0.5 → 0.75).
            hsv[1] = if (hsv[1] < 0.07f) 0f else min(1f, hsv[1].pow(0.55f) * 1.08f)
            hsv[2] = 1f
        }
        java.util.Arrays.fill(binW, 0.0); java.util.Arrays.fill(binR, 0.0); java.util.Arrays.fill(binG, 0.0); java.util.Arrays.fill(binB, 0.0)
        var neutral = 0.0
        java.util.Arrays.fill(accW, 0.0); java.util.Arrays.fill(accR, 0.0); java.util.Arrays.fill(accG, 0.0); java.util.Arrays.fill(accB, 0.0)
        var accN = 0; var flashN = 0; var accY = 0f
        val havePrev = prevY.size >= n
        for (i in 0 until n) {
            if (ys[i] < 0.04f) continue
            val p = px[i]
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            Color.RGBToHSV(pr, pg, pb, hsv)
            // Small vivid lights: strongly coloured, bright, and standing out from the scene or just switched on.
            if (hsv[1] >= 0.45f && hsv[2] >= 0.5f) {
                val rise = if (havePrev && prevY[i] >= 0f) ys[i] - prevY[i] else 0f
                val pop = ys[i] - bright
                if (rise > 0.12f || pop > 0.18f) {
                    val bin = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
                    val wgt = (hsv[1] * hsv[2] * (1f + 6f * max(0f, rise))).toDouble()
                    accW[bin] += wgt; accR[bin] += pr * wgt; accG[bin] += pg * wgt; accB[bin] += pb * wgt
                    accN++; accY += hsv[2]; if (rise > 0.12f) flashN++
                }
            }
            if (hsv[1] < 0.2f) { neutral += ys[i]; continue }
            val bin = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
            val wgt = (hsv[1] * hsv[2]).toDouble()
            binW[bin] += wgt; binR[bin] += pr * wgt; binG[bin] += pg * wgt; binB[bin] += pb * wgt
        }
        var best = 0
        for (k in 1 until 18) if (binW[k] + 0.5 * (binW[(k + 17) % 18] + binW[(k + 1) % 18]) > binW[best] + 0.5 * (binW[(best + 17) % 18] + binW[(best + 1) % 18])) best = k
        var cw = 0.0; var cr = 0.0; var cg = 0.0; var cb = 0.0
        for (k in intArrayOf((best + 17) % 18, best, (best + 1) % 18)) { cw += binW[k]; cr += binR[k]; cg += binG[k]; cb += binB[k] }
        if (cw > 0 && cw * 2.5 >= neutral * 0.5) richer(cr / cw, cg / cw, cb / cw)
        else if (wsum > 0) { richer(wr / wsum, wg / wsum, wb / wsum); hsv[1] = min(hsv[1], 0.15f) }
        else { hsv[0] = 0f; hsv[1] = 0f; hsv[2] = 1f }
        var c = Color.HSVToColor(hsv)
        tr = Color.red(c).toFloat(); tg = Color.green(c).toFloat(); tb = Color.blue(c).toFloat()

        // A flashing light in the picture, however small (a few pixels of a police car's light bar is enough): it
        // takes the room - full colour, snapping instantly from red to blue - and holds through the dark between
        // pulses, then hands back to the scene when it stops (the car's driven off).
        var accent = false
        if (accN >= 3 && flashN >= 2 && accN < counted / 3) {
            var ab = 0
            for (k in 1 until 18) if (accW[k] + 0.5 * (accW[(k + 17) % 18] + accW[(k + 1) % 18]) > accW[ab] + 0.5 * (accW[(ab + 17) % 18] + accW[(ab + 1) % 18])) ab = k
            var aw = 0.0; var ar = 0.0; var ag = 0.0; var abl = 0.0
            for (k in intArrayOf((ab + 17) % 18, ab, (ab + 1) % 18)) { aw += accW[k]; ar += accR[k]; ag += accG[k]; abl += accB[k] }
            if (aw > 0) {
                richer(ar / aw, ag / aw, abl / aw); hsv[1] = max(hsv[1], 0.85f)
                val ac = Color.HSVToColor(hsv)
                accR0 = Color.red(ac).toFloat(); accG0 = Color.green(ac).toFloat(); accB0 = Color.blue(ac).toFloat()
                accLevel = (0.55f + 0.45f * (accY / accN)).coerceAtMost(1f)
                accentUntil = now + 220
                accent = true
            }
        }
        val holdingAccent = now < accentUntil
        if (holdingAccent) { tr = accR0; tg = accG0; tb = accB0 }

        // Real brightness: black is black (no glow floor); perceptually spaced so dim scenes still read.
        var targetLevel = if (bright < 0.018f) 0f else (bright.pow(0.8f) * 1.7f).coerceIn(0f, 1f)

        // A flash in the picture, or a 4D sound cue: the bright part's colour, full strength, instantly.
        val flash = (hiY - lastHi > 0.22f && hiY > 0.45f) || bright - lastBright > 0.10f
        val cue = now < cueUntil
        if ((flash || cue) && hn > 0) {
            richer(hr / hn, hg / hn, hb / hn)
            c = Color.HSVToColor(hsv)
            tr = Color.red(c).toFloat(); tg = Color.green(c).toFloat(); tb = Color.blue(c).toFloat()
            targetLevel = max(targetLevel, if (cue) max(0.85f, hiY) else hiY.coerceAtLeast(0.6f))
        }
        if (holdingAccent && !(flash || cue)) targetLevel = max(targetLevel, if (accent) accLevel else accLevel * 0.6f)
        lastBright = bright; lastHi = hiY
        if (prevY.size < n) prevY = FloatArray(n)
        System.arraycopy(ys, 0, prevY, 0, n)

        val kl = when {
            flash || cue || accent -> 1f                               // lands on the frame it happens
            targetLevel > level -> ease(70f)
            targetLevel == 0f -> ease(140f)                            // fades to black quickly, like the picture
            else -> ease(240f)
        }
        level += (targetLevel - level) * kl

        val jump = abs(tr - r) + abs(tg - g) + abs(tb - b)
        val kc = if (flash || cue || accent) 1f else if (jump > 150f) ease(90f) else ease(200f)
        r += (tr - r) * kc; g += (tg - g) * kc; b += (tb - b) * kc
        send()
    }

    private fun send(force: Boolean = false) {
        // LED white balance (their blue and green run much stronger than their red), so white looks white.
        val o0 = (r * level).toInt().coerceIn(0, 255)
        val o1 = (g * 0.86f * level).toInt().coerceIn(0, 255)
        val o2 = (b * 0.70f * level).toInt().coerceIn(0, 255)
        if (!force && abs(o0 - sent[0]) + abs(o1 - sent[1]) + abs(o2 - sent[2]) < 2) return
        sent[0] = o0; sent[1] = o1; sent[2] = o2
        link.colour(o0, o1, o2)
    }

    /** Nothing on screen to follow (paused): ease down towards dark rather than holding the last colour. */
    fun idle() { frame(IntArray(1), 1, 1) }
}
