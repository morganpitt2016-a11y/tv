package com.morgan.minimallauncher

import android.service.dreams.DreamService

/**
 * The Aerial display as a system screensaver, for TVs that let you pick
 * one (Settings › System › Ambient mode / Screen saver).
 */
class AerialDream : DreamService() {
    private var screen: AmbientScreen? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val s = AmbientScreen(this, AmbientScreen.AERIAL)
        screen = s
        setContentView(s.view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screen?.start()
    }

    override fun onDreamingStopped() {
        screen?.stop()
        super.onDreamingStopped()
    }
}
