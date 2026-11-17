package com.morgan.minimallauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Keeps the soundbar awake while you're in Watch, the TV guide, a show's page or the player. An idle HDMI/optical
 * link makes the soundbar re-detect the format when sound starts (it shows its mode) and swallow the first few
 * seconds while it does - so as long as one of those screens is open we send inaudible stereo PCM (the same kind of
 * sound the mixer sends): the soundbar stays locked on, and sound starts the moment you press play.
 * Leaving those screens lets the link go quiet again (so the soundbar can still go to standby).
 */
object SoundbarWake {
    private val holders = HashSet<String>()
    @Volatile private var running = false

    /** Only when the TV sends PCM (Virtual Surround / Decode on TV / Stereo Only); Dolby to the soundbar would switch twice. */
    private fun wanted(c: Context) = (Prefs.string(c, "audio_format") ?: "auto") != "auto"

    @Synchronized fun hold(c: Context, who: String) {
        if (!wanted(c)) return
        holders += who
        if (!running) startThread()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Moving between screens (Watch → player) mustn't let the link drop: it only goes quiet after 3 s with nothing open. */
    @Synchronized fun release(who: String) {
        holders -= who
        handler.postDelayed({ synchronized(this) { if (holders.isEmpty()) running = false } }, 3000)
    }

    // Older calls (the player before it held its own place).
    fun start() {}
    fun stop() {}

    private fun startThread() {
        running = true
        Thread {
            val rate = 48_000
            val track = try {
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) { running = false; return@Thread }
            // Not quite digital zero (some soundbars treat pure zeros as "no signal"): ±1 step of 32768, far below hearing.
            val buf = ShortArray(2048) { if (it % 3 == 0) 1 else if (it % 3 == 1) -1 else 0 }
            try {
                track.play()
                while (running) track.write(buf, 0, buf.size)
            } catch (_: Throwable) {
            } finally {
                try { track.stop(); track.release() } catch (_: Throwable) {}
                running = false
            }
        }.apply { name = "soundbar-wake"; isDaemon = true; start() }
    }
}
