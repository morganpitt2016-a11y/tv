package com.morgan.minimallauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import android.view.View
import android.view.Window

/**
 * Subtle tvOS-style navigation sounds: a soft tick when focus moves, a
 * "tock" on select and a lower one on Back. Can be turned off in Settings.
 */
object UiSounds {
    private var pool: SoundPool? = null
    private var move = 0
    private var select = 0
    private var back = 0
    private var lastMove = 0L

    private fun ensure(c: Context): SoundPool {
        pool?.let { return it }
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        val app = c.applicationContext
        move = p.load(app, R.raw.ui_move, 1)
        select = p.load(app, R.raw.ui_select, 1)
        back = p.load(app, R.raw.ui_back, 1)
        pool = p
        return p
    }

    private fun play(c: Context, id: () -> Int, volume: Float) {
        if (!Prefs.sounds(c)) return
        val p = ensure(c)
        p.play(id(), volume, volume, 1, 0, 1f)
    }

    fun move(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastMove < 70) return // holding a direction: don't machine-gun
        lastMove = now
        play(c, { move }, 0.22f)
    }

    fun select(c: Context) = play(c, { select }, 0.32f)
    fun back(c: Context) = play(c, { back }, 0.3f)

    /** Adds the sounds to an activity's window (call after setContentView). */
    fun attach(activity: Activity) {
        ensure(activity)
        hook(activity, activity.window)
    }

    fun attach(dialog: Dialog) {
        val w = dialog.window ?: return
        hook(dialog.context, w)
    }

    private fun hook(c: Context, window: Window) {
        val decor: View = window.decorView
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && new != null) move(c)
        }
        val original = window.callback ?: return
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A -> if (window.currentFocus?.isClickable == true) select(c)
                        KeyEvent.KEYCODE_BACK -> back(c)
                    }
                }
                return original.dispatchKeyEvent(event)
            }
        }
    }
}
