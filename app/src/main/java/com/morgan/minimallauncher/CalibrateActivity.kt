package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * Lights Calibration: the screen shows pure white and the lights show their white beside it. Left / Right make the
 * lights warmer or cooler, Up / Down move them greener or pinker, until the wall matches the screen; OK saves it.
 * (Like Philips' "wall colour" setting: the paint behind the TV and the strip itself both tint the light.)
 */
class CalibrateActivity : Activity() {
    private var link: MagicHue.Link? = null
    private val wb = ColourEngine.wb.copyOf()
    private lateinit var hint: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        hint = GlassUi.text(this, "", 15f, R.font.inter_medium, 0xFF1C1C1E.toInt()).apply { gravity = Gravity.CENTER }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (40 * resources.displayMetrics.density).toInt() })
        setContentView(root)
        val ip = MagicHue.ip(this)
        if (ip == null) { GlassUi.toast(this, "Find your lights first (Settings › Magic Hue Lights)"); finish(); return }
        link = MagicHue.Link(ip).also { it.begin() }
        show()
    }

    private fun show() {
        hint.text = "Make the lights' white match the screen's white\n◀ warmer   ▶ cooler   ▲ greener   ▼ pinker   ·   OK to save   ·   Back to cancel"
        val k = 0.7f * 255f
        link?.colour((k * wb[0]).toInt().coerceIn(0, 255), (k * wb[1]).toInt().coerceIn(0, 255), (k * wb[2]).toInt().coerceIn(0, 255))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { wb[2] -= 0.03f; wb[0] += 0.015f }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { wb[2] += 0.03f; wb[0] -= 0.015f }
            KeyEvent.KEYCODE_DPAD_UP -> wb[1] += 0.02f
            KeyEvent.KEYCODE_DPAD_DOWN -> wb[1] -= 0.02f
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                ColourEngine.saveWb(this, wb); GlassUi.toast(this, "Lights calibrated"); finish(); return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        // Keep the strongest colour at full so the white stays as bright as it can be.
        for (i in 0..2) wb[i] = wb[i].coerceIn(0.3f, 1.2f)
        val m = maxOf(wb[0], wb[1], wb[2]); for (i in 0..2) wb[i] /= m
        show()
        return true
    }

    override fun onDestroy() { link?.end(); link = null; super.onDestroy() }
}
