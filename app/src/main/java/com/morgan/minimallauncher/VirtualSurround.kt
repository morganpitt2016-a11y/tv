package com.morgan.minimallauncher

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Virtual Surround: surround sound (5.1, 7.1, Dolby once decoded) mixed into surround-encoded stereo, for a
 * soundbar on an optical cable (which can only carry two channels of decoded sound).
 *
 * Follows Dolby Pro Logic II encoding: voices (centre) up the middle, the bass channel into both sides, and each
 * rear channel into both sides in opposite phase - heard on plain stereo as width beyond the speakers, and pulled
 * back out as surround by a soundbar's Surround / Movie mode. Stereo and mono pass through untouched.
 */
object VirtualSurround {
    private const val G = 0.55f           // headroom so the mix doesn't clip
    private val FL = floatArrayOf(G, 0f)
    private val FR = floatArrayOf(0f, G)
    private val FC = floatArrayOf(0.707f * G, 0.707f * G)
    private val LFE = floatArrayOf(0.5f * G, 0.5f * G)
    private val SL = floatArrayOf(-0.87f * G, 0.49f * G)
    private val SR = floatArrayOf(-0.49f * G, 0.87f * G)

    /** Each input channel as (to left, to right), in Android's order: FL FR FC LFE BL BR SL SR. */
    private fun rows(n: Int): Array<FloatArray> = when (n) {
        3 -> arrayOf(FL, FR, FC)
        4 -> arrayOf(FL, FR, SL, SR)
        5 -> arrayOf(FL, FR, FC, SL, SR)
        6 -> arrayOf(FL, FR, FC, LFE, SL, SR)
        7 -> arrayOf(FL, FR, FC, LFE, floatArrayOf(-0.6f * G, 0.6f * G), SL, SR)
        8 -> arrayOf(FL, FR, FC, LFE,
            floatArrayOf(-0.7f * G, 0.39f * G), floatArrayOf(-0.39f * G, 0.7f * G),
            floatArrayOf(-0.6f * G, 0.34f * G), floatArrayOf(-0.34f * G, 0.6f * G))
        else -> Array(n) { i -> when (i) { 0 -> FL; 1 -> FR; else -> floatArrayOf(0.3f * G, 0.3f * G) } }
    }

    fun mixer(): AudioProcessor = Mixer()

    private class Mixer : BaseAudioProcessor() {
        private var coeffs: Array<FloatArray> = emptyArray()

        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            // Only 16-bit surround is mixed; anything else goes through as it is.
            if (f.encoding != C.ENCODING_PCM_16BIT || f.channelCount <= 2) return AudioProcessor.AudioFormat.NOT_SET
            coeffs = rows(f.channelCount)
            return AudioProcessor.AudioFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }

        override fun queueInput(input: ByteBuffer) {
            val n = inputAudioFormat.channelCount
            val frames = input.remaining() / (2 * n)
            val out = replaceOutputBuffer(frames * 4)
            val m = coeffs
            for (f in 0 until frames) {
                var l = 0f; var r = 0f
                for (ch in 0 until n) {
                    val s = input.getShort().toFloat()
                    l += s * m[ch][0]; r += s * m[ch][1]
                }
                out.putShort(l.coerceIn(-32768f, 32767f).toInt().toShort())
                out.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
            }
            input.position(input.limit())
            out.flip()
        }
    }
}
