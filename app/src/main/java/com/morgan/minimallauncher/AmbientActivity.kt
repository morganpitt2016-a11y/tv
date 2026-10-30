package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the Ambient / Aerial / Sleep displays full screen. Any button
 * returns to the home screen (Left/Right change the picture instead).
 * Ambient and Aerial keep the screen on for the time set in Settings,
 * then fade to the sleep clock and let the TV sleep normally.
 */
class AmbientActivity : AppCompatActivity() {

    private lateinit var screen: AmbientScreen
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: AMBIENT
        screen = AmbientScreen(this, mode)
        setContentView(screen.view)
        screen.view.alpha = 0f
        screen.view.animate().alpha(1f).setDuration(900).start()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onResume() {
        super.onResume()
        screen.start()
        if (screen.mode != SLEEP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Prefs.ambientHours(this).takeIf { it > 0 }?.let { handler.postDelayed(toSleep, it * 3_600_000L) }
        }
    }

    override fun onPause() {
        super.onPause()
        screen.stop()
        handler.removeCallbacks(toSleep)
    }

    private val toSleep = Runnable {
        screen.switchTo(SLEEP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                screen.next(); return true
            }
        }
        screen.view.animate().alpha(0f).setDuration(350).withEndAction {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }.start()
        return true
    }

    companion object {
        const val AMBIENT = AmbientScreen.AMBIENT
        const val AERIAL = AmbientScreen.AERIAL
        const val SLEEP = AmbientScreen.SLEEP
        private const val EXTRA_MODE = "mode"

        fun start(c: Context, mode: String) {
            val i = Intent(c, AmbientActivity::class.java).putExtra(EXTRA_MODE, mode)
            if (c !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        }

        fun title(mode: String) = when (mode) {
            AERIAL -> "Aerial"
            SLEEP -> "Sleep Clock"
            else -> "Ambient"
        }
    }
}
