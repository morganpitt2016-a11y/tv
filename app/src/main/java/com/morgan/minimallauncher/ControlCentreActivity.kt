package com.morgan.minimallauncher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The Control Centre over whatever is on screen (hold Home). Closing it returns to what you were doing. */
class ControlCentreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickSettings(this, object : QuickSettings.Host {
            override fun isNightMode() = NightDim.isOn(this@ControlCentreActivity)
            override fun setNightMode(on: Boolean) = NightDim.set(this@ControlCentreActivity, on)
            override fun nextWallpaper() { GlassUi.toast(this@ControlCentreActivity, "Change the wallpaper from the home screen") }
            override fun lights() = emptyList<GoveeHelper.Device>()
            override fun toggleLight(device: GoveeHelper.Device, done: () -> Unit) = done()
        }).apply { onClose = { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) } }.show()
    }
}
