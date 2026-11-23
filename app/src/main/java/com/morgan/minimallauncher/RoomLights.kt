package com.morgan.minimallauncher

import android.content.Context
import org.json.JSONObject

/**
 * Lights Down, for Night Mode and Cinema Mode: the Magic Hue lights switch off and the Govee strip behind the TV
 * drops to its lowest glow. They come back as they were only when neither mode is on any more.
 * (Settings › Cinema Mode › Lights Down turns this off.)
 */
object RoomLights {
    private const val DOWN = "lights_down"
    private const val GOVEE = "lights_down_govee"

    fun isDown(c: Context) = Prefs.bool(c, DOWN, false)

    /** Night Mode or Cinema Mode came on. */
    fun down(c: Context) {
        val app = c.applicationContext
        if (!Prefs.cinemaLights(app) || isDown(app)) return
        Prefs.setBool(app, DOWN, true)
        MagicHue.switchOff(app)
        GoveeHelper.listDevices(app) { devices, _ ->
            val on = devices?.filter { it.isOn }.orEmpty()
            if (on.isEmpty()) return@listDevices
            Thread {
                val saved = JSONObject()
                for (d in on) {
                    saved.put(d.deviceId, GoveeHelper.readBrightness(app, d) ?: 100)
                    GoveeHelper.setBrightness(app, d, 2)          // as low as it goes and still on: just a glow
                }
                Prefs.setString(app, GOVEE, saved.toString())
            }.start()
        }
    }

    /** Night Mode or Cinema Mode went off: back up, unless the other one is still on. */
    fun up(c: Context) {
        val app = c.applicationContext
        if (!isDown(app) || Prefs.cinemaOn(app) || NightDim.isOn(app)) return
        Prefs.setBool(app, DOWN, false)
        MagicHue.switchBackOn(app)
        val saved = try { JSONObject(Prefs.string(app, GOVEE) ?: return) } catch (e: Exception) { return }
        Prefs.setString(app, GOVEE, null)
        GoveeHelper.listDevices(app) { devices, _ ->
            val back = devices?.filter { saved.has(it.deviceId) }.orEmpty()
            Thread { for (d in back) GoveeHelper.setBrightness(app, d, saved.optInt(d.deviceId, 100)) }.start()
        }
    }
}
