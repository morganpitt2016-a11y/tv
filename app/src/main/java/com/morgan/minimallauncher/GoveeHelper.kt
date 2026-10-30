package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Govee Developer API client.
 *
 * Key: Govee Home app -> Profile -> Settings -> Apply for API Key (emailed).
 *
 * Uses Govee's current "router" API (openapi.api.govee.com), which lists
 * every device on the account - newer lights only appear there - and can
 * read each light's real on/off state. Falls back to the older
 * developer-api.govee.com/v1 API if the new one returns nothing.
 */
object GoveeHelper {
    private const val NEW_API = "https://openapi.api.govee.com/router/api/v1"
    private const val OLD_API = "https://developer-api.govee.com/v1"
    private const val PREFS = "govee_prefs"
    private const val KEY_API_KEY = "api_key"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    data class Device(
        val deviceId: String,
        val model: String,
        val name: String,
        /** 2 = current router API, 1 = old v1 API. */
        val api: Int,
        var isOn: Boolean = false,
    )

    private class HttpResult(val code: Int, val body: String)

    private fun request(method: String, url: String, key: String, body: JSONObject? = null): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Govee-API-Key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(r: HttpResult): String {
        val msg = try { JSONObject(r.body).optString("message", JSONObject(r.body).optString("msg")) } catch (e: Exception) { "" }
        return when (r.code) {
            401, 403 -> "Govee rejected the API key (check it was copied exactly)"
            429 -> "Govee rate limit reached - try again in a minute"
            else -> "Govee error ${r.code}${if (msg.isNotBlank()) ": $msg" else ""}"
        }
    }

    /** Lists devices that can be switched on and off, with their current state. */
    fun listDevices(context: Context, onResult: (devices: List<Device>?, error: String?) -> Unit) {
        val key = getApiKey(context)
        if (key == null) {
            onResult(null, "No Govee API key set yet")
            return
        }
        Thread {
            var error: String? = null
            var devices: List<Device> = emptyList()
            try {
                val r = request("GET", "$NEW_API/user/devices", key)
                if (r.code in 200..299) {
                    devices = parseNew(r.body)
                    devices.forEach { d -> d.isOn = readStateNew(key, d) ?: false }
                } else error = describe(r)
            } catch (e: Exception) {
                error = "Couldn't reach Govee (${e.javaClass.simpleName})"
            }
            if (devices.isEmpty()) {
                // Older accounts/devices may only be visible through the v1 API.
                try {
                    val r = request("GET", "$OLD_API/devices", key)
                    if (r.code in 200..299) devices = parseOld(r.body)
                    else if (error == null) error = describe(r)
                } catch (_: Exception) {}
            }
            val result = devices
            val err = if (result.isEmpty()) (error ?: "Connected, but Govee reported no controllable devices on this account") else null
            mainHandler.post { onResult(if (err == null) result else if (error == null) emptyList() else null, err) }
        }.start()
    }

    private fun parseNew(body: String): List<Device> {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<Device>()
        for (i in 0 until data.length()) {
            val d = data.getJSONObject(i)
            val caps = d.optJSONArray("capabilities") ?: JSONArray()
            val canSwitch = (0 until caps.length()).any { caps.getJSONObject(it).optString("instance") == "powerSwitch" }
            if (!canSwitch) continue
            out += Device(
                deviceId = d.getString("device"),
                model = d.getString("sku"),
                name = d.optString("deviceName").ifBlank { d.getString("sku") },
                api = 2,
            )
        }
        return out
    }

    private fun parseOld(body: String): List<Device> {
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Device(d.getString("device"), d.getString("model"), d.optString("deviceName").ifBlank { d.getString("model") }, api = 1)
        }
    }

    private fun readStateNew(key: String, d: Device): Boolean? = try {
        val body = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("payload", JSONObject().put("sku", d.model).put("device", d.deviceId))
        val r = request("POST", "$NEW_API/device/state", key, body)
        if (r.code !in 200..299) null else {
            val caps = JSONObject(r.body).optJSONObject("payload")?.optJSONArray("capabilities") ?: JSONArray()
            (0 until caps.length()).map { caps.getJSONObject(it) }
                .firstOrNull { it.optString("instance") == "powerSwitch" }
                ?.optJSONObject("state")?.optInt("value", 0)?.let { it == 1 }
        }
    } catch (e: Exception) {
        null
    }

    fun setPower(context: Context, device: Device, turnOn: Boolean, onResult: (success: Boolean, error: String?) -> Unit) {
        val key = getApiKey(context)
        if (key == null) {
            onResult(false, "No Govee API key set yet")
            return
        }
        Thread {
            val error: String? = try {
                val r = if (device.api == 2) {
                    request("POST", "$NEW_API/device/control", key, JSONObject()
                        .put("requestId", UUID.randomUUID().toString())
                        .put("payload", JSONObject()
                            .put("sku", device.model)
                            .put("device", device.deviceId)
                            .put("capability", JSONObject()
                                .put("type", "devices.capabilities.on_off")
                                .put("instance", "powerSwitch")
                                .put("value", if (turnOn) 1 else 0))))
                } else {
                    request("PUT", "$OLD_API/devices/control", key, JSONObject()
                        .put("device", device.deviceId)
                        .put("model", device.model)
                        .put("cmd", JSONObject().put("name", "turn").put("value", if (turnOn) "on" else "off")))
                }
                // The router API can answer HTTP 200 with an error code in the body.
                val bodyCode = try { JSONObject(r.body).optInt("code", 200) } catch (e: Exception) { 200 }
                if (r.code in 200..299 && bodyCode == 200) null else describe(HttpResult(if (r.code in 200..299) bodyCode else r.code, r.body))
            } catch (e: Exception) {
                "Couldn't reach Govee (${e.javaClass.simpleName})"
            }
            mainHandler.post { onResult(error == null, error) }
        }.start()
    }

    /** Current brightness (1-100) of a light, or null if unknown. Blocking. */
    fun readBrightness(context: Context, d: Device): Int? {
        val key = getApiKey(context) ?: return null
        if (d.api != 2) return null
        return try {
            val r = request("POST", "$NEW_API/device/state", key, JSONObject()
                .put("requestId", UUID.randomUUID().toString())
                .put("payload", JSONObject().put("sku", d.model).put("device", d.deviceId)))
            val caps = JSONObject(r.body).optJSONObject("payload")?.optJSONArray("capabilities") ?: JSONArray()
            (0 until caps.length()).map { caps.getJSONObject(it) }
                .firstOrNull { it.optString("instance") == "brightness" }
                ?.optJSONObject("state")?.optInt("value")?.takeIf { it in 1..100 }
        } catch (e: Exception) { null }
    }

    /** Sets brightness (1-100). Blocking; returns true on success. */
    fun setBrightness(context: Context, d: Device, percent: Int): Boolean {
        val key = getApiKey(context) ?: return false
        return try {
            val r = if (d.api == 2) {
                request("POST", "$NEW_API/device/control", key, JSONObject()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("payload", JSONObject()
                        .put("sku", d.model)
                        .put("device", d.deviceId)
                        .put("capability", JSONObject()
                            .put("type", "devices.capabilities.range")
                            .put("instance", "brightness")
                            .put("value", percent.coerceIn(1, 100)))))
            } else {
                request("PUT", "$OLD_API/devices/control", key, JSONObject()
                    .put("device", d.deviceId).put("model", d.model)
                    .put("cmd", JSONObject().put("name", "brightness").put("value", percent.coerceIn(1, 100))))
            }
            r.code in 200..299
        } catch (e: Exception) { false }
    }
}
