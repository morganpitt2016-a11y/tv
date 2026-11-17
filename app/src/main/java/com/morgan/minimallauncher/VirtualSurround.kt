package com.morgan.minimallauncher

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Virtual Surround: everything (stereo, 5.1, 7.1, Dolby once decoded) mixed into surround-encoded stereo for a
 * soundbar on an optical cable (which can only carry two channels of decoded sound) - and the sound profile done here
 * too, because this Chromecast won't let Android's own sound effects attach to decoded Dolby.
 *
 *  - Mixing follows Dolby Pro Logic II encoding: voices (centre) up the middle, the bass channel into both sides,
 *    each rear channel into both sides in opposite phase - heard as width beyond the speakers, and pulled back out
 *    as surround by a soundbar's Surround / Cinema mode.
 *  - Enhance Dialogue lifts the centre channel before mixing (surround) and the middle of the mix around the voice
 *    band (stereo); bass / clarity / treble are shelf and bell filters; Reduce Loud Sounds is a gentle compressor.
 *  - A soft limiter at the end so nothing ever crackles.
 */
object VirtualSurround {
    /** The listener's sound profile, read by the mixer as it plays (changes apply straight away). */
    @Volatile var settings: SoundProfile.Settings = SoundProfile.Settings(0, 0, 0, false, false)

    private const val G = 0.55f            // headroom for surround mixes
    private val FL = floatArrayOf(G, 0f)
    private val FR = floatArrayOf(0f, G)
    private val LFE = floatArrayOf(0.5f * G, 0.5f * G)
    private val SL = floatArrayOf(-0.87f * G, 0.49f * G)
    private val SR = floatArrayOf(-0.49f * G, 0.87f * G)
    private fun centre(boost: Float) = floatArrayOf(0.707f * G * boost, 0.707f * G * boost)

    /** Each input channel as (to left, to right), in Android's order: FL FR FC LFE BL BR SL SR. */
    private fun rows(n: Int, dlg: Float): Array<FloatArray> = when (n) {
        1 -> arrayOf(floatArrayOf(0.7f, 0.7f))
        2 -> arrayOf(floatArrayOf(0.85f, 0f), floatArrayOf(0f, 0.85f))
        3 -> arrayOf(FL, FR, centre(dlg))
        4 -> arrayOf(FL, FR, SL, SR)
        5 -> arrayOf(FL, FR, centre(dlg), SL, SR)
        6 -> arrayOf(FL, FR, centre(dlg), LFE, SL, SR)
        7 -> arrayOf(FL, FR, centre(dlg), LFE, floatArrayOf(-0.6f * G, 0.6f * G), SL, SR)
        8 -> arrayOf(FL, FR, centre(dlg), LFE,
            floatArrayOf(-0.7f * G, 0.39f * G), floatArrayOf(-0.39f * G, 0.7f * G),
            floatArrayOf(-0.6f * G, 0.34f * G), floatArrayOf(-0.34f * G, 0.6f * G))
        else -> Array(n) { i -> when (i) { 0 -> FL; 1 -> FR; else -> floatArrayOf(0.3f * G, 0.3f * G) } }
    }

    fun mixer(): AudioProcessor = Mixer()

    /** One second-order filter (RBJ cookbook), run on one signal. */
    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun clear() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
        private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        }
        fun peak(fs: Int, f: Double, db: Double, q: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
            set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }
        fun lowShelf(fs: Int, f: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val r = 2 * sqrt(a) * al
            set(a * ((a + 1) - (a - 1) * c + r), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - r),
                (a + 1) + (a - 1) * c + r, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - r)
        }
        fun highShelf(fs: Int, f: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val r = 2 * sqrt(a) * al
            set(a * ((a + 1) + (a - 1) * c + r), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - r),
                (a + 1) - (a - 1) * c + r, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - r)
        }
    }

    private class Mixer : BaseAudioProcessor() {
        private var n = 2
        private var fs = 48_000
        private var applied: SoundProfile.Settings? = null
        private var coeffs: Array<FloatArray> = emptyArray()
        // The middle of the mix (voices) and the sides (width) are shaped separately.
        private val mid = Array(4) { Biquad() }
        private val side = Array(3) { Biquad() }
        private var env = 0f
        private var atk = 0f; private var rel = 0f

        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (f.encoding != C.ENCODING_PCM_16BIT || f.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
            n = f.channelCount; fs = f.sampleRate
            applied = null
            atk = exp(-1.0 / (0.008 * fs)).toFloat(); rel = exp(-1.0 / (0.25 * fs)).toFloat()
            return AudioProcessor.AudioFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }

        override fun onFlush() { mid.forEach { it.clear() }; side.forEach { it.clear() }; env = 0f }

        private fun tune(s: SoundProfile.Settings) {
            applied = s
            val dlg = if (!s.dialogue) 0.0 else if (s.more) 8.0 else 5.0
            coeffs = rows(n, 10f.pow((dlg * 0.8 / 20).toFloat()))
            // Stereo has no centre channel: the voice band in the middle of the mix gets the whole lift.
            val voice = (if (n <= 2) dlg else dlg * 0.4) + s.clarity
            mid[0].lowShelf(fs, 150.0, s.bass.toDouble() - (if (s.dialogue) 1.5 else 0.0))
            mid[1].peak(fs, 600.0, -(dlg * 0.4) - s.clarity * 0.3, 1.0)
            mid[2].peak(fs, 3000.0, voice, 0.9)
            mid[3].highShelf(fs, 8000.0, s.treble * 1.0 + s.clarity * 0.3)
            side[0].lowShelf(fs, 150.0, s.bass.toDouble())
            side[1].peak(fs, 600.0, -(dlg * 0.2), 1.0)
            side[2].highShelf(fs, 8000.0, s.treble.toDouble())
        }

        override fun queueInput(input: ByteBuffer) {
            val s = settings
            if (s != applied) tune(s)
            val frames = input.remaining() / (2 * n)
            val out = replaceOutputBuffer(frames * 4)
            val m = coeffs
            val level = s.level
            val makeup = if (level) 1.9f else 1f              // +5.5 dB back after the compressor
            for (f in 0 until frames) {
                var l = 0f; var r = 0f
                for (ch in 0 until n) {
                    val x = input.getShort() / 32768f
                    l += x * m[ch][0]; r += x * m[ch][1]
                }
                // Middle and sides.
                var md = (l + r) * 0.5f; var sd = (l - r) * 0.5f
                for (b in mid) md = b.run(md)
                for (b in side) sd = b.run(sd)
                l = md + sd; r = md - sd
                // Reduce Loud Sounds: 3:1 above -24 dB, quick to catch, slow to let go.
                if (level) {
                    val peak = maxOf(abs(l), abs(r))
                    env = if (peak > env) atk * env + (1 - atk) * peak else rel * env + (1 - rel) * peak
                    val db = 20 * log10(env.coerceAtLeast(1e-6f))
                    val g = if (db > -24f) 10f.pow(-(db + 24f) * (1f - 1f / 3f) / 20f) else 1f
                    l *= g * makeup; r *= g * makeup
                }
                out.putShort(soft(l)); out.putShort(soft(r))
            }
            input.position(input.limit())
            out.flip()
        }

        /** Soft limiter: untouched up to 90%, then eased in so peaks never crackle. */
        private fun soft(x: Float): Short {
            val a = abs(x)
            val y = if (a <= 0.9f) x else (if (x > 0) 1f else -1f) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
            return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
