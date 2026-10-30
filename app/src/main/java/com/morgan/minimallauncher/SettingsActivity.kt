package com.morgan.minimallauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.morgan.minimallauncher.GlassUi.dp

/**
 * tvOS-style Settings. Every row is a single remote-focusable item that
 * turns white when selected; values show in grey on the right. Anything
 * that needs typing or choosing opens a GlassDialog.
 */
class SettingsActivity : AppCompatActivity() {

    private class Row(val view: View, val value: TextView)

    private lateinit var list: LinearLayout

    private lateinit var profileRow: Row
    private lateinit var dockRow: Row
    private lateinit var homeRow: Row
    private lateinit var bootRow: Row
    private lateinit var wakeRow: Row
    private lateinit var accessibilityRow: Row
    private lateinit var overlayRow: Row
    private lateinit var usageRow: Row
    private lateinit var autoStatus: TextView
    private lateinit var autoDiag: TextView
    private lateinit var keyRow: Row
    private lateinit var devicesRow: Row
    private lateinit var devicesNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        GlassUi.installBackdrop(findViewById<FrameLayout>(R.id.settingsRoot))
        findViewById<View>(R.id.settingsIconTile).background = GlassUi.panel(this, 34f, 0x30)
        list = findViewById(R.id.settingsList)

        section("Home Screen")
        group {
            profileRow = row("Profile") { showProfilePicker() }
            dockRow = row("Dock Apps") { showDockAppsPicker() }
        }

