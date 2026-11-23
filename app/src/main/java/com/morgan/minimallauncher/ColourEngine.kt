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
class ColourEngine(private val link: MagicHue.Sink, style: Int = 1) {
    /** How lively the room is (Settings › Lights Style): 0 Calm, 1 Balanced, 2 Intense. */
    private class Tune(val rise: Float, val pop: Float, val accMin: Int, val flashMin: Int, val confirm: Boolean,
                       val hiJump: Float, val brJump: Float, val up: Float, val down: Float, val drift: Float, val bigDrift: Float, val holdMs: Long)
    private val t = when (style) {
        0 -> Tune(0.26f, 0.26f, 8, 6, true, 0.36f, 0.20f, 260f, 700f, 900f, 450f, 800L)
        2 -> Tune(0.12f, 0.18f, 3, 2, false, 0.22f, 0.10f, 80f, 260f, 260f, 120f, 0L)
        else -> Tune(0.18f, 0.22f, 5, 4, true, 0.30f, 0.16f, 180f, 500f, 600f, 300f, 500L)
    }
    /**
     * The film's pace, as a multiplier on every glide: the genre sets where it starts (a drama drifts, an action film
     * moves), and the film itself moves it - how often the picture cuts over the last minute or so. A quiet
     * conversation glides slowly; a chase keeps up. Flashes, lightning and police lights still land instantly.
     */
    var genrePace = 1f
    /** Neutral (grey/white) scenes take the film's mood: a touch cooler for horror and thrillers ("tense"), a touch
     *  warmer for comedy, family and romance ("cosy") - after Philips Research's atmosphere studies. */
    var neutralTint = intArrayOf(255, 255, 255)
    /** Room light ceiling, 0..1 (Settings › Lights Brightness): bias lighting should sit well under the screen. */
    var maxLevel = 0.8f
    private var cutUntil = 0L
    private var punch = false                                       // a flash, cue or lightning: allowed past the ceiling
    private var cutRate = 12f                                       // picture cuts a minute, smoothed
    private var pace = 1f
    private var pendingBin = -10                                    // a flashing light must show on two frames running
    private var heldR = -1f; private var heldG = 0f; private var heldB = 0f; private var heldAt = 0L

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
    // Smoothing like a critically damped spring (HyperHDR's "hybrid physics" idea): changes ease in and ease out
    // instead of lurching off at full speed - smooth without being slow.
    private var vr = 0f; private var vg = 0f; private var vb = 0f; private var vl = 0f
    // Light from the picture's edges spills onto the wall around the TV, so the edges count a bit more (Ambilight).
    private var edgeW = FloatArray(0); private var edgeFor = 0
    private val binW = DoubleArray(18); private val binR = DoubleArray(18); private val binG = DoubleArray(18); private val binB = DoubleArray(18)

    // 4D Mode: while a sound cue's window is open, the lights follow the frame's brightest part at full strength.
    private var cueUntil = 0L
    private var blackUntil = 0L
    private var whiteUntil = 0L                                     // the jump scare's white flash

    /** 4D Mode's sense of the film (from [SoundEvents]): suspense sinks the room towards dark; rain makes it a storm. */
    @Volatile var tension = 0f
    @Volatile var rain = false
    private var stormUntil = 0L
    private var lastFlashAt = 0L
    private var flicker = 0L                                        // lightning's second strike

    /** Carry on from where the lights already are (no dip to black when taking over). */
    fun seed(rgb: IntArray, brightness: Float) {
        val m = max(rgb[0], max(rgb[1], rgb[2])).coerceAtLeast(1)
        r = rgb[0] * 255f / m; g = rgb[1] * 255f / m; b = rgb[2] * 255f / m
        level = brightness.coerceIn(0f, 1f)
        lastAt = SystemClock.uptimeMillis()
    }

