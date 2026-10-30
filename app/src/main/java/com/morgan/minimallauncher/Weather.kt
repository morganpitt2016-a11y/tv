package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Current weather from Open-Meteo (free, no key). Location comes from a
 * town set in Settings, or else is estimated from the internet
 * connection. Cached for 20 minutes.
 */
object Weather {
    private const val PREFS = "weather"
    private const val MAX_AGE = 20 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    data class Now(
        val temp: Double, val high: Double, val low: Double, val code: Int, val isDay: Boolean,
        val place: String, val fahrenheit: Boolean, val at: Long,
    ) {
        private fun fmt(v: Double) = "${v.roundToInt()}°"
        val tempText get() = fmt(temp)
        val rangeText get() = "H:${fmt(high)}  L:${fmt(low)}"
        val condition get() = describe(code)
        val icon get() = icon(code, isDay)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCity(c: Context): String? = prefs(c).getString("city", null)?.takeIf { it.isNotBlank() }
    fun setCity(c: Context, city: String?) {
        prefs(c).edit().putString("city", city?.trim()).remove("cache").remove("loc").apply()
    }
    fun isFahrenheit(c: Context) = prefs(c).getBoolean("fahrenheit", false)
    fun setFahrenheit(c: Context, on: Boolean) = prefs(c).edit().putBoolean("fahrenheit", on).remove("cache").apply()

    fun cached(c: Context): Now? = try {
        prefs(c).getString("cache", null)?.let { j ->
            val o = JSONObject(j)
            Now(o.getDouble("t"), o.getDouble("h"), o.getDouble("l"), o.getInt("c"), o.getBoolean("d"),
                o.getString("p"), o.getBoolean("f"), o.getLong("at"))
        }
    } catch (e: Exception) { null }

    /** Calls back on the main thread with fresh (or recent cached) weather, or null. */
    fun refresh(c: Context, force: Boolean = false, done: (Now?) -> Unit) {
        val app = c.applicationContext
        val cache = cached(app)
        if (!force && cache != null && System.currentTimeMillis() - cache.at < MAX_AGE) {
            done(cache); return
        }
        Thread {
            val result = try { fetch(app) } catch (e: Exception) { null }
            main.post { done(result ?: cache) }
        }.start()
    }

    private fun fetch(c: Context): Now? {
        val (lat, lon, place) = location(c) ?: return null
        val f = isFahrenheit(c)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&forecast_days=1&timezone=auto" + if (f) "&temperature_unit=fahrenheit" else ""
        val o = JSONObject(Net.get(url))
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val now = Now(
            cur.getDouble("temperature_2m"),
            daily.getJSONArray("temperature_2m_max").getDouble(0),
            daily.getJSONArray("temperature_2m_min").getDouble(0),
            cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, place, f, System.currentTimeMillis(),
        )
        prefs(c).edit().putString("cache", JSONObject()
            .put("t", now.temp).put("h", now.high).put("l", now.low).put("c", now.code).put("d", now.isDay)
            .put("p", now.place).put("f", now.fahrenheit).put("at", now.at).toString()).apply()
        return now
    }

    /** (lat, lon, place name), remembered once found. */
    private fun location(c: Context): Triple<Double, Double, String>? {
        prefs(c).getString("loc", null)?.let {
            val o = JSONObject(it)
            return Triple(o.getDouble("lat"), o.getDouble("lon"), o.getString("name"))
        }
        val city = getCity(c)
        val found: Triple<Double, Double, String>? = if (city != null) {
            val r = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
                URLEncoder.encode(city, "UTF-8"))).optJSONArray("results")?.optJSONObject(0)
            r?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.getString("name")) }
        } else {
            try {
                val o = JSONObject(Net.get("https://ipwho.is/"))
                if (o.optBoolean("success", true)) Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city", "Here")) else null
            } catch (e: Exception) {
                val o = JSONObject(Net.get("https://ipapi.co/json/"))
                Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city", "Here"))
            }
        }
        found?.let { (lat, lon, name) ->
            prefs(c).edit().putString("loc", JSONObject().put("lat", lat).put("lon", lon).put("name", name).toString()).apply()
        }
        return found
    }

    fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66 -> "Rain"
        65, 67 -> "Heavy Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95, 96, 99 -> "Thunderstorms"
        else -> "—"
    }

    fun icon(code: Int, day: Boolean) = when (code) {
        0, 1 -> if (day) R.drawable.ic_lucide_sun else R.drawable.ic_lucide_moon
        2 -> if (day) R.drawable.ic_lucide_cloud_sun else R.drawable.ic_lucide_cloud
        3 -> R.drawable.ic_lucide_cloud
        45, 48 -> R.drawable.ic_lucide_cloud_fog
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_lucide_cloud_snow
        95, 96, 99 -> R.drawable.ic_lucide_cloud_lightning
        else -> R.drawable.ic_lucide_cloud_rain
    }
}
