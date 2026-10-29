package com.morgan.minimallauncher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherTempText: TextView
    private lateinit var weatherPlaceText: TextView
    private lateinit var profileRow: RecyclerView
    private lateinit var dockRow: RecyclerView
    private lateinit var backgroundPhotoHelper: BackgroundPhotoHelper
    private val handler = Handler(Looper.getMainLooper())

    private var govDevices: MutableList<GoveeHelper.Device> = mutableListOf()
    private var lastFetchedApiKey: String? = null

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
        profileRow = findViewById(R.id.profileRow)
        dockRow = findViewById(R.id.dockRow)

        profileRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        loadWeather()

        backgroundPhotoHelper = BackgroundPhotoHelper(findViewById(R.id.backgroundPhoto))
        backgroundPhotoHelper.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        refreshProfiles()
        refreshDock()
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

    private fun loadWeather() {
        WeatherHelper.fetchCurrentWeather { tempCelsius, place ->
            if (tempCelsius != null) {
                weatherTempText.text = "$tempCelsius°"
                weatherPlaceText.text = place
            } else {
                weatherTempText.text = "--°"
                weatherPlaceText.text = place
            }
        }
    }

    private fun refreshProfiles() {
        val profiles = ProfileManager.getProfiles(this)
        val active = ProfileManager.getActiveProfile(this)
        profileRow.adapter = ProfileAdapter(profiles, active) { selected ->
            ProfileManager.setActiveProfile(this, selected)
            refreshProfiles()
            refreshDock()
        }
    }

    /**
     * Builds the bottom dock: the active profile's pinned apps, then any
     * Govee smart-plug/light devices (tap to toggle on/off), then a tile
     * to the full app grid and one to Settings.
     */
    private fun refreshDock() {
        val allApps = AppListHelper.loadLaunchableTvApps(this)
        val pinned = ProfileManager.getPinnedApps(this)
        val pinnedApps = if (pinned.isEmpty()) {
            // Sensible default so the dock isn't empty before anyone picks favorites in Settings.
            allApps.take(6)
        } else {
            allApps.filter { pinned.contains(it.packageName) }
        }

        val items = mutableListOf<DockItem>()
        items += pinnedApps.map { DockItem.AppTile(it) }
        items += govDevices.map { DockItem.DeviceTile(it) }
        items += DockItem.MoreAppsTile
        items += DockItem.SettingsTile

        dockRow.adapter = DockAdapter(
            items,
            onAppClick = { entry -> entry.launchIntent?.let { startActivity(it) } },
            onDeviceToggle = { device -> toggleDevice(device) },
            onMoreAppsClick = { startActivity(Intent(this, AllAppsActivity::class.java)) },
            onSettingsClick = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )

        val currentKey = GoveeHelper.getApiKey(this)
        if (currentKey != null && (govDevices.isEmpty() || currentKey != lastFetchedApiKey)) {
            GoveeHelper.listDevices(this) { devices, _ ->
                // Errors are silent here; the detail lives in the Settings screen.
                if (devices != null) {
                    govDevices = devices.toMutableList()
                    lastFetchedApiKey = currentKey
                    refreshDock()
                }
            }
        }
    }

    private fun toggleDevice(device: GoveeHelper.Device) {
        val newState = !device.isOn
        GoveeHelper.setPower(this, device, newState) { success, error ->
            if (success) {
                device.isOn = newState
                refreshDock()
            } else {
                Toast.makeText(this, error ?: "Couldn't reach Govee", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