    companion object {
        /** Where a film's lights start, by genre: dramas drift slowly, action keeps up (the film then adjusts it). */
        fun paceFor(genres: String): Float {
            val g = genres.lowercase()
            return when {
                g.isBlank() -> 1f
                Regex("action|adventure|war|sci|animation|anime").containsMatchIn(g) -> 0.85f
                Regex("horror|thriller|mystery|crime").containsMatchIn(g) -> 1.2f
                Regex("drama|romance|documentary|history|biograph|music").containsMatchIn(g) -> 1.5f
                Regex("comedy|family|kids").containsMatchIn(g) -> 1.1f
                else -> 1f
            }
        }

        /** The strip's white balance (red, green, blue gains), from Lights Calibration; LEDs run blue-heavy by default. */
        @Volatile var wb = floatArrayOf(1f, 0.86f, 0.70f)
        fun loadWb(c: android.content.Context) {
            val s = Prefs.string(c, "lights_wb")?.split(",")?.mapNotNull { it.toFloatOrNull() }
            // Kept within what still looks white (a runaway calibration once left the room purple and dark).
            if (s != null && s.size == 3) wb = floatArrayOf(s[0].coerceIn(0.7f, 1f), s[1].coerceIn(0.6f, 1f), s[2].coerceIn(0.45f, 1f))
        }
        fun saveWb(c: android.content.Context, v: FloatArray) { wb = v.copyOf(); Prefs.setString(c, "lights_wb", v.joinToString(",")) }

        /** The white a neutral scene takes, by genre (see [neutralTint]). */
        fun tintFor(genres: String): IntArray {
            val g = genres.lowercase()
            return when {
                Regex("horror|thriller|mystery|sci").containsMatchIn(g) -> intArrayOf(215, 230, 255)
                Regex("comedy|family|romance|kids|animation|music").containsMatchIn(g) -> intArrayOf(255, 225, 190)
                else -> intArrayOf(255, 245, 235)
            }
        }

        /** A picture's main colour, at full strength - the film's "palette" for the room. */
        fun dominant(bmp: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(bmp, 32, 18, true)
            val px = IntArray(32 * 18); small.getPixels(px, 0, 32, 0, 0, 32, 18)
            if (small !== bmp) small.recycle()
            val sink = object : MagicHue.Sink { var last = intArrayOf(255, 140, 70); override fun colour(r: Int, g: Int, b: Int) { last = intArrayOf(r, g, b) } }
            val e = ColourEngine(sink)
            repeat(40) { e.frame(px, px.size, 32) }
            // The colour itself, whatever the picture's brightness (a dark poster must still give its colour, not black).
            return e.fullColour()
        }

        private fun y(p: Int) = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
    }

    /** A 4D Mode sound cue: "boom" / "bang" light the room in the frame's brightest colour; "scare" blacks out first. */
    fun impulse(kind: String) {
        val now = SystemClock.uptimeMillis()
        when (kind) {
            "boom" -> { cueUntil = now + 320; if (now - lastFlashAt < 6000) stormUntil = now + 25_000 }   // thunder after lightning: a storm
            "bang" -> cueUntil = now + 140
            // The jump scare: the room flashes white, then cuts to black, then the film comes back.
            "scare" -> { whiteUntil = now + 130; blackUntil = now + 700; r = 255f; g = 255f; b = 255f; level = 1f; send(force = true) }
        }
    }

