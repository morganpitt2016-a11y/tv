package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for Govee's official Developer API (developer.govee.com).
 *
 * Getting a key: in the Govee Home app, go to Settings -> About Us ->
 * Apply for API Key. Govee emails it, usually within minutes - no OAuth,
 * no Google Cloud project, just a free static key sent as a header.
 *
 * This targets Govee's long-standing v1 REST API
 * (developer-api.govee.com), the most widely documented and stable
 * surface (also what Home Assistant's own Govee integration uses). This
 * session's network couldn't reach developer.govee.com to re-verify the
 * docs against anything that may have changed since, so if Govee has
 * since moved everything to a newer v2 platform, only BASE_URL and the
 * two request shapes below need updating - storage, UI and toggle wiring
 * all stay the same.
 */
object GoveeHelper {
    private const val BASE_URL = "https://developer-api.govee.com/v1"
    private const val PREFS = "govee_prefs"
    private const val KEY_API_KEY = "api_key"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, key.trim())
            .apply()
    }

    data class Device(
        val deviceId: String,
        val model: String,
        val name: String,
        var isOn: Boolean = false
    )

    fun listDevices(context: Context, onResult: (devices: List<Device>?, error: String?) -> Unit) {
        val apiKey = getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onResult(null, "No Govee API key set yet")
            return
        }
        Thread {
            try {
                val conn = URL("$BASE_URL/devices").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Govee-API-Key", apiKey)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().readText()
                conn.disconnect()
                if (code !in 200..299) {
                    mainHandler.post { onResult(null, "Govee error $code: $body") }
                    return@Thread
                }
                val data = JSONObject(body).optJSONObject("data")
                val devicesJson = data?.optJSONArray("devices") ?: JSONArray()
                val devices = mutableListOf<Device>()
                for (i in 0 until devicesJson.length()) {
                    val d = devicesJson.getJSONObject(i)
                    devices.add(
                        Device(
                            deviceId = d.getString("device"),
                            model = d.getString("model"),
                            name = d.optString("deviceName", d.getString("model"))
                        )
                    )
                }
                mainHandler.post { onResult(devices, null) }
            } catch (e: Exception) {
                mainHandler.post { onResult(null, e.message ?: "Network error reaching Govee") }
            }
        }.start()
    }

    fun setPower(
        context: Context,
        device: Device,
        turnOn: Boolean,
        onResult: (success: Boolean, error: String?) -> Unit
    ) {
        val apiKey = getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onResult(false, "No Govee API key set yet")
            return
        }
        Thread {
            try {
                val conn = URL("$BASE_URL/devices/control").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setRequestProperty("Govee-API-Key", apiKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = JSONObject().apply {
                    put("device", device.deviceId)
                    put("model", device.model)
                    put("cmd", JSONObject().apply {
                        put("name", "turn")
                        put("value", if (turnOn) "on" else "off")
                    })
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().readText()
                conn.disconnect()
                if (code in 200..299) {
                    mainHandler.post { onResult(true, null) }
                } else {
                    mainHandler.post { onResult(false, "Govee error $code: $respBody") }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, e.message ?: "Network error reaching Govee") }
            }
        }.start()
    }
}
