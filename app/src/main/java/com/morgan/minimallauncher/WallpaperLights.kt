package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.max

/**
 * On the home screen and screensaver, the room slowly takes on the colours of the photo that's showing - a lake at
 * dusk turns the room soft blue, a desert sunset warm amber - gliding over a few seconds with each new picture.
 * Only when TV Colour Lights is on (and Wallpaper Lights isn't turned off in Settings).
 */
class WallpaperLights(private val c: Context) {
    private var link: MagicHue.Link? = null
    private val cur = floatArrayOf(-1f, 0f, 0f)
    @Volatile private var target: IntArray? = null
    @Volatile private var gen = 0

    fun enabled() = MagicHue.syncOn(c) && Prefs.bool(c, "wallpaper_lights", true) && MagicHue.allowed(c)

    /** A new photo is showing (or home came back): glide to its colour. */
    fun show(bmp: Bitmap?) {
        if (bmp == null || !enabled()) return
        val ip = MagicHue.ip(c) ?: return
        val my = ++gen
        Bg.run {
            val col = try { ColourEngine.dominant(bmp) } catch (e: Exception) { return@run }
            // Calm and dim enough to sit behind a home screen, through the same maths as the film lights.
            val t = ColourEngine.toLed(col, 0.5f)
            val l = synchronized(this) { if (my != gen) return@run; link ?: MagicHue.Link(ip).also { it.begin(); link = it } }
            target = t
            if (cur[0] < 0) { l.before?.third?.let { b -> cur[0] = b[0].toFloat(); cur[1] = b[1].toFloat(); cur[2] = b[2].toFloat() } ?: run { cur[0] = t[0].toFloat(); cur[1] = t[1].toFloat(); cur[2] = t[2].toFloat() } }
            // About 3 seconds of glide, 25 steps a second.
            for (i in 0 until 75) {
                if (my != gen) return@run
                for (k in 0..2) cur[k] += (t[k] - cur[k]) * 0.07f
                l.colour(cur[0].toInt(), cur[1].toInt(), cur[2].toInt())
                Thread.sleep(40)
            }
        }
    }

    /** Leaving home: stop gliding and let go of the lights (a film's colours take over from here). */
    fun leave() = synchronized(this) { gen++; link?.hangUp(); link = null; cur[0] = -1f }
}