    /** One frame: [px] is ARGB, [n] pixels long, [w] pixels wide. */
    fun frame(px: IntArray, n: Int, w: Int = 96) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastAt == 0L) 33f else (now - lastAt).coerceIn(5, 200).toFloat()
        lastAt = now
        if (now < whiteUntil) { r = 255f; g = 255f; b = 255f; level = 1f; send(); return }
        if (now < blackUntil) { level = 0f; send(); return }

        if (edgeFor != n * 1000 + w && w > 1 && n / w > 1) {
            edgeFor = n * 1000 + w; edgeW = FloatArray(n); val hh = n / w
            for (i in 0 until n) {
                val ex = abs((i % w) - (w - 1) / 2f) / ((w - 1) / 2f); val ey = abs((i / w) - (hh - 1) / 2f) / ((hh - 1) / 2f)
                edgeW[i] = 1f + 0.8f * max(ex, ey).pow(2f)
            }
        }
        fun ew(i: Int) = if (edgeW.size >= n) edgeW[i] else 1f
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
                val wt = yv * yv * ew(i)                            // brighter areas (and the edges) carry the scene's colour
                wr += ((p shr 16) and 0xFF) * wt; wg += ((p shr 8) and 0xFF) * wt; wb += (p and 0xFF) * wt; wsum += wt
            }
        }
        val bright = if (counted == 0) 0f else (lum / counted).toFloat()
        if (prevY.size >= n && counted > 0) {
            var d = 0f; var m = 0
            for (i in 0 until n) if (ys[i] >= 0f && prevY[i] >= 0f) { d += abs(ys[i] - prevY[i]); m++ }
            // Read slowly (10-bit films), ordinary movement between reads looks like a cut - only trust it at full rate.
            val cut = m > 0 && d / m > 0.12f && dt < 150f
            // A new shot: the lights change WITH the cut (a quick ~¼ s crossfade), not drifting in afterwards. Lights
            // falling out of step with the picture is what made Ambilight feel distracting in Philips' own tests.
            if (cut) cutUntil = now + 260
            // ~45 s memory: each frame nudges the rate towards "this frame's cut, per minute".
            val a = dt / 45_000f
            cutRate += a * ((if (cut) 60_000f / dt else 0f) - cutRate)
            val dyn = (1.45f - (cutRate - 6f) / 24f * 0.7f).coerceIn(0.75f, 1.45f)
            pace = (genrePace * dyn).coerceIn(0.7f, 2.2f)
        }

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
            // Sent to the LEDs as real light (linear, see [send]) colours already come out as rich as on screen; only a
            // light lift on top (0.3 → 0.39, 0.5 → 0.6).
            hsv[1] = if (hsv[1] < 0.07f) 0f else min(1f, hsv[1].pow(0.8f) * 1.03f)
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
                if (rise > t.rise || pop > t.pop) {
                    val bin = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
                    val wgt = (hsv[1] * hsv[2] * (1f + 6f * max(0f, rise))).toDouble()
                    accW[bin] += wgt; accR[bin] += pr * wgt; accG[bin] += pg * wgt; accB[bin] += pb * wgt
                    accN++; accY += hsv[2]; if (rise > t.rise) flashN++
                }
            }
            // Murky, washed-out tones (most of a dark film) are neutral - boosting their faint tint just paints the
            // room an odd teal or green. Only clearly coloured parts of the picture count as colour.
            if (hsv[1] < 0.3f) { neutral += ys[i]; continue }
            val bin = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
            // Skin and warm interiors (soft orange) are in nearly every shot - they count for less, so the room isn't
            // orange all film; a real orange sunset or fire is far more saturated and still wins.
            val skin = hsv[0] in 5f..45f && hsv[1] < 0.55f
            val wgt = (hsv[1] * hsv[2] * ew(i) * (if (skin) 0.35f else 1f)).toDouble()
            binW[bin] += wgt; binR[bin] += pr * wgt; binG[bin] += pg * wgt; binB[bin] += pb * wgt
        }
        var best = 0
        for (k in 1 until 18) if (binW[k] + 0.5 * (binW[(k + 17) % 18] + binW[(k + 1) % 18]) > binW[best] + 0.5 * (binW[(best + 17) % 18] + binW[(best + 1) % 18])) best = k
        var cw = 0.0; var cr = 0.0; var cg = 0.0; var cb = 0.0
        for (k in intArrayOf((best + 17) % 18, best, (best + 1) % 18)) { cw += binW[k]; cr += binR[k]; cg += binG[k]; cb += binB[k] }
        if (cw > 0 && cw >= neutral * 0.35) richer(cr / cw, cg / cw, cb / cw)
        else if (wsum > 0) { richer(wr / wsum, wg / wsum, wb / wsum); hsv[1] = min(hsv[1], 0.15f) }
        else { hsv[0] = 0f; hsv[1] = 0f; hsv[2] = 1f }
        val neutralScene = hsv[1] <= 0.15f
        var c = Color.HSVToColor(hsv)
        tr = Color.red(c).toFloat(); tg = Color.green(c).toFloat(); tb = Color.blue(c).toFloat()
        if (neutralScene) { tr = tr * 0.5f + neutralTint[0] * 0.5f; tg = tg * 0.5f + neutralTint[1] * 0.5f; tb = tb * 0.5f + neutralTint[2] * 0.5f }

        // A flashing light in the picture, however small (a few pixels of a police car's light bar is enough): it
        // takes the room - full colour, snapping instantly from red to blue - and holds through the dark between
        // pulses, then hands back to the scene when it stops (the car's driven off).
        var accent = false
        var ab = -10
        if (accN >= t.accMin && flashN >= t.flashMin && accN < counted / 3) {
            ab = 0
            for (k in 1 until 18) if (accW[k] + 0.5 * (accW[(k + 17) % 18] + accW[(k + 1) % 18]) > accW[ab] + 0.5 * (accW[(ab + 17) % 18] + accW[(ab + 1) % 18])) ab = k
        }
        // Only a real flashing light, not one-frame noise: the same colour has to flash on two frames running.
        val confirmed = ab >= 0 && (!t.confirm || (pendingBin >= 0 && minOf((ab - pendingBin + 18) % 18, (pendingBin - ab + 18) % 18) <= 1))
        pendingBin = ab
        if (confirmed) {
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
        else {
            // The scene's colour settles: a new colour is kept for a moment before another can replace it, so busy
            // shots don't flick the room back and forth.
            val moved = heldR < 0 || abs(tr - heldR) + abs(tg - heldG) + abs(tb - heldB) > 60f
            if (moved && (heldR < 0 || now < cutUntil || now - heldAt >= (t.holdMs * pace).toLong())) { heldR = tr; heldG = tg; heldB = tb; heldAt = now }
            else if (!moved) { heldR = tr; heldG = tg; heldB = tb }
            else { tr = heldR; tg = heldG; tb = heldB }
        }

        // Real brightness: black is black (no glow floor); perceptually spaced so dim scenes still read.
        // Black is black: a dark scene (not just a pure black frame) leaves the room dark.
        var targetLevel = if (bright < 0.045f) 0f else ((bright - 0.03f) * 1.55f).coerceIn(0f, 1f).pow(0.85f)

        // A flash in the picture, or a 4D sound cue: the bright part's colour, full strength, instantly.
        val flash = (hiY - lastHi > t.hiJump && hiY > 0.45f) || bright - lastBright > t.brJump
        val cue = now < cueUntil
        if ((flash || cue) && hn > 0) {
            richer(hr / hn, hg / hn, hb / hn)
            c = Color.HSVToColor(hsv)
            tr = Color.red(c).toFloat(); tg = Color.green(c).toFloat(); tb = Color.blue(c).toFloat()
            targetLevel = max(targetLevel, if (cue) max(0.9f, hiY) else (0.7f + 0.3f * hiY))
        }
        if (flash) lastFlashAt = now
        if (rain) stormUntil = max(stormUntil, now + 8000)
        val storm = now < stormUntil
        if (storm) {
            if (flash) {
                // Lightning: cold white-blue, and a second strike a moment later, the way real lightning flickers.
                tr = 205f; tg = 220f; tb = 255f; targetLevel = 1f; flicker = now + 170
            } else if (!(cue || holdingAccent)) {
                // Between strikes: the scene washed storm blue-grey, the light rolling slowly like clouds and rain.
                tr = tr * 0.45f + 140f * 0.55f; tg = tg * 0.45f + 170f * 0.55f; tb = tb * 0.45f + 255f * 0.55f
                targetLevel *= 0.8f + 0.2f * kotlin.math.sin(now / 2600.0 * 2 * Math.PI).toFloat()
            }
        }
        val strike2 = flicker != 0L && now in (flicker - 80)..flicker
        if (strike2) { tr = 205f; tg = 220f; tb = 255f; targetLevel = if (now < flicker - 30) 0.12f else 1f }
        // Suspense: the longer the film holds its breath, the darker the room - so the scare lands in the dark.
        if (!(flash || cue || accent)) targetLevel *= 1f - 0.8f * tension
        if (holdingAccent && !(flash || cue)) targetLevel = max(targetLevel, if (accent) accLevel else accLevel * 0.6f)
        lastBright = bright; lastHi = hiY
        if (prevY.size < n) prevY = FloatArray(n)
        System.arraycopy(ys, 0, prevY, 0, n)

        val snap = flash || cue || accent || strike2                   // lands on the frame it happens
        // Flashes may go past the ceiling while they last; afterwards the level eases back under it.
        punch = snap || level > maxLevel + 0.02f && punch
        if (snap) { level = targetLevel; r = tr; g = tg; b = tb; vl = 0f; vr = 0f; vg = 0f; vb = 0f }
        else {
            val onCut = now < cutUntil
            val sl = if (onCut) 0.09f else when { targetLevel > level -> t.up; targetLevel == 0f -> 200f; else -> t.down } * pace * 1.4f / 1000f
            level = damp(level, targetLevel, 0, sl, dt / 1000f).coerceIn(0f, 1f)
            val jump = abs(tr - r) + abs(tg - g) + abs(tb - b)
            // Within a shot: slow and steady (only real changes in the shot move the room); on a cut: quick.
            val sc = if (onCut) 0.11f else (if (jump > 150f) t.bigDrift else t.drift) * pace * 1.6f / 1000f
            r = damp(r, tr, 1, sc, dt / 1000f); g = damp(g, tg, 2, sc, dt / 1000f); b = damp(b, tb, 3, sc, dt / 1000f)
        }
        send()
    }

    /** Critically damped spring towards [target] (Game Programming Gems 4's SmoothDamp): stable at any frame time. */
    private fun damp(cur: Float, target: Float, ch: Int, smooth: Float, dt: Float): Float {
        val omega = 2f / smooth.coerceAtLeast(0.01f)
        val x = omega * dt
        val e = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val change = cur - target
        val v = when (ch) { 0 -> vl; 1 -> vr; 2 -> vg; else -> vb }
        val temp = (v + omega * change) * dt
        val nv = (v - omega * temp) * e
        when (ch) { 0 -> vl = nv; 1 -> vr = nv; 2 -> vg = nv; else -> vb = nv }
        return target + (change + temp) * e
    }

    // Most of the way to real light (1.8 rather than the full 2.2): rich colours without every warm tone going orange.
    private fun lin(x: Float) = (x / 255f).coerceIn(0f, 1f).pow(1.8f)

    private fun send(force: Boolean = false) {
        // The screen's colours are gamma-encoded; LEDs make light in proportion to what they're sent. So the colour goes
        // to the strip as real light (an orange stays orange instead of turning yellow, skin stays warm, not pale), and
        // brightness gets the LEDs' own curve so dim scenes are dim and bright ones bright, like the screen.
        // Flashes and cues may use the full strip; everything else stays under the room ceiling.
        val cap = if (punch || SystemClock.uptimeMillis() < whiteUntil) 1f else maxLevel
        val pwm = min(level, cap).coerceIn(0f, 1f).pow(1.3f) * 255f
        // Brightness compensation (as in Hyperion): white and yellow light two or three LED colours at once and swamp
        // the room, while a pure red or blue looks dim - so mixed colours are held back to keep every hue equally
        // bright, and saturated colours keep their punch.
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        val comp = min(1f, 1.9f / (lr + lg + lb).coerceAtLeast(0.01f))
        // LED white balance (their blue and green run much stronger than their red), so white looks white.
        val w = wb
        var o0 = (lr * comp * w[0] * pwm + 0.5f).toInt().coerceIn(0, 255)
        var o1 = (lg * comp * w[1] * pwm + 0.5f).toInt().coerceIn(0, 255)
        var o2 = (lb * comp * w[2] * pwm + 0.5f).toInt().coerceIn(0, 255)
        // Below a few steps an 8-bit LED can't hold a colour (it jumps between hues): that's black.
        if (max(o0, max(o1, o2)) < 2) { o0 = 0; o1 = 0; o2 = 0 }
        // Anti-flicker: in dark scenes a one-step change in one colour is visible as a hue wobble - wait for a real change.
        val need = if (max(o0, max(o1, o2)) < 24) 3 else 2
        if (!force && abs(o0 - sent[0]) + abs(o1 - sent[1]) + abs(o2 - sent[2]) < need) return
        sent[0] = o0; sent[1] = o1; sent[2] = o2
        link.colour(o0, o1, o2)
    }

    /** The current colour as a screen colour at full strength (what [glide] takes). */
    fun screenColour(): IntArray {
        val m = maxOf(r, g, b)
        if (m < 5f) return intArrayOf(255, 214, 180)
        return intArrayOf((r / m * 255f).toInt(), (g / m * 255f).toInt(), (b / m * 255f).toInt())
    }

    /** The current colour at full strength, as the LEDs show it (light, white-balanced). */
    fun fullColour(): IntArray {
        val w = wb
        val c = floatArrayOf(lin(r) * w[0], lin(g) * w[1], lin(b) * w[2])
        val m = maxOf(c[0], c[1], c[2])
        if (m < 0.02f) return intArrayOf(255, 190, 140)
        return IntArray(3) { i -> (c[i] / m * 255f).toInt().coerceIn(0, 255) }
    }

    /** Ease a step towards a set colour and brightness (the paused film's warm house lights). */
    fun glide(rgb: IntArray, lvl: Float, k: Float) {
        r += (rgb[0] - r) * k; g += (rgb[1] - g) * k; b += (rgb[2] - b) * k; level += (lvl - level) * k
        send()
    }

    /** Nothing on screen to follow (paused): ease down towards dark rather than holding the last colour. */
    fun idle() { frame(IntArray(1), 1, 1) }
}
