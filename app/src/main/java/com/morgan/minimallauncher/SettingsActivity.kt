package com.morgan.minimallauncher

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var deviceStatusText: TextView
    private lateinit var profileListContainer: LinearLayout
    private lateinit var appCheckboxContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        deviceStatusText = findViewById(R.id.deviceStatusText)
        profileListContainer = findViewById(R.id.profileListContainer)
        appCheckboxContainer = findViewById(R.id.appCheckboxContainer)

        GoveeHelper.getApiKey(this)?.let { apiKeyInput.setText(it) }

        findViewById<TextView>(R.id.saveApiKeyButton).setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                GoveeHelper.setApiKey(this, key)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.refreshDevicesButton).setOnClickListener {
            deviceStatusText.text = "Loading..."
            GoveeHelper.listDevices(this) { devices, error ->
                deviceStatusText.text = when {
                    devices != null && devices.isNotEmpty() ->
                        "Found ${devices.size} device(s): " + devices.joinToString(", ") { it.name }
                    devices != null -> "Connected, but no devices found on this Govee account."
                    else -> "Error: $error"
                }
            }
        }

        findViewById<TextView>(R.id.addProfileButton).setOnClickListener { showAddProfileDialog() }

        findViewById<TextView>(R.id.qsWifi).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<TextView>(R.id.qsDisplay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        findViewById<TextView>(R.id.qsApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }

        findViewById<TextView>(R.id.autoLaunchToggle).setOnClickListener {
            AutoLaunch.setEnabled(this, !AutoLaunch.isEnabled(this))
            refreshAutoLaunch()
        }
        findViewById<TextView>(R.id.openAccessibilityButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Accessibility settings aren't available on this device", Toast.LENGTH_SHORT).show()
            }
        }

        rebuildProfileList()
        rebuildAppCheckboxes()
    }

    override fun onResume() {
        super.onResume()
        // The user may be coming back from the accessibility settings.
        refreshAutoLaunch()
    }

    private fun refreshAutoLaunch() {
        val serviceOn = HomeRedirectService.isServiceEnabled(this)
        val wanted = AutoLaunch.isEnabled(this)
        findViewById<TextView>(R.id.autoLaunchToggle).text =
            if (wanted) "Open automatically: On" else "Open automatically: Off"
        findViewById<TextView>(R.id.autoLaunchStatus).text = when {
            !wanted -> "Off. The Google TV home screen will show as normal."
            serviceOn -> "Working. Minimal Launcher opens when the TV turns on and when you leave an app."
            else -> "One more step: turn on the accessibility service (button below)."
        }
        findViewById<TextView>(R.id.autoLaunchDiag).text = RedirectDiag.summary(this)
    }

    private fun showAddProfileDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New profile name")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    ProfileManager.addProfile(this, name)
                    rebuildProfileList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildProfileList() {
        profileListContainer.removeAllViews()
        val active = ProfileManager.getActiveProfile(this)
        for (name in ProfileManager.getProfiles(this)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)

            val label = TextView(this)
            label.text = if (name == active) "$name (active)" else name
            label.setTextColor(resources.getColor(R.color.text_white, theme))
            label.textSize = 16f
            label.setOnClickListener {
                ProfileManager.setActiveProfile(this, name)
                rebuildProfileList()
                rebuildAppCheckboxes()
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val deleteBtn = TextView(this)
            deleteBtn.text = "Delete"
            deleteBtn.setTextColor(resources.getColor(R.color.text_grey, theme))
            deleteBtn.setOnClickListener {
                ProfileManager.removeProfile(this, name)
                rebuildProfileList()
                rebuildAppCheckboxes()
            }
            row.addView(deleteBtn)

            profileListContainer.addView(row)
        }
    }

    private fun rebuildAppCheckboxes() {
        appCheckboxContainer.removeAllViews()
        val active = ProfileManager.getActiveProfile(this)
        val pinned = ProfileManager.getPinnedApps(this, active).toMutableSet()
        val apps = AppListHelper.loadLaunchableTvApps(this)

        for (app in apps) {
            val checkBox = CheckBox(this)
            checkBox.text = app.label
            checkBox.setTextColor(resources.getColor(R.color.text_white, theme))
            checkBox.isChecked = pinned.contains(app.packageName)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) pinned.add(app.packageName) else pinned.remove(app.packageName)
                ProfileManager.setPinnedApps(this, active, pinned)
            }
            appCheckboxContainer.addView(checkBox)
        }
    }
}
