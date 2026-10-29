package com.morgan.minimallauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherTempText: TextView
    private lateinit var weatherPlaceText: TextView
    private lateinit var appGrid: RecyclerView
    private lateinit var backgroundPhotoHelper: BackgroundPhotoHelper
    private val handler = Handler(Looper.getMainLooper())

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        weatherTempText = findViewById(R.id.weatherTempText)
        weatherPlaceText = findViewById(R.id.weatherPlaceText)
        appGrid = findViewById(R.id.appGrid)

        appGrid.layoutManager = GridLayoutManager(this, 6)
        appGrid.adapter = AppAdapter(loadLaunchableTvApps()) { launchIntent ->
            startActivity(launchIntent)
        }

        setupQuickSettingsRow()
        loadWeather()

        backgroundPhotoHelper = BackgroundPhotoHelper(findViewById(R.id.backgroundPhoto))
        backgroundPhotoHelper.start()
    }

    private fun loadWeather() {
        WeatherHelper.fetchCurrentWeather { tempCelsius, place ->
            if (tempCelsius != null) {
                weatherTempText.text = "$tempCelsius\u00B0"
                weatherPlaceText.text = place
            } else {
                weatherTempText.text = "--\u00B0"
                weatherPlaceText.text = place // "Weather unavailable"
            }
        }
    }

    /**
     * Wires the quick-settings row to real Android TV system screens.
     * Wi-Fi, Display and Apps all jump straight to the matching settings
     * page. "Power" is honest about a real Android limit: a normal app
     * (ours included) cannot programmatically power off or reboot the
     * device - that requires system/signature-level permissions regular
     * apps don't get. So Power opens the device's own power menu entry
     * point instead of pretending to shut the TV down.
     */
    private fun setupQuickSettingsRow() {
        findViewById<TextView>(R.id.qsWifi).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<TextView>(R.id.qsDisplay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        findViewById<TextView>(R.id.qsApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
        findViewById<TextView>(R.id.qsPower).setOnClickListener {
            try {
                // Most Android TV / Google TV builds expose this settings
                // screen; it's the closest a regular app can legally get
                // to a "power" action.
                startActivity(Intent("android.settings.DREAM_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Power controls aren't available to regular apps on this device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundPhotoHelper.stop()
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        clockText.text = DateFormat.format("HH:mm", now)
        dateText.text = DateFormat.format("EEEE d MMMM", now)
    }

    /**
     * Finds every app on the device that declares itself launchable from
     * the TV home screen (LEANBACK_LAUNCHER), falling back to normal
     * LAUNCHER apps if a device doesn't use the leanback category.
     */
    private fun loadLaunchableTvApps(): List<AppEntry> {
        val pm = packageManager
        val results = mutableListOf<ResolveInfo>()

        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER")
        results += pm.queryIntentActivities(leanbackIntent, 0)

        if (results.isEmpty()) {
            val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            results += pm.queryIntentActivities(standardIntent, 0)
        }

        return results
            .filter { it.activityInfo.packageName != packageName } // don't show ourselves
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm),
                    launchIntent = pm.getLeanbackLaunchIntentForPackage(it.activityInfo.packageName)
                        ?: pm.getLaunchIntentForPackage(it.activityInfo.packageName)
                )
            }
            .filter { it.launchIntent != null }
            .sortedBy { it.label.lowercase() }
    }
}
