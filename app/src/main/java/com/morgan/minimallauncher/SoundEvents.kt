package com.morgan.minimallauncher

import android.os.Handler
import android.os.Looper
import kotlin.math.log10

/**
 * 4D Mode's ears: fed every sample of the film's sound (inside the Virtual Surround mixer), it notices the moments
 * a room should react to and announces them:
 *  - "boom"  - a sudden surge of deep bass (explosions, crashes, thunder);
 *  - "bang"  - a sharp crack well above what came just before (gunshots, lightning, slammed doors);
 *  - "scare" - a big jump in loudness after a long quiet stretch (the jump scare).
 * Cheap: a few one-pole envelopes per sample, a decision every ~5 ms.
 */
object SoundEvents {
    /** Called on the main thread with "boom", "bang" or "scare". */
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private var rate = 0
    private var aFast = 0f; private var aSlow = 0f; private var aQuiet = 0f; private var aLow = 0f
    private var fast = 0f; private var slow = 1e-6f; private var quiet = 1e-6f
    private var low = 0f; private var lowFast = 0f; private var lowSlow = 1e-6f
    private var count = 0
    private var lastBoom = 0L; private var lastBang = 0L; private var lastScare = 0L

    private fun db(p: Float) = 10f * log10(p.coerceAtLeast(1e-10f))

    fun feed(x: Float, fs: Int) {
        if (listener == null) return
        if (fs != rate) {
            rate = fs
            fun coef(sec: Double) = (1.0 - kotlin.math.exp(-1.0 / (sec * fs))).toFloat()
            aFast = coef(0.012); aSlow = coef(0.6); aQuiet = coef(3.0); aLow = coef(1.0 / (2 * Math.PI * 140))
        }
        val p = x * x
        fast += aFast * (p - fast)
        slow += aSlow * (p - slow)
        quiet += aQuiet * (p - quiet)
        low += aLow * (x - low)
        val lp = low * low
        lowFast += aFast * (lp - lowFast)
        lowSlow += aSlow * (lp - lowSlow)
        if (++count < 240) return
        count = 0
        val now = android.os.SystemClock.uptimeMillis()
        val f = db(fast); val s = db(slow); val q = db(quiet)
        val lf = db(lowFast); val ls = db(lowSlow)
        when {
            // Long quiet, then suddenly very loud: the jump scare.
            q < -40f && f > -18f && f - q > 24f && now - lastScare > 8000 -> { lastScare = now; lastBang = now; lastBoom = now; emit("scare") }
            // Deep bass surging well above the last moments, and loud.
            lf > -20f && lf - ls > 10f && now - lastBoom > 1200 -> { lastBoom = now; emit("boom") }
            // A sharp crack well above what came just before.
            f > -16f && f - s > 13f && now - lastBang > 250 -> { lastBang = now; emit("bang") }
        }
    }

    private fun emit(kind: String) { val l = listener ?: return; main.post { l(kind) } }
}
