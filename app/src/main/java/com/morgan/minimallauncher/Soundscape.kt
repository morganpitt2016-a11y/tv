package com.morgan.minimallauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Ambient sound for the screensaver and sleep clock - rain on the window, a crackling fire, waves at dusk - made
 * live on the TV from noise (no recordings, nothing downloaded, never the same twice), with the lights doing the same
 * thing: soft blue for rain, a flickering amber that jumps with each crackle, teal that swells with each wave.
 * Fades in over a few seconds and out when the screensaver closes.
 */
object Soundscape {
    val KINDS = listOf("off" to "Off", "rain" to "Rain", "fire" to "Fireplace", "waves" to "Waves")
    fun kind(c: Context) = Prefs.string(c, "soundscape") ?: "off"
    fun title(c: Context) = KINDS.firstOrNull { it.first == kind(c) }?.second ?: "Off"

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun start(c: Context) {
        val k = kind(c)
        if (k == "off" || running) return
        running = true
        val ip = if (MagicHue.syncOn(c) && MagicHue.allowed(c)) MagicHue.ip(c) else null
        thread = Thread({ try { play(k, ip) } catch (_: Exception) {} finally { if (thread === Thread.currentThread()) running = false } }, "soundscape").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    private fun play(k: String, ip: String?) {
        val fs = 44100
        val min = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(min, fs)).setTransferMode(AudioTrack.MODE_STREAM).build()
        val link = ip?.let { MagicHue.Link(it).also { l -> l.begin() } }
        track.play()
        val rnd = java.util.Random()
        val frames = 1024
        val buf = ShortArray(frames * 2)
        // Pink noise (Paul Kellet's filter), brown noise, and a few one-pole filters per side.
        val p = Array(2) { FloatArray(7) }
        val brown = FloatArray(2); val lp = FloatArray(2); val lp2 = FloatArray(2)
        var fade = 0f
        var t = 0L
        // Raindrops / crackles: a short decaying burst somewhere left-to-right.
        var evAmp = 0f; var evDecay = 0f; var evPhase = 0.0; var evFreq = 0.0; var evPan = 0.5f; var evEnergy = 0f
        var flicker = 0.5f
        var waveEnv = 0f
        var nextWave = 0.0; var wavePeriod = 9.0
        var blocks = 0
        while (true) {
            val stopping = !running
            for (i in 0 until frames) {
                fade = if (stopping) (fade - 1f / (fs * 2.5f)).coerceAtLeast(0f) else (fade + 1f / (fs * 4f)).coerceAtMost(1f)
                val sec = t.toDouble() / fs
                var l = 0f; var r = 0f
                for (ch in 0..1) {
                    val w = rnd.nextFloat() * 2f - 1f
                    val b = p[ch]
                    b[0] = 0.99886f * b[0] + w * 0.0555179f; b[1] = 0.99332f * b[1] + w * 0.0750759f
                    b[2] = 0.96900f * b[2] + w * 0.1538520f; b[3] = 0.86650f * b[3] + w * 0.3104856f
                    b[4] = 0.55000f * b[4] + w * 0.5329522f; b[5] = -0.7616f * b[5] - w * 0.0168980f
                    val pink = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362f) * 0.11f
                    b[6] = w * 0.115926f
                    brown[ch] = (brown[ch] + w * 0.02f) * 0.997f
                    val s = when (k) {
                        "rain" -> { lp[ch] += 0.35f * (pink - lp[ch]); lp[ch] * 0.55f }
                        "fire" -> { lp[ch] += 0.08f * (brown[ch] * 3f - lp[ch]); lp[ch] * 0.9f + pink * 0.04f }
                        else -> {
                            // The wave's body: noise whose brightness and loudness rise and fall with the swell.
                            val cut = 0.03f + 0.25f * waveEnv
                            lp[ch] += cut * (pink - lp[ch]); lp2[ch] += cut * (lp[ch] - lp2[ch])
                            lp2[ch] * (0.08f + 1.1f * waveEnv)
                        }
                    }
                    if (ch == 0) l = s else r = s
                }
                // Events: raindrops tick, the fire cracks and pops.
                val rate = when (k) { "rain" -> 0.0009f; "fire" -> 0.00012f; else -> 0f }
                if (rate > 0f && rnd.nextFloat() < rate) {
                    evAmp = if (k == "rain") 0.05f + rnd.nextFloat() * 0.1f else 0.15f + rnd.nextFloat() * 0.45f
                    evDecay = exp(-1f / (fs * (if (k == "rain") 0.012f else 0.004f)))
                    evFreq = if (k == "rain") 2500.0 + rnd.nextDouble() * 3500 else 0.0
                    evPan = rnd.nextFloat(); evPhase = 0.0
                    evEnergy = max(evEnergy, evAmp)
                }
                if (evAmp > 0.0005f) {
                    val e = if (evFreq > 0) (sin(evPhase) * evAmp).toFloat() else (rnd.nextFloat() * 2f - 1f) * evAmp
                    evPhase += 2 * PI * evFreq / fs
                    l += e * (1f - evPan); r += e * evPan
                    evAmp *= evDecay
                }
                if (k == "waves") {
                    // A new wave every 7-13 s: it builds, breaks, and drains away.
                    if (sec >= nextWave) { wavePeriod = 7.0 + rnd.nextDouble() * 6; nextWave = sec + wavePeriod }
                    val ph = 1.0 - (nextWave - sec) / wavePeriod
                    val shape = (0.5 - 0.5 * cos(2 * PI * ph)).let { it * it }.toFloat()
                    waveEnv += 0.0002f * (shape - waveEnv)
                }
                buf[2 * i] = (l * fade * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                buf[2 * i + 1] = (r * fade * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                t++
            }
            track.write(buf, 0, buf.size)
            // The lights, about 14 times a second.
            if (link != null && ++blocks % 3 == 0) {
                val sec = t.toDouble() / fs
                val c = when (k) {
                    "rain" -> { val lv = 0.22f + 0.05f * sin(sec * 2 * PI / 11).toFloat(); floatArrayOf(60f * lv, 95f * lv, 230f * lv) }
                    "fire" -> {
                        flicker += 0.3f * (rnd.nextFloat() - flicker) + evEnergy * 0.8f
                        flicker = flicker.coerceIn(0f, 1.2f)
                        val lv = 0.25f + 0.3f * flicker
                        floatArrayOf(255f * lv, (95f + 40f * flicker) * lv, 15f * lv)
                    }
                    else -> { val lv = 0.08f + 0.5f * waveEnv; floatArrayOf(25f * lv, 150f * lv, 185f * lv) }
                }
                evEnergy *= 0.4f
                link.colour((c[0] * fade).toInt().coerceIn(0, 255), (c[1] * 0.86f * fade).toInt().coerceIn(0, 255), (c[2] * 0.7f * fade).toInt().coerceIn(0, 255))
            }
            if (stopping && fade <= 0f) break
            if (!stopping && thread !== Thread.currentThread()) break
        }
        track.stop(); track.release()
        // Replaced by a newer soundscape: let go without putting the lights back (the new one is using them).
        if (thread === Thread.currentThread()) link?.end() else link?.hangUp()
    }
}
