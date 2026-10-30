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

    private val refreshers = mutableListOf<() -> Unit>()

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
    private lateinit var claudeKeyRow: Row
    private lateinit var devicesRow: Row
    private lateinit var devicesNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        GlassUi.installBackdrop(findViewById<FrameLayout>(R.id.settingsRoot))
        findViewById<View>(R.id.settingsIconTile).background = GlassUi.panel(this, 34f, 0x30)
        list = findViewById(R.id.settingsList)
        UiSounds.attach(this)

        section("Home Screen")
        group {
            vrow("Account", { Accounts.active(this)?.name ?: "Not Set Up" }) {
                startActivity(Intent(this, WhoActivity::class.java).putExtra(WhoActivity.EXTRA_MODE, WhoActivity.ACCOUNTS))
            }
            vrow("Who’s Watching at Start", { if (Prefs.bool(this, "who_at_start", true)) "On" else "Off" }) {
                Prefs.setBool(this, "who_at_start", !Prefs.bool(this, "who_at_start", true)); refreshValues()
            }
            profileRow = row("Profile") { showProfilePicker() }
            dockRow = row("Dock Apps") { showDockAppsPicker() }
            vrow("Arrange Dock", { "" }, chevron = true) { arrangeDock() }
            vrow("Favourites", { ProfileManager.getFavourites(this).size.let { if (it == 0) "None" else "$it" } }) { pickFavourites() }
            vrow("Wallpaper", { WallpaperLibrary.describe(this) }) { pickWallpaper() }
        }
        footnote("Each person has their own dock, favourites, wallpaper, watch history and recommendations. Tip: hold OK on any app to favourite it or move it in the dock.")

        section("Widgets")
        group {
            toggleRow("Weather", Prefs.W_WEATHER)
            toggleRow("Calendar", Prefs.W_CALENDAR)
            toggleRow("Now Playing", Prefs.W_MUSIC)
            toggleRow("Smart Home", Prefs.W_HOME)
            toggleRow("Continue Watching", Prefs.W_RECENT)
        }
        group {
            vrow("Weather Location", { Weather.getCity(this) ?: "Automatic" }) { editCity() }
            vrow("Temperature", { if (Weather.isFahrenheit(this)) "°F" else "°C" }) { Weather.setFahrenheit(this, !Weather.isFahrenheit(this)); refreshValues() }
            vrow("Calendar Link", { if (CalendarFeed.getUrl(this) != null) "Connected" else "Not Set" }) { editCalendar() }
            vrow("Now Playing Access", { if (NowPlaying.hasAccess(this)) "Allowed" else "Not Allowed" }) {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Notification access settings")
            }
        }
        footnote("Calendar: paste your calendar’s private iCal link (easiest from the iPhone remote’s More tab). Now Playing needs Notification Access for Minimal Launcher.")

        section("Ambient & Screen Saver")
        group {
            vrow("Start Ambient", { "" }, chevron = true) { AmbientActivity.start(this, AmbientActivity.AMBIENT) }
            vrow("Start Aerial", { "" }, chevron = true) { AmbientActivity.start(this, AmbientActivity.AERIAL) }
            vrow("Sleep Clock", { "" }, chevron = true) { AmbientActivity.start(this, AmbientActivity.SLEEP) }
        }
        group {
            vrow("When Idle, Show", { if (Prefs.idleAfter(this) == 0) "Nothing" else AmbientActivity.title(Prefs.idleMode(this)) }) {
                choose("When Idle, Show", listOf(AmbientActivity.AMBIENT to "Ambient", AmbientActivity.AERIAL to "Aerial", AmbientActivity.SLEEP to "Sleep Clock", "none" to "Nothing"),
                    if (Prefs.idleAfter(this) == 0) "none" else Prefs.idleMode(this)) { v ->
                    if (v == "none") Prefs.setInt(this, "idle_after", 0) else {
                        Prefs.setString(this, "idle_mode", v)
                        if (Prefs.idleAfter(this) == 0) Prefs.setInt(this, "idle_after", 10)
                    }
                }
            }
            vrow("Start After", { mins(Prefs.idleAfter(this)) }) {
                choose("Start After", listOf(5, 10, 15, 30, 60, 0).map { "$it" to mins(it) }, "${Prefs.idleAfter(this)}") { Prefs.setInt(this, "idle_after", it.toInt()) }
            }
            vrow("Dim Home Screen After", { mins(Prefs.dimAfter(this)) }) {
                choose("Dim Home Screen After", listOf(1, 2, 5, 10, 0).map { "$it" to mins(it) }, "${Prefs.dimAfter(this)}") { Prefs.setInt(this, "dim_after", it.toInt()) }
            }
            vrow("Stay On For", { Prefs.ambientHours(this).let { if (it == 0) "No Limit" else if (it == 1) "1 Hour" else "$it Hours" } }) {
                choose("Stay On For", listOf("1" to "1 Hour", "2" to "2 Hours", "4" to "4 Hours", "8" to "8 Hours", "0" to "No Limit"), "${Prefs.ambientHours(this)}") {
                    Prefs.setInt(this, "ambient_hours", it.toInt())
                }
            }
        }
        footnote("After “Stay On For”, Ambient and Aerial fade to the dark sleep clock and the TV can turn itself off as usual.")
        group {
            vrow("Ambient Pictures", { sourceName(Prefs.ambientSource(this)) }) {
                choose("Ambient Pictures", listOf("aerial", "art", "album", "builtin").map { it to sourceName(it) }, Prefs.ambientSource(this)) {
                    Prefs.setString(this, "ambient_source", it)
                }
            }
            vrow("Landscape Category", { PhotoSources.aerialCategories.firstOrNull { it.id == Prefs.aerialCategory(this) }?.title ?: "Mountains" }) {
                choose("Landscape Category", PhotoSources.aerialCategories.map { it.id to it.title }, Prefs.aerialCategory(this)) {
                    Prefs.setString(this, "aerial_category", it)
                }
            }
            vrow("Show Location", { onOff(Prefs.showLocation(this)) }) { Prefs.setBool(this, "show_location", !Prefs.showLocation(this)); refreshValues() }
            vrow("Music Visualiser", { onOff(Prefs.visualiser(this)) }) { toggleVisualiser() }
            vrow("System Screen Saver", { "" }, chevron = true) { open(Intent(Settings.ACTION_DREAM_SETTINGS), "Screen saver settings") }
        }
        footnote("Landscapes and artwork download once over Wi-Fi (Wikimedia Commons featured pictures; public-domain paintings from the Art Institute of Chicago). The visualiser follows the music when Android allows the microphone permission; otherwise it sways gently.")

        section("Cinema Mode")
        group {
            vrow("Do Not Disturb", {
                when {
                    !Prefs.cinemaDnd(this) -> "Off"
                    CinemaMode.hasDndAccess(this) -> "On"
                    else -> "Needs Access"
                }
            }) {
                if (Prefs.cinemaDnd(this) && !CinemaMode.hasDndAccess(this)) {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Do Not Disturb access settings")
                } else {
                    Prefs.setBool(this, "cinema_dnd", !Prefs.cinemaDnd(this)); refreshValues()
                }
            }
            vrow("Dim Govee Lights", { onOff(Prefs.cinemaLights(this)) }) { Prefs.setBool(this, "cinema_lights", !Prefs.cinemaLights(this)); refreshValues() }
        }
        footnote("The film button on the home screen dims everything, silences notifications, dims your lights to 12%, shows a cinema clock and opens Watch on All Films. Press it again to undo.")

        section("Live TV")
        group {
            vrow("TV Guide", { if (LiveGuide.customUrl(this) != null) "Your Guide Link" else "Freeview" }) { editGuide() }
            vrow("TiviMate Tuning Log", { "" }, chevron = true) {
                GlassDialog(this).title("TiviMate Tuning Log")
                    .message(TiviTuner.logText().takeLast(3500).let { if (it.length < 300) it + "\n\n(Long logs scroll - use Up/Down.)" else it })
                    .button("Done", GlassDialog.Style.BOLD).show()
            }
        }
        footnote("What happened the last time Live TV tried to switch TiviMate to a channel - including what TiviMate showed if it couldn’t find it.")

        section("iPhone Remote")
        group {
            vrow("iPhone Remote", { onOff(RemoteService.isEnabled(this)) }) {
                RemoteService.setEnabled(this, !RemoteService.isEnabled(this)); refreshValues()
                if (RemoteService.isEnabled(this)) showRemoteQr()
            }
            vrow("Connect iPhone", { "" }, chevron = true) {
                if (!RemoteService.isEnabled(this)) RemoteService.setEnabled(this, true)
                refreshValues(); showRemoteQr()
            }
        }
        footnote("Works over your home Wi-Fi: touchpad, buttons, volume, typing, apps, modes and uploading your own photos. For full control of other apps, also turn on the Accessibility Service above.")

        section("Feel")
        group {
            vrow("Sounds", { onOff(Prefs.sounds(this)) }) { Prefs.setBool(this, "sounds", !Prefs.sounds(this)); refreshValues() }
            vrow("Ambient Lighting", { onOff(Prefs.ambientLight(this)) }) { Prefs.setBool(this, "ambient_light", !Prefs.ambientLight(this)); refreshValues() }
        }
        footnote("Ambient lighting makes the wallpaper a little darker in the evening and at night. Haptic feedback isn’t possible: the Chromecast remote has no vibration motor.")

        section("Open Automatically")
        group {
            homeRow = row("Instead of Google TV Home") { AutoLaunch.setEnabled(this, !AutoLaunch.isEnabled(this)); refreshValues() }
            bootRow = row("When the TV Turns On") { AutoLaunch.setOnBoot(this, !AutoLaunch.onBoot(this)); refreshValues() }
            wakeRow = row("When Waking from Sleep") { AutoLaunch.setOnWake(this, !AutoLaunch.onWake(this)); refreshValues() }
            accessibilityRow = row("Accessibility Service") { accessibilityPressed() }
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

        section("Claude")
        group {
            claudeKeyRow = row("API Key") { showClaudeKeyDialog() }
            row("Open Claude", chevron = true) { startActivity(Intent(this, ClaudeActivity::class.java)) }
        }
        footnote("Ask Claude questions on your TV with the remote or your voice. Uses your own Anthropic API key, billed per question.")

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
        accessibilityRow.value.text = when {
            HomeRedirectService.instance != null -> "On"
            serviceOn -> "On · Not Running"
            else -> "Off"
        }
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
        claudeKeyRow.value.text = if (ClaudeChat.getApiKey(this) != null) "Set" else "Not Set"
        refreshers.forEach { it() }
    }

    // ---------- Dialogs ----------

    private fun showClaudeKeyDialog() {
        val d = GlassDialog(this)
            .title("Claude API Key")
            .message("Create a key at console.anthropic.com › API Keys and enter it here. It stays on this TV.")
            .input("sk-ant-…", ClaudeChat.getApiKey(this).orEmpty(), secret = true)
        d.button("Save", GlassDialog.Style.BOLD) {
            ClaudeChat.setApiKey(this, d.inputText)
            refreshValues()
        }
        if (ClaudeChat.getApiKey(this) != null) {
            d.button("Remove Key", GlassDialog.Style.DESTRUCTIVE) {
                ClaudeChat.setApiKey(this, null)
                refreshValues()
            }
        }
        d.button("Cancel")
        d.show()
    }

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
            .message("Choose the apps for $active’s dock. Up to 9 fit; with none chosen, the dock shows your first 9 apps.")
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
    /** One press: switch it on directly if allowed, otherwise go straight to its on/off page. */
    private fun accessibilityPressed() {
        if (HomeRedirectService.isServiceEnabled(this) || SelfEnable.canSelfEnable(this)) {
            if (!HomeRedirectService.isServiceEnabled(this)) SelfEnable.enableAll(this)
            handler.postDelayed({ refreshValues(); showAccessibilityCheck() }, 1500)
            return
        }
        if (SelfEnable.enableAll(this)) {
            GlassUi.toast(this, "Accessibility Service turned on")
            handler.postDelayed({ refreshValues() }, 800)
            return
        }
        GlassDialog(this).title("Turn On Accessibility Service")
            .message("Open its on/off switch now, or set it up once so Minimal Launcher turns it on by itself from now on.")
            .button("Open the Switch", GlassDialog.Style.BOLD) { openAccessibilitySwitch() }
            .button("Turn On Automatically…") { showSelfEnableSteps() }
            .button("Cancel")
            .show()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** What Android actually has set, so a photo shows why it isn't working. */
    private fun showAccessibilityCheck() {
        val cr = contentResolver
        val enabledList = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val master = try { Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (e: Exception) { -1 }
        val running = HomeRedirectService.instance != null
        val msg = buildString {
            append(if (running) "✓ Running - Live TV can switch TiviMate channels.\n\n" else "✗ Not running.\n\n")
            append("Switched on in Android: ").append(if (HomeRedirectService.isServiceEnabled(this@SettingsActivity)) "yes" else "no").append('\n')
            append("Accessibility master switch: ").append(master).append('\n')
            append("Can switch itself on (ADB permission): ").append(if (SelfEnable.canSelfEnable(this@SettingsActivity)) "yes" else "no").append('\n')
            append("Android ").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append("Enabled services: ").append(enabledList ?: "none").append("\n\n")
            append(RedirectDiag.summary(this@SettingsActivity))
            if (!running) append("\n\nIf it says switched on but not running, Android may be blocking it as a “restricted setting”. Fix with ADB:\nadb shell appops set com.morgan.minimallauncher ACCESS_RESTRICTED_SETTINGS allow\nthen restart the TV.")
        }
        GlassDialog(this).title("Accessibility Check").message(msg).button("Done", GlassDialog.Style.BOLD).show()
    }

    private fun showSelfEnableSteps() {
        val ip = RemoteServer.ip(this) ?: "TV-IP"
        GlassDialog(this).title("Turn On Automatically")
            .message("One-time setup from your iPhone - no computer needed. These steps are also on the iPhone remote (More tab) for copying.\n\n" +
                "1. On this TV: Settings › System › About › select “Android TV OS build” 7 times. Then Settings › System › Developer options › turn on “USB debugging”.\n\n" +
                "2. On your iPhone: install “iSH Shell” (free, App Store), open it and type:\n" +
                "apk add android-tools\n" +
                "adb connect $ip:5555\n\n" +
                "3. Choose “Allow” on the TV when it asks, then type on the iPhone:\n" +
                SelfEnable.ADB_COMMAND + "\n\n" +
                "4. Done. Minimal Launcher switches its Accessibility Service on itself (and Now Playing access), and turns it back on if it’s ever switched off. You can turn USB debugging off again.\n\n" +
                "If “adb connect” is refused: in Developer options turn on “Wireless debugging”, choose “Pair device with pairing code”, run “adb pair IP:PORT CODE” with what it shows, then “adb connect” to the IP:port on the Wireless debugging screen.")
            .button("Done", GlassDialog.Style.BOLD)
            .show()
    }

    private fun openAccessibilitySwitch() {
        val me = android.content.ComponentName(this, HomeRedirectService::class.java).flattenToString()
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            tryOpen(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me))) return
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

    // ---------- Helpers for the newer sections ----------

    /** A row whose value is read again whenever the screen refreshes. */
    private fun vrow(title: String, value: () -> String, chevron: Boolean = false, onClick: () -> Unit): Row {
        val r = row(title, chevron, onClick)
        refreshers += { r.value.text = value() }
        return r
    }

    private fun toggleRow(title: String, key: String) =
        vrow(title, { onOff(Prefs.widget(this, key)) }) { Prefs.setBool(this, key, !Prefs.widget(this, key)); refreshValues() }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun mins(m: Int) = when (m) { 0 -> "Never"; 60 -> "1 Hour"; 1 -> "1 Minute"; else -> "$m Minutes" }

    private fun sourceName(s: String) = when (s) {
        "aerial" -> "Landscapes"
        "art" -> "Artwork"
        "album" -> "My Photos"
        else -> "Built-in Photos"
    }

    /** A list of choices with a tick on the current one. */
    private fun choose(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val d = GlassDialog(this).title(title)
        if (options.size > 6) d.scrolling()
        for ((value, label) in options) {
            d.button(if (value == current) "$label  ✓" else label, if (value == current) GlassDialog.Style.BOLD else GlassDialog.Style.NORMAL) {
                onPick(value); refreshValues()
            }
        }
        d.focusButton(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
        d.show()
    }

    private fun pickWallpaper() {
        val albumCount = WallpaperLibrary.album(this).size
        val options = listOf("all" to "All Photos") +
            WallpaperLibrary.categories.map { "cat:${it.id}" to it.title } +
            ("album" to if (albumCount == 0) "My Photos (add from iPhone)" else "My Photos ($albumCount)") +
            WallpaperLibrary.bundled.map { "photo:${it.number}" to it.place }
        choose("Wallpaper for ${ProfileManager.getActiveProfile(this)}", options, ProfileManager.getWallpaper(this)) {
            if (it == "album" && albumCount == 0) GlassUi.toast(this, "Add photos from the iPhone remote’s Photos tab")
            ProfileManager.setWallpaper(this, it)
        }
    }

    private fun pickFavourites() {
        val favs = ProfileManager.getFavourites(this)
        val d = GlassDialog(this).title("Favourites").message("Shown in the Favourites row below the dock.").scrolling()
        for (app in AppListHelper.loadLaunchableTvApps(this)) {
            d.toggle(app.label, app.packageName in favs) { ProfileManager.toggleFavourite(this, app.packageName); refreshValues() }
        }
        d.button("Done", GlassDialog.Style.BOLD).show()
    }

    private fun arrangeDock() {
        val all = AppListHelper.loadLaunchableTvApps(this)
        val byPkg = all.associateBy { it.packageName }
        val dock = ProfileManager.getDockOrder(this).ifEmpty { all.take(10).map { it.packageName } }
        val d = GlassDialog(this).title("Arrange Dock").message("Choose an app to move or remove.").scrolling()
        dock.mapNotNull { byPkg[it] }.forEachIndexed { i, app ->
            d.button("${i + 1}.  ${app.label}") { AppActions.menu(this, app, null, dock) { refreshValues(); arrangeDock() } }
        }
        d.button("Done", GlassDialog.Style.BOLD).show()
    }

    private fun editCity() {
        val d = GlassDialog(this).title("Weather Location").message("Enter a town or city, or leave empty to work it out from your internet connection.")
            .input("Town or city", Weather.getCity(this).orEmpty())
        d.button("Save", GlassDialog.Style.BOLD) { Weather.setCity(this, d.inputText.ifBlank { null }); refreshValues() }
        d.button("Cancel").show()
    }

    private fun editGuide() {
        val d = GlassDialog(this).title("TV Guide")
            .message("To match TiviMate’s channels, use the same guide: in TiviMate open Settings › EPG, note the EPG source link, and enter it here (easiest from the iPhone remote’s More tab). Leave empty for the free Freeview guide.")
            .input("http://…/epg.xml", LiveGuide.customUrl(this).orEmpty())
        d.button("Save", GlassDialog.Style.BOLD) { LiveGuide.setCustomUrl(this, d.inputText.ifBlank { null }); refreshValues() }
        if (LiveGuide.customUrl(this) != null) d.button("Use Freeview Instead") { LiveGuide.setCustomUrl(this, null); refreshValues() }
        d.button("Cancel").show()
    }

    private fun editCalendar() {
        val d = GlassDialog(this).title("Calendar Link")
            .message("Paste your calendar’s private iCal (.ics) link. Google Calendar: Settings › your calendar › “Secret address in iCal format”. Typing is easier from the iPhone remote’s More tab.")
            .input("https://…", CalendarFeed.getUrl(this).orEmpty())
        d.button("Save", GlassDialog.Style.BOLD) { CalendarFeed.setUrl(this, d.inputText.ifBlank { null }); refreshValues() }
        if (CalendarFeed.getUrl(this) != null) d.button("Remove", GlassDialog.Style.DESTRUCTIVE) { CalendarFeed.setUrl(this, null); refreshValues() }
        d.button("Cancel").show()
    }

    private fun toggleVisualiser() {
        val on = !Prefs.visualiser(this)
        Prefs.setBool(this, "visualiser", on)
        if (on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
        }
        refreshValues()
    }

    private fun showRemoteQr() {
        val url = RemoteServer.url(this)
        if (url == null) {
            GlassDialog(this).title("Not Connected").message("Connect the TV to Wi-Fi first.").button("OK", GlassDialog.Style.BOLD).show()
            return
        }
        GlassDialog(this).title("Connect Your iPhone")
            .message("Join the same Wi-Fi, then point the iPhone camera at this code. Or open:\n$url")
            .image(RemoteService.qr(url, 480))
            .button("Done", GlassDialog.Style.BOLD)
            .show()
    }
}
