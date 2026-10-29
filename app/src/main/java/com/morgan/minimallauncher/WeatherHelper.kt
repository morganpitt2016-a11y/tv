package com.morgan.minimallauncher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a rough current temperature with no API key and no GPS permission,
 * which matters on a TV box that usually has neither a SIM nor a location
 * sensor. Two free, keyless services are chained together:
 *
 *  1. ip-api.com  -> guesses lat/lon from the device's public IP address
 *  2. open-meteo.com -> turns that lat/lon into an actual current temperature
 *
 * IP-based location is approximate (city-level, sometimes off), not exact
 * GPS - good enough for a home screen weather readout, not for anything
 * that needs precision.
 */
object WeatherHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchCurrentWeather(onResult: (tempCelsius: Int?, description: String) -> Unit) {
        Thread {
            try {
                val (lat, lon, city) = fetchApproxLocation()
                val tempC = fetchTemperature(lat, lon)
                mainHandler.post {
                    if (tempC != null) {
                        onResult(tempC, city)
                    } else {
                        onResult(null, "Weather unavailable")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(null, "Weather unavailable") }
            }
        }.start()
    }

    private fun fetchApproxLocation(): Triple<Double, Double, String> {
        val url = URL("http://ip-api.com/json/?fields=lat,lon,city")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val json = JSONObject(body)
        return Triple(json.getDouble("lat"), json.getDouble("lon"), json.optString("city", ""))
    }

    private fun fetchTemperature(lat: Double, lon: Double): Int? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val json = JSONObject(body)
        val current = json.optJSONObject("current_weather") ?: return null
        return current.optDouble("temperature", Double.NaN)
            .takeIf { !it.isNaN() }
            ?.toInt()
    }
}
