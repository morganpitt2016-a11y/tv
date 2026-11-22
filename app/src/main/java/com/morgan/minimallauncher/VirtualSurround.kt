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

    /** Speakers: false = a soundbar with a subwoofer (deep bass, surround); true = a TV's own small speakers. */
    @Volatile var tvSpeakers = false

    /** Told how long the decoder took to give real (not silent) sound after starting - for the TV's log. */
    @Volatile var onFirstSound: ((Long) -> Unit)? = null

    private const val G = 0.68f            // level for surround mixes: room for the bass without the limiter pumping
    private val FL = floatArrayOf(G, 0f)
    private val FR = floatArrayOf(0f, G)
    private val LFE = floatArrayOf(1.0f * G, 1.0f * G)   // the film's bass channel, at full weight into the subwoofer
    private val SL = floatArrayOf(-1.0f * G, 0.56f * G)     // surrounds a touch louder: you're in it
    private val SR = floatArrayOf(-0.56f * G, 1.0f * G)
    private fun centre(boost: Float) = floatArrayOf(0.707f * G * boost, 0.707f * G * boost)

    /** Each input channel as (to left, to right), in Android's order: FL FR FC LFE BL BR SL SR. */
    private fun rows(n: Int, dlg: Float): Array<FloatArray> = when (n) {
        1 -> arrayOf(floatArrayOf(0.7f, 0.7f))
        2 -> arrayOf(floatArrayOf(0.8f, 0f), floatArrayOf(0f, 0.8f))
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

    fun mixer(): AudioProcessor = Mixer(surround = true)

    /** A plain stereo downmix (no surround effect) - for Decode on TV / Stereo Only, so a 2-channel link (HDMI ARC,
     *  optical) never gets surround PCM it can't carry (that left the soundbar silent). */
    fun stereo(): AudioProcessor = Mixer(surround = false)

    // Plain downmix: rears to their own side (ITU-style), no opposite-phase blend.
    private val PSL = floatArrayOf(0.707f * G, 0f)
    private val PSR = floatArrayOf(0f, 0.707f * G)
    private fun plain(n: Int, dlg: Float): Array<FloatArray> = rows(n, dlg).mapIndexed { i, r ->
        when {
            n >= 4 && r === SL -> PSL
            n >= 4 && r === SR -> PSR
            r[0] < 0f -> if (i % 2 == 0) PSL else PSR
            else -> r
        }
    }.toTypedArray()

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
        fun lowPass(fs: Int, f: Double, q: Double = 0.7071) {
            val w = 2 * Math.PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }
        fun highPass(fs: Int, f: Double, q: Double = 0.7071) {
            val w = 2 * Math.PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }
        fun highShelf(fs: Int, f: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * Math.PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val r = 2 * sqrt(a) * al
            set(a * ((a + 1) + (a - 1) * c + r), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - r),
                (a + 1) - (a - 1) * c + r, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - r)
        }
    }

    private class Mixer(private val surround: Boolean) : BaseAudioProcessor() {
        private var n = 2
        private var fs = 48_000
        private var applied: SoundProfile.Settings? = null
        private var coeffs: Array<FloatArray> = emptyArray()
        // The middle of the mix (voices) and the sides (width) are shaped separately.
        private val mid = Array(13) { Biquad() }
        private val side = Array(5) { Biquad() }
        private var env = 0f
        private var envLow = 0f
        private var atk = 0f; private var rel = 0f
        // Rear channels arrive ~12 ms late and a touch softer on top - heard as around and behind, not from the bar.
        private var rear = BooleanArray(0)
        private var rearBuf: Array<FloatArray> = emptyArray()
        private var rearLp = FloatArray(0)
        private var rearPos = 0
        private var rearA = 0f
        // Rear-channel bass kept in phase on both sides: in opposite phase it cancelled itself in the subwoofer (thin sound).
        private var rearLow = FloatArray(0)
        // Dynamic EQ (after Audyssey): ears lose bass at lower levels, and TV/streaming is mixed well below film level -
        // so quieter scenes get up to +5 dB of deep bass, easing to none in loud ones.
        private var ms = 0f; private var msA = 0f
        private var dynGain = 0f; private var dynApplied = -99f; private var dynCount = 0
        private var lowA = 0f
        // Lookahead limiter: sees peaks 4 ms early and eases the level down before they arrive (no crackle, no dulling).
        private var la = 1
        private var limL = FloatArray(1); private var limR = FloatArray(1); private var limPos = 0
        private var hold = 0f; private var gain = 1f
        // Front widening (crosstalk cancellation, as 3D virtualisers do it): the side signal feeds back a little, delayed by
        // the time sound takes between your ears and softened as your head would - cancelling what reaches the "wrong"
        // ear, so sound seems to come from well beyond the ends of the soundbar. Only the side (not the voices).
        private var xtcBuf = FloatArray(1); private var xtcPos = 0; private var xtcLp = 0f; private var xtcA = 0f
        private var sideLow = 0f; private var sideLowA = 0f
        private val xtcTop = Biquad()   // gives the widened part its sparkle back (the cancellation tilts it dull)
        private var appliedTv: Boolean? = null
        // Virtual bass (TV speakers): the notes a small speaker can't play (40-100 Hz) are turned into their harmonics
        // (100-400 Hz), which it can - your ear fills in the missing low note (the "missing fundamental").
        private val vbHp = Biquad(); private val vbLp = Biquad(); private val vbOutHp = Biquad(); private val vbOutLp = Biquad()
        private var vbEnv = 0f; private var vbAtk = 0f; private var vbRel = 0f
        // Rears made slightly different left to right (all-pass filters), so they feel diffuse and around you.
        private var apX = FloatArray(0); private var apY = FloatArray(0)
        // Two bands at the end (Linkwitz-Riley at 120 Hz): the bass gets its own limiter, so a kick drum or a score hit
        // only turns the bass down - never the voices and detail above it (that ducking was the "blanket over it").
        private val xoL = arrayOf(Biquad(), Biquad()); private val xoR = arrayOf(Biquad(), Biquad())
        private val xhL = arrayOf(Biquad(), Biquad()); private val xhR = arrayOf(Biquad(), Biquad())
        private var lowEnv = 0f; private var lowGain = 1f
        private var lowAtk = 0f; private var lowRel = 0f; private var lowGainAtk = 0f; private var lowGainRel = 0f
        private var holdRel = 0f; private var gainAtk = 0f; private var gainRel = 0f

        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (f.encoding != C.ENCODING_PCM_16BIT || f.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
            n = f.channelCount; fs = f.sampleRate
            applied = null
            atk = exp(-1.0 / (0.020 * fs)).toFloat(); rel = exp(-1.0 / (0.25 * fs)).toFloat()
            rearBuf = Array(n) { FloatArray((0.015 * fs).toInt().coerceAtLeast(1)) }; rearLp = FloatArray(n); rearPos = 0
            // ~70 µs: the extra distance from one end of a soundbar to your far ear (speakers close together). A longer
            // delay made a comb of dips through the voice range - hollow and echoey.
            xtcBuf = FloatArray(Math.round(0.00007 * fs).toInt().coerceAtLeast(1)); xtcPos = 0; xtcLp = 0f
            xtcA = (1 - exp(-2 * Math.PI * 6000.0 / fs)).toFloat()
            xtcTop.highShelf(fs, 2500.0, 5.0)
            vbHp.highPass(fs, 40.0); vbLp.lowPass(fs, 100.0); vbOutHp.highPass(fs, 110.0); vbOutLp.lowPass(fs, 400.0)
            vbAtk = exp(-1.0 / (0.005 * fs)).toFloat(); vbRel = exp(-1.0 / (0.06 * fs)).toFloat(); vbEnv = 0f
            appliedTv = null
            sideLowA = (1 - exp(-2 * Math.PI * 250.0 / fs)).toFloat(); sideLow = 0f
            apX = FloatArray(n); apY = FloatArray(n)
            rearA = (1 - exp(-2 * Math.PI * 11_000.0 / fs)).toFloat()   // rears keep their detail
            rearLow = FloatArray(n); lowA = (1 - exp(-2 * Math.PI * 180.0 / fs)).toFloat()
            msA = (1 - exp(-1.0 / (0.4 * fs))).toFloat(); dynApplied = -99f
            la = (0.004 * fs).toInt().coerceAtLeast(1); limL = FloatArray(la); limR = FloatArray(la); limPos = 0
            holdRel = exp(-1.0 / (0.08 * fs)).toFloat()
            gainAtk = (1 - exp(-1.0 / (0.0015 * fs))).toFloat(); gainRel = (1 - exp(-1.0 / (0.2 * fs))).toFloat()
            for (b in xoL + xoR) b.lowPass(fs, 120.0)
            for (b in xhL + xhR) b.highPass(fs, 120.0)
            lowAtk = exp(-1.0 / (0.002 * fs)).toFloat(); lowRel = exp(-1.0 / (0.12 * fs)).toFloat()
            lowGainAtk = (1 - exp(-1.0 / (0.008 * fs))).toFloat(); lowGainRel = (1 - exp(-1.0 / (0.15 * fs))).toFloat()
            return AudioProcessor.AudioFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }

        private var framesSinceStart = 0L
        private var heard = false

        override fun onFlush() {
            framesSinceStart = 0L; heard = false
            mid.forEach { it.clear() }; side.forEach { it.clear() }; env = 0f; envLow = 0f
            rearBuf.forEach { it.fill(0f) }; rearLp.fill(0f); rearLow.fill(0f); limL.fill(0f); limR.fill(0f); hold = 0f; gain = 1f
            (xoL + xoR + xhL + xhR).forEach { it.clear() }; lowEnv = 0f; lowGain = 1f
            xtcBuf.fill(0f); xtcLp = 0f; sideLow = 0f; apX.fill(0f); apY.fill(0f); xtcTop.clear()
            listOf(vbHp, vbLp, vbOutHp, vbOutLp).forEach { it.clear() }; vbEnv = 0f
        }

        private fun tune(s: SoundProfile.Settings) {
            applied = s
            appliedTv = tvSpeakers
            val dlg = if (!s.dialogue) 0.0 else if (s.more) 8.0 else 5.0
            val boost = 10f.pow((dlg * 0.8 / 20).toFloat())
            coeffs = if (surround) rows(n, boost) else plain(n, boost)
            rear = BooleanArray(n) { surround && coeffs[it][0] < 0f }
            // Stereo has no centre channel: the voice band in the middle of the mix gets the whole lift.
            val voice = (if (n <= 2) dlg else dlg * 0.4) + s.clarity
            mid[0].lowShelf(fs, 150.0, s.bass.toDouble() - (if (s.dialogue) 1.5 else 0.0))
            mid[1].peak(fs, 600.0, -(dlg * 0.4) - s.clarity * 0.3, 1.0)
            mid[2].peak(fs, 3000.0, voice, 0.9)
            mid[3].highShelf(fs, 8000.0, s.treble * 1.0 + s.clarity * 0.3)
            side[0].lowShelf(fs, 150.0, s.bass.toDouble())
            side[1].peak(fs, 600.0, -(dlg * 0.2), 1.0)
            side[2].highShelf(fs, 8000.0, s.treble.toDouble())
            // Cinema voicing (Virtual Surround), after Harman's in-room preference research: a bass shelf, less
            // boxiness, and only a small lift on top (a big treble tilt either way sounds dull or harsh).
            if (surround) {
                // Rich and boomy: a deeper bass shelf, a low "boom" bump where film scores and kick drums live (55 Hz),
                // warmth at 180 Hz (body in strings and voices), and the boxy band moved up out of its way (400 Hz).
                // The boom stays; the low-mid "blanket" goes: a cut where mud builds up (250 Hz) and the boxy band (400 Hz),
                // with the top lifted to match the bigger bass, so detail and sparkle sit above it (a "smile", not a hump).
                mid[4].lowShelf(fs, 85.0, 5.5); mid[5].peak(fs, 400.0, -1.5, 0.9); mid[6].highShelf(fs, 8000.0, 3.0)
                mid[10].peak(fs, 60.0, 2.0, 1.0); mid[11].peak(fs, 250.0, -2.0, 1.0)
                // Below ~28 Hz the soundbar's subwoofer can't play it - boosting it only used up loudness. Filtered out.
                mid[12].highPass(fs, 28.0)
                if (tvSpeakers) {
                    // TV speakers can't play deep bass: the punch goes where they can (130 Hz), nothing below ~65 Hz is
                    // sent (it only makes them strain and distort), and virtual bass supplies the low notes' weight.
                    mid[4].peak(fs, 130.0, 3.0, 0.8); mid[10].peak(fs, 1000.0, 0.0, 1.0); mid[12].highPass(fs, 65.0)
                }
                // Vocal presence, the way soundbars' "voice" modes do it: a lift where voices get their bite (2.8 kHz)
                // and where consonants get crisp (5.5 kHz) - in the middle of the mix only, so effects stay wide and soft.
                mid[8].peak(fs, 2800.0, 2.0, 1.1); mid[9].peak(fs, 5500.0, 1.2, 1.4)
                side[3].highShelf(fs, 7000.0, 2.0)            // air and sparkle out at the sides (music, ambience)
                side[4].peak(fs, 3000.0, 2.0, 1.0)              // and presence there too: instruments come forward, not just voices
            } else {
                mid[4].peak(fs, 1000.0, 0.0, 1.0); mid[5].peak(fs, 1000.0, 0.0, 1.0); mid[6].peak(fs, 1000.0, 0.0, 1.0)
                mid[8].peak(fs, 1000.0, 0.0, 1.0); mid[9].peak(fs, 1000.0, 0.0, 1.0)
                mid[10].peak(fs, 1000.0, 0.0, 1.0); mid[11].peak(fs, 1000.0, 0.0, 1.0)
                mid[12].highPass(fs, 20.0)
                side[3].peak(fs, 1000.0, 0.0, 1.0)
                side[4].peak(fs, 1000.0, 0.0, 1.0)
            }
        }

        override fun queueInput(input: ByteBuffer) {
            val s = settings
            if (s != applied || tvSpeakers != appliedTv) tune(s)
            val frames = input.remaining() / (2 * n)
            val out = replaceOutputBuffer(frames * 4)
            val m = coeffs
            val level = s.level
            val makeup = if (level) 1.8f else 1f              // +5 dB back after the compressor
            for (f in 0 until frames) {
                var l = 0f; var r = 0f
                for (ch in 0 until n) {
                    var x = input.getShort() / 32768f
                    if (!heard && abs(x) > 0.0005f) { heard = true; val ms = framesSinceStart * 1000 / fs; onFirstSound?.invoke(ms) }
                    if (rear[ch]) {
                        val buf = rearBuf[ch]; var d = buf[rearPos % buf.size]; buf[rearPos % buf.size] = x
                        // First-order all-pass, opposite settings on the two sides: same sound, different phase - diffuse.
                        val g = if (ch % 2 == 0) 0.55f else -0.55f
                        val y = -g * d + apX[ch] + g * apY[ch]; apX[ch] = d; apY[ch] = y; d = y
                        rearLp[ch] += rearA * (d - rearLp[ch])
                        // Lows in phase to both sides (for the subwoofer), the rest blended wide.
                        rearLow[ch] += lowA * (rearLp[ch] - rearLow[ch])
                        val lo = rearLow[ch]; val hi = rearLp[ch] - lo
                        l += hi * m[ch][0] + lo * 0.5f * G; r += hi * m[ch][1] + lo * 0.5f * G
                        continue
                    }
                    l += x * m[ch][0]; r += x * m[ch][1]
                }
                rearPos++
                framesSinceStart++
                if (rearPos > 1_000_000_000) rearPos = 0
                // Middle and sides (surround: the sides a little wider).
                var md = (l + r) * 0.5f; var sd = (l - r) * (if (surround) 0.66f else 0.5f)
                // Dynamic EQ: track how loud the programme is (~0.4 s), and set the deep-bass lift every ~10 ms.
                ms += msA * (md * md - ms)
                if (++dynCount >= 512) {
                    dynCount = 0
                    val db = 10 * log10(ms.coerceAtLeast(1e-9f))
                    val target = ((-15f - db) / 20f).coerceIn(0f, 1f) * 3.5f      // -35 dB or quieter: +3.5 dB; -15 dB or louder: 0
                    dynGain += (target - dynGain) * 0.15f
                    if (abs(dynGain - dynApplied) > 0.25f) { mid[7].lowShelf(fs, 60.0, dynGain.toDouble()); dynApplied = dynGain }
                }
                val mdRaw = md
                SoundEvents.feed(mdRaw, fs)
                for (b in mid) md = b.run(md)
                if (tvSpeakers && surround) {
                    val low = vbLp.run(vbHp.run(mdRaw))
                    val a = abs(low)
                    vbEnv = if (a > vbEnv) vbAtk * vbEnv + (1 - vbAtk) * a else vbRel * vbEnv + (1 - vbRel) * a
                    // x·|x| makes the harmonics; dividing by the level keeps it as loud as the bass it stands in for.
                    val h = low * a / (vbEnv + 1e-4f)
                    md += vbOutLp.run(vbOutHp.run(h)) * 0.9f
                }
                for (b in side) sd = b.run(sd)
                if (surround) {
                    // Widening only above ~250 Hz (bass stays centred and solid for the subwoofer).
                    sideLow += sideLowA * (sd - sideLow)
                    val hi = sd - sideLow
                    val fb = xtcBuf[xtcPos]
                    xtcLp += xtcA * (fb - xtcLp)
                    val wide = hi + 0.6f * xtcLp
                    xtcBuf[xtcPos] = wide; xtcPos = (xtcPos + 1) % xtcBuf.size
                    // Net: about +5 dB of width through the lows and mids, smooth and flat through the voice range.
                    sd = sideLow + xtcTop.run(wide) * 0.75f
                }
                l = md + sd; r = md - sd
                // Split at 120 Hz with a Linkwitz-Riley crossover (as in speaker crossovers): the two bands add back flat.
                var lowL = xoL[1].run(xoL[0].run(l)); var lowR = xoR[1].run(xoR[0].run(r))
                val highL = xhL[1].run(xhL[0].run(l)); val highR = xhR[1].run(xhR[0].run(r))
                // Reduce Loud Sounds: 2.5:1 above -22 dB. A 20 ms attack lets the start of each sound through (the punch
                // of a consonant, a door, a drum) - the limiter below still catches real peaks. It judges loudness the way
                // the bass separately, so the boom doesn't turn the voices down.
                if (level) {
                    // Two compressors, one per band: the bass never turns the voices down, and the voices never the bass.
                    val peak = maxOf(abs(highL), abs(highR))
                    env = if (peak > env) atk * env + (1 - atk) * peak else rel * env + (1 - rel) * peak
                    val db = 20 * log10(env.coerceAtLeast(1e-6f))
                    val g = if (db > -22f) 10f.pow(-(db + 22f) * (1f - 1f / 2.5f) / 20f) else 1f
                    val lpk = maxOf(abs(lowL), abs(lowR))
                    envLow = if (lpk > envLow) atk * envLow + (1 - atk) * lpk else rel * envLow + (1 - rel) * lpk
                    val dbl = 20 * log10(envLow.coerceAtLeast(1e-6f))
                    val gl = if (dbl > -16f) 10f.pow(-(dbl + 16f) * (1f - 1f / 2f) / 20f) else 1f   // gentler on the bass: it keeps its punch
                    lowL *= gl * makeup; lowR *= gl * makeup
                    l = highL * g * makeup; r = highR * g * makeup
                } else { l = highL; r = highR }
                // The bass band's own limiter: keeps the boom under -4.4 dBFS, so it never pushes the whole mix down.
                val lp = maxOf(abs(lowL), abs(lowR))
                lowEnv = if (lp > lowEnv) lowAtk * lowEnv + (1 - lowAtk) * lp else lowRel * lowEnv + (1 - lowRel) * lp
                val lowTarget = if (lowEnv > 0.6f) 0.6f / lowEnv else 1f
                lowGain += (lowTarget - lowGain) * (if (lowTarget < lowGain) lowGainAtk else lowGainRel)
                l += lowL * lowGain; r += lowR * lowGain
                // Lookahead limiter, ceiling -0.7 dB.
                val pk = maxOf(abs(l), abs(r))
                hold = if (pk > hold) pk else hold * holdRel
                val target = if (hold > 0.92f) 0.92f / hold else 1f
                gain += (target - gain) * (if (target < gain) gainAtk else gainRel)
                val dl = limL[limPos]; val dr = limR[limPos]
                limL[limPos] = l; limR[limPos] = r
                limPos = (limPos + 1) % la
                out.putShort(clip(dl * gain)); out.putShort(clip(dr * gain))
            }
            input.position(input.limit())
            out.flip()
        }

        private fun clip(x: Float): Short = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
