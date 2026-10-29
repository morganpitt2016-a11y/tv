package com.morgan.minimallauncher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
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
    private lateinit var profileSwitchContainer: LinearLayout
    private lateinit var continueWatchingLabel: TextView
    private lateinit var continueWatchingRow: RecyclerView
    private lateinit var dockRow: RecyclerView
    private lateinit var nightModeButton: TextView
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
        profileSwitchContainer = findViewById(R.id.profileSwitchContainer)
        continueWatchingLabel = findViewById(R.id.continueWatchingLabel)
        continueWatchingRow = findViewById(R.id.continueWatchingRow)
        dockRow = findViewById(R.id.dockRow)
        nightModeButton = findViewById(R.id.nightModeButton)

        continueWatchingRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Most TV boxes don't let a regular app power off/reboot the
        // device (needs system/signature permissions), so this reuses
        // the closest thing a normal app can legally reach: the system
        // Dream (screensaver/ambient) settings screen.
        nightModeButton.setOnClickListener {
            try {
                startActivity(Intent("android.settings.DREAM_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "Screensaver settings aren't available on this device", Toast.LENGTH_SHORT).show()
            }
        }

        loadWeather()

        backgroundPhotoHelper = BackgroundPhotoHelper(findViewById(R.id.backgroundPhoto))
        backgroundPhotoHelper.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        refreshProfileSwitch()
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

    /** One pill container; only the active profile gets a solid white background. */
    private fun refreshProfileSwitch() {
        val profiles = ProfileManager.getProfiles(this)
        val active = ProfileManager.getActiveProfile(this)
        profileSwitchContainer.removeAllViews()

        for (name in profiles) {
            val isActive = name == active
            val pill = TextView(this)
            pill.text = name
            pill.textSize = 13f
            pill.setPadding(dp(18), dp(8), dp(18), dp(8))
            pill.setTextColor(if (isActive) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            if (!isActive) pill.alpha = 0.65f
            pill.background = if (isActive) {
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.profile_pill_active_bg)
            } else null
            pill.isFocusable = true
            pill.isFocusableInTouchMode = true
            pill.setOnClickListener {
                ProfileManager.setActiveProfile(this, name)
                refreshProfileSwitch()
                refreshDock()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(6)
            profileSwitchContainer.addView(pill, params)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Builds the bottom dock: the active profile's pinned apps, then any
     * Govee smart-plug/light devices (tap to toggle on/off), then a tile
     * to the full app grid and one to Settings. Also refreshes the
     * "Keep watching" row (recently-opened apps - see RecentAppsTracker).
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
            onAppClick = { entry -> launchApp(entry) },
            onDeviceToggle = { device -> toggleDevice(device) },
            onMoreAppsClick = { startActivity(Intent(this, AllAppsActivity::class.java)) },
            onSettingsClick = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )

        refreshContinueWatching(allApps)

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

    private fun refreshContinueWatching(allApps: List<AppEntry>) {
        val recents = RecentAppsTracker.getRecent(this, allApps, limit = 3)
        if (recents.isEmpty()) {
            continueWatchingLabel.visibility = View.GONE
            continueWatchingRow.visibility = View.GONE
        } else {
            continueWatchingLabel.visibility = View.VISIBLE
            continueWatchingRow.visibility = View.VISIBLE
            continueWatchingRow.adapter = ContinueWatchingAdapter(recents) { entry -> launchApp(entry) }
        }
    }

    private fun launchApp(entry: AppEntry) {
        entry.launchIntent?.let {
            RecentAppsTracker.recordOpened(this, entry.packageName)
            startActivity(it)
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