        section("Open Automatically")
        group {
            homeRow = row("Instead of Google TV Home") { AutoLaunch.setEnabled(this, !AutoLaunch.isEnabled(this)); refreshValues() }
            bootRow = row("When the TV Turns On") { AutoLaunch.setOnBoot(this, !AutoLaunch.onBoot(this)); refreshValues() }
            wakeRow = row("When Waking from Sleep") { AutoLaunch.setOnWake(this, !AutoLaunch.onWake(this)); refreshValues() }
            accessibilityRow = row("Accessibility Service") { openAccessibilitySwitch() }
            usageRow = row("Usage Access") {
                if (!tryOpen(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))))
                    open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "Usage access settings")
            }
            overlayRow = row("Display Over Other Apps") {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    "\"Display over other apps\"")
            }
        }
        autoStatus = footnote("")
        autoDiag = footnote("", mono = true)

        section("Govee Lights")
        group {
            keyRow = row("API Key") { showApiKeyDialog() }
            devicesRow = row("Devices") { refreshDevices() }
        }
        devicesNote = footnote("")

        section("System")
        group {
            row("Wi-Fi", chevron = true) { open(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings") }
            row("Display & Sound", chevron = true) { open(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Display settings") }
            row("Apps", chevron = true) { open(Intent(Settings.ACTION_APPLICATION_SETTINGS), "App settings") }
            row("All TV Settings", chevron = true) { open(Intent(Settings.ACTION_SETTINGS), "TV settings") }
        }

        section("About")
        group {
            row("Photo Credits", chevron = true) {
                GlassDialog(this).title("Photo Credits").message(getString(R.string.wallpaper_credits))
                    .button("Done", GlassDialog.Style.BOLD).show()
            }
        }
        footnote("Minimal Launcher · personal build")

        list.post { list.getChildAt(1)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() } }
        if (GoveeHelper.getApiKey(this) != null) refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshValues() // e.g. coming back from the accessibility settings
    }

    private fun refreshValues() {
        val active = ProfileManager.getActiveProfile(this)
        profileRow.value.text = active
        val pinned = ProfileManager.getPinnedApps(this, active).size
        dockRow.value.text = if (pinned == 0) "Automatic" else "$pinned chosen"

        fun onOff(b: Boolean) = if (b) "On" else "Off"
        homeRow.value.text = onOff(AutoLaunch.isEnabled(this))
        bootRow.value.text = onOff(AutoLaunch.onBoot(this))
        wakeRow.value.text = onOff(AutoLaunch.onWake(this))
        val serviceOn = HomeRedirectService.isServiceEnabled(this)
        accessibilityRow.value.text = onOff(serviceOn)
        overlayRow.value.text = if (Settings.canDrawOverlays(this)) "Allowed" else "Not Allowed"
        usageRow.value.text = if (AppWatchService.hasUsageAccess(this)) "Allowed" else "Not Allowed"
        AppWatchService.startIfNeeded(this)
        val anyWanted = AutoLaunch.isEnabled(this) || AutoLaunch.onBoot(this) || AutoLaunch.onWake(this)
        autoStatus.text = when {
            !anyWanted -> "All off: the Google TV home screen shows as normal."
            serviceOn -> "Ready. Google TV has no setting to change the home app, so the accessibility service opens Minimal Launcher for you."
            AppWatchService.canRun(this) -> "Ready (backup method). Usage Access lets Minimal Launcher spot the Google TV home screen and open itself instead."
            else -> "Turn on ONE of these:\n\u2022 Accessibility Service (opens this app\u2019s switch directly), or\n\u2022 if your TV\u2019s accessibility page doesn\u2019t work: allow both Usage Access and Display Over Other Apps."
        }
        autoDiag.text = RedirectDiag.summary(this)
        keyRow.value.text = if (GoveeHelper.getApiKey(this) != null) "Set" else "Not Set"
    }

    // ---------- Dialogs ----------

    private fun showProfilePicker() {
        val profiles = ProfileManager.getProfiles(this)
        val active = ProfileManager.getActiveProfile(this)
        val d = GlassDialog(this).title("Profile").message("Each person has their own dock apps.").scrolling()
        for (p in profiles) {
            d.button(if (p == active) "$p  ✓" else p, if (p == active) GlassDialog.Style.BOLD else GlassDialog.Style.NORMAL) {
                ProfileManager.setActiveProfile(this, p)
                refreshValues()
            }
        }
        d.button("Add Person…") { showAddProfile() }
        if (profiles.size > 1) {
            d.button("Delete “$active”", GlassDialog.Style.DESTRUCTIVE) {
                GlassDialog(this).title("Delete “$active”?")
                    .message("Their dock apps will be forgotten.")
                    .button("Delete", GlassDialog.Style.DESTRUCTIVE) {
                        ProfileManager.removeProfile(this, active); refreshValues()
                        GlassUi.toast(this, "Deleted $active")
                    }
                    .button("Cancel", GlassDialog.Style.BOLD)
                    .focusButton(1)
                    .show()
            }
        }
        d.button("Cancel", GlassDialog.Style.BOLD)
        d.focusButton(profiles.indexOf(active).coerceAtLeast(0)).show()
    }

    private fun showAddProfile() {
        val d = GlassDialog(this)
        d.title("Add Person").message("Enter a name for the new profile.").input("Name")
            .button("Add", GlassDialog.Style.BOLD) {
                val name = d.inputText.replace(",", " ").trim()
                if (name.isEmpty()) return@button
                ProfileManager.addProfile(this, name)
                ProfileManager.setActiveProfile(this, name)
                refreshValues()
                GlassUi.toast(this, "Switched to $name")
            }
            .button("Cancel")
            .show()
    }

    private fun showDockAppsPicker() {
        val active = ProfileManager.getActiveProfile(this)
        val pinned = ProfileManager.getPinnedApps(this, active).toMutableSet()
        val d = GlassDialog(this).title("Dock Apps")
            .message("Choose the apps for $active’s dock. Up to 10 fit; with none chosen, the dock shows your first 10 apps.")
            .scrolling()
        for (app in AppListHelper.loadLaunchableTvApps(this)) {
            d.toggle(app.label, app.packageName in pinned) {
                if (!pinned.add(app.packageName)) pinned.remove(app.packageName)
                ProfileManager.setPinnedApps(this, active, pinned)
                refreshValues()
            }
        }
        d.button("Done", GlassDialog.Style.BOLD).show()
    }

    private fun showApiKeyDialog() {
        val d = GlassDialog(this)
        d.title("Govee API Key")
            .message("Use the key Govee emailed you. To get one: Govee Home app › Profile › Settings › Apply for API Key.")
            .input("API key", GoveeHelper.getApiKey(this).orEmpty(), secret = true)
            .button("Save", GlassDialog.Style.BOLD) {
                GoveeHelper.setApiKey(this, d.inputText)
                refreshValues()
                refreshDevices()
            }
            .button("Cancel")
            .show()
    }

    private fun refreshDevices() {
        if (GoveeHelper.getApiKey(this) == null) {
            devicesRow.value.text = "—"
            devicesNote.text = "Add your API key to control Govee lights from the home screen."
            return
        }
        devicesRow.value.text = "Checking…"
        devicesNote.text = ""
        GoveeHelper.listDevices(this) { devices, error ->
            when {
                !devices.isNullOrEmpty() -> {
                    devicesRow.value.text = if (devices.size == 1) "1 light" else "${devices.size} lights"
                    devicesNote.text = devices.joinToString("\n") { "${it.name}  ·  ${if (it.isOn) "On" else "Off"}" } +
                        "\n\nThey appear next to the moon button on the home screen."
                }
                else -> {
                    devicesRow.value.text = if (devices == null) "Error" else "None Found"
                    devicesNote.text = error ?: "No devices found."
                }
            }
        }
    }

    /** Jumps straight to this app's switch, skipping the accessibility list page. */
    private fun openAccessibilitySwitch() {
        val me = android.content.ComponentName(this, HomeRedirectService::class.java).flattenToString()
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            tryOpen(Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME, me))) return
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Accessibility settings")
    }

    private fun tryOpen(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (e: Exception) {
        false
    }

    private fun open(intent: Intent, what: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            GlassUi.toast(this, "$what isn’t available on this TV")
        }
    }

    // ---------- Building blocks ----------

    private fun section(title: String) {
        list.addView(GlassUi.text(this, title.uppercase(), 12f, R.font.inter_semibold, 0x8CFFFFFF.toInt()).apply {
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(this@SettingsActivity, 16f)
            topMargin = dp(this@SettingsActivity, if (list.childCount == 0) 0f else 26f)
            bottomMargin = dp(this@SettingsActivity, 8f)
        })
    }

    private var currentGroup: LinearLayout? = null

    private fun group(build: () -> Unit) {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUi.panel(this@SettingsActivity, 20f)
            setPadding(dp(context, 6f), dp(context, 6f), dp(context, 6f), dp(context, 6f))
            clipChildren = false
            clipToPadding = false
        }
        currentGroup = g
        build()
        currentGroup = null
        list.addView(g, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(title: String, chevron: Boolean = false, onClick: () -> Unit): Row {
        val c = this
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c)
            setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            isFocusable = true
            isClickable = true
            contentDescription = title
            setOnClickListener { onClick() }
        }
        row.addView(GlassUi.rowText(c, title, 15.5f, R.font.inter_medium, GlassUi.primaryText()),
            LinearLayout.LayoutParams(0, -2, 1f))
        val value = GlassUi.rowText(c, "", 14.5f, R.font.inter_regular, GlassUi.secondaryText())
        row.addView(value, LinearLayout.LayoutParams(-2, -2))
        if (chevron) {
            row.addView(GlassUi.rowText(c, "›", 20f, R.font.inter_regular, GlassUi.secondaryText()),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 10f) })
        }
        GlassUi.focusLift(row, 1.025f)
        (currentGroup ?: list).addView(row, LinearLayout.LayoutParams(-1, dp(c, 50f)))
        return Row(row, value)
    }

    private fun footnote(text: String, mono: Boolean = false): TextView {
        val t = GlassUi.text(this, text, if (mono) 11f else 12.5f, R.font.inter_regular, 0x8CFFFFFF.toInt()).apply {
            if (mono) typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        list.addView(t, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(this@SettingsActivity, 16f)
            marginEnd = dp(this@SettingsActivity, 16f)
            topMargin = dp(this@SettingsActivity, 10f)
        })
        return t
    }
}
