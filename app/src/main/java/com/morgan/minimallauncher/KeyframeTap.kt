package com.morgan.minimallauncher

import java.nio.ByteBuffer

/**
 * Tells the lights when each new shot starts. The encoder puts a keyframe (a complete picture) at every scene cut;
 * this sees each one as it goes into the TV's decoder, so the lights can read the screen ONCE per shot, right as it
 * appears - instead of several times a second, which cost 10-bit films dropped frames on this Chromecast.
 * (Decoding the keyframes ourselves was tried: the TV's software decoder can't do 10-bit HEVC.)
 */
class KeyframeTap(private val onShot: (Long) -> Unit) {
    /** On only for video read once per shot (720p 10-bit). */
    @Volatile var enabled = false
    private var lastUs = Long.MIN_VALUE

    fun reset() { lastUs = Long.MIN_VALUE }

    /** From the video renderer, for every sample going into the decoder. */
    fun onSample(isKey: Boolean, @Suppress("UNUSED_PARAMETER") data: ByteBuffer?, timeUs: Long) {
        if (!enabled || !isKey) return
        // Keyframes closer than a second apart (fixed-interval encodes) don't each make a new shot.
        if (timeUs - lastUs in 0..999_999) return
        lastUs = timeUs
        onShot(timeUs)
    }
}
