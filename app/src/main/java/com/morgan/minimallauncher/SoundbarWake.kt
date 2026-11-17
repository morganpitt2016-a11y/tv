package com.morgan.minimallauncher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Wakes the soundbar before the sound starts. An idle HDMI/optical link makes the soundbar re-detect the format
 * when a programme begins (it shows its mode), and it swallows the first second or two of sound while it does.
 * So while the stream is still loading we send inaudible stereo PCM (the same kind of sound the mixer sends):
 * the soundbar has locked on by the time the real sound arrives, and nothing is lost.
 */
object SoundbarWake {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
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
                val until = System.currentTimeMillis() + 20_000           // never longer than 20 s
                while (running && System.currentTimeMillis() < until) track.write(buf, 0, buf.size)
            } catch (_: Throwable) {
            } finally {
                try { track.stop(); track.release() } catch (_: Throwable) {}
                running = false
            }
        }.apply { name = "soundbar-wake"; isDaemon = true; start() }
    }

    fun stop() { running = false }
}
