package com.morgan.minimallauncher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var profileSwitch: LinearLayout
    private lateinit var deviceRow: LinearLayout
    private lateinit var nightModeButton: View
    private lateinit var quickSettingsButton: View
    private lateinit var nightOverlay: View
    private lateinit var keepWatchingLabel: TextView
    private lateinit var keepWatchingRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var wallpaperFront: View
    private lateinit var rotator: WallpaperRotator

    private val handler = Handler(Looper.getMainLooper())
    private var govDevices: List<GoveeHelper.Device> = emptyList()
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

        root = findViewById(R.id.root)
        stage = findViewById(R.id.stage)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        profileSwitch = findViewById(R.id.profileSwitch)
        deviceRow = findViewById(R.id.deviceRow)
        nightModeButton = findViewById(R.id.nightModeButton)
        nightOverlay = findViewById(R.id.nightOverlay)
        keepWatchingLabel = findViewById(R.id.keepWatchingLabel)
        keepWatchingRow = findViewById(R.id.keepWatchingRow)
        dock = findViewById(R.id.dock)
        wallpaperFront = findViewById(R.id.wallpaperFront)

        setupClockFont()
        rotator = WallpaperRotator(
            this, findViewById(R.id.photoA), findViewById(R.id.photoB),
            findViewById(R.id.frontA), findViewById(R.id.frontB)
        ) { photoShowing ->
            // Real photos replace the painted scene, foreground layer included.
            wallpaperFront.visibility = if (photoShowing) View.GONE else View.VISIBLE
        }

        // Scale the 960x540dp stage to fill whatever screen this is.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitStage() }

        // This is the home screen: Back has nowhere to go.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        addFocusScale(nightModeButton, 1.08f)
        nightModeButton.setOnClickListener { setNightMode(!isNightMode()) }
        quickSettingsButton = findViewById(R.id.quickSettingsButton)
        addFocusScale(quickSettingsButton, 1.08f)
        quickSettingsButton.setOnClickListener { openQuickSettings() }
        applyNightMode(animate = false)
    }

    override fun onResume() {
        super.onResume()
        RedirectDiag.launcherShown(this)
        AppWatchService.startIfNeeded(this)
        handler.post(clockTick)
        rotator.start()
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        rotator.stop()
    }

    /**
     * Rebuilds every row. Rebuilding removes the focused button, which
     * would make Android jump focus somewhere else (e.g. to the moon), so
     * remember which row/position had focus and put it back afterwards.
     */
    private fun refreshAll(focusActiveProfile: Boolean = false) {
        val saved = savedFocus()
        val allApps = AppListHelper.loadLaunchableTvApps(this)
        buildProfileSwitch()
        buildDock(allApps)
        buildKeepWatching(allApps)
        buildDeviceRow()
        fetchDevicesIfNeeded()
        if (focusActiveProfile) {
            val i = ProfileManager.getProfiles(this).indexOf(ProfileManager.getActiveProfile(this))
            profileSwitch.getChildAt(i.coerceAtLeast(0))?.requestFocus()
        } else {
            restoreFocus(saved)
        }
    }

    private fun savedFocus(): Pair<ViewGroup, Int>? {
        val focused = currentFocus ?: return null
        val row = listOf(profileSwitch, dock, keepWatchingRow, deviceRow).firstOrNull { focused.parent === it } ?: return null
        return row to row.indexOfChild(focused)
    }

    private fun restoreFocus(saved: Pair<ViewGroup, Int>?) {
        val (row, index) = saved ?: (null to 0)
        val target = row?.takeIf { it.isShown && it.childCount > 0 }?.getChildAt(index.coerceAtMost(row.childCount - 1))
        when {
            target != null -> target.requestFocus()
            currentFocus == null || saved != null -> dock.getChildAt(0)?.requestFocus()
        }
    }

    private fun fitStage() {
        if (root.width == 0 || stage.width == 0) return
        val scale = min(root.width / stage.width.toFloat(), root.height / stage.height.toFloat())
        stage.scaleX = scale
        stage.scaleY = scale
    }

    // ---------- Clock ----------

    /**
     * Apple's SF Pro can't be bundled (its licence only covers mock-ups of
     * Apple-platform software), so the clock uses Roboto Flex tuned on its
     * width/weight/figure-height axes to match SF Pro Condensed's tall,
     * flat-sided numerals in the reference. Variable-font axes need
     * Android 8+; older devices fall back to Oswald Light.
     */
    private fun setupClockFont() {
        if (Build.VERSION.SDK_INT >= 26) {
            clockText.typeface = font(R.font.roboto_flex)
            clockText.setFontVariationSettings("'wdth' 72, 'wght' 480, 'opsz' 144, 'YTFI' 788")
        } else {
            clockText.typeface = font(R.font.oswald_light)
            clockText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 183f)
            clockText.letterSpacing = -0.008f
            (clockText.layoutParams as FrameLayout.LayoutParams).topMargin = dp(16)
        }
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        clockText.text = DateFormat.format(pattern, now)
        dateText.text = DateFormat.format("EEEE d MMMM", now)
    }

    // ---------- Night mode ----------

    fun isNightMode() = getPreferences(MODE_PRIVATE).getBoolean("night", false)

    fun setNightMode(on: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("night", on).apply()
        applyNightMode(animate = true)
    }

    /** Dims the scene with a slow fade either way, rather than an instant switch. */
    private fun applyNightMode(animate: Boolean) {
        val on = isNightMode()
        nightModeButton.isActivated = on
        nightOverlay.animate().cancel()
        if (!animate) {
            nightOverlay.alpha = if (on) 1f else 0f
            nightOverlay.visibility = if (on) View.VISIBLE else View.GONE
            return
        }
        nightOverlay.visibility = View.VISIBLE
        nightOverlay.animate()
            .alpha(if (on) 1f else 0f)
            .setDuration(NIGHT_FADE_MS)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { if (!isNightMode()) nightOverlay.visibility = View.GONE }
            .start()
    }

    // ---------- Quick settings ----------

    private fun openQuickSettings() {
        QuickSettings(this, object : QuickSettings.Host {
            override fun isNightMode() = this@MainActivity.isNightMode()
            override fun setNightMode(on: Boolean) = this@MainActivity.setNightMode(on)
            override fun nextWallpaper() = rotator.next()
            override fun lights() = govDevices
            override fun toggleLight(device: GoveeHelper.Device, done: () -> Unit) = toggleDevice(device, done)
        }).show()
    }

    // ---------- Profile selector ----------

    private fun buildProfileSwitch() {
        profileSwitch.removeAllViews()
        val active = ProfileManager.getActiveProfile(this)
        for (name in ProfileManager.getProfiles(this)) {
            val isActive = name == active
            val seg = TextView(this).apply {
                text = name
                typeface = font(R.font.inter_semibold)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                setTextColor(if (isActive) 0xFF141414.toInt() else 0xBFFFFFFF.toInt())
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isActive) R.drawable.segment_active_bg else R.drawable.segment_bg
                )
                isFocusable = true
                contentDescription = if (isActive) "$name, selected profile" else "Switch to $name"
                setOnClickListener {
                    ProfileManager.setActiveProfile(this@MainActivity, name)
                    refreshAll(focusActiveProfile = true)
                }
            }
            addFocusScale(seg, 1.04f)
            profileSwitch.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(21)))
        }
    }

    // ---------- App dock ----------

    /** Up to 10 of the active profile's apps, then Settings and More. */
    private fun buildDock(allApps: List<AppEntry>) {
        dock.removeAllViews()
        val pinned = ProfileManager.getPinnedApps(this)
        val apps = (if (pinned.isEmpty()) allApps else allApps.filter { it.packageName in pinned }).take(10)

        val tileSize = dp(56)
        for (app in apps) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(TileRenderer.render(app.icon, tileSize))
            }
            addDockTile(image, 0, "Open ${app.label}") { launchApp(app) }
        }
        addDockTile(glyph(R.drawable.ic_lucide_settings, 22), SYSTEM_TILE, getString(R.string.settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        addDockTile(glyph(R.drawable.ic_lucide_more, 22), SYSTEM_TILE, getString(R.string.more_apps)) {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
    }

    private fun glyph(res: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (dp(56) - dp(sizeDp)) / 2
        setPadding(pad, pad, pad, pad)
    }

    private fun addDockTile(content: View, background: Int, label: String, onClick: () -> Unit) {
        val tile = FrameLayout(this).apply {
            this.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(background)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_bezel)
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addFocusScale(tile, 1.09f)
        val lp = LinearLayout.LayoutParams(dp(56), dp(56))
        if (dock.childCount > 0) lp.marginStart = dp(13)
        dock.addView(tile, lp)
    }

    // ---------- Keep watching ----------

    private fun buildKeepWatching(allApps: List<AppEntry>) {
        keepWatchingRow.removeAllViews()
        val recents = RecentAppsTracker.getRecent(this, allApps, limit = 2)
        val visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        keepWatchingLabel.visibility = visibility
        keepWatchingRow.visibility = visibility

        for ((index, recent) in recents.withIndex()) {
            val app = recent.app
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_card_bg)
                foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_bezel)
                setPadding(dp(8), 0, dp(10), 0)
                isFocusable = true
                contentDescription = "Open ${app.label}"
                setOnClickListener { launchApp(app) }
            }

            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xFF2C2B30.toInt()) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                val banner = bannerFor(app)
                if (banner != null) setImageDrawable(banner) else setImageBitmap(TileRenderer.render(app.icon, dp(48)))
            }
            card.addView(thumb, LinearLayout.LayoutParams(dp(84), dp(48)))

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val providerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = ImageView(this).apply {
                setImageBitmap(TileRenderer.render(app.icon, dp(9)))
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            providerRow.addView(badge, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(4) })
            providerRow.addView(label(getString(R.string.recently_opened), R.font.inter_medium, 9.5f, 0x99FFFFFF.toInt()).apply {
                letterSpacing = 0.06f
            })
            text.addView(providerRow)
            text.addView(label(app.label, R.font.inter_semibold, 15f, 0xFFFFFFFF.toInt()), marginTop(4))
            text.addView(label(relativeTime(recent.openedAt), R.font.inter_regular, 10.5f, 0x9EFFFFFF.toInt()), marginTop(2))
            card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })

            addFocusScale(card, 1.03f)
            val lp = LinearLayout.LayoutParams(dp(259), dp(70))
            if (index > 0) lp.marginStart = dp(11)
            keepWatchingRow.addView(card, lp)
        }
    }

    /** TV apps ship a 16:9 banner, which fits the thumbnail slot exactly. */
    private fun bannerFor(app: AppEntry): Drawable? = try {
        packageManager.getApplicationBanner(app.packageName)
            ?: app.launchIntent?.let { packageManager.getActivityBanner(it) }
    } catch (e: Exception) {
        null
    }

    private fun relativeTime(millis: Long): String =
        if (millis <= 0L) "Recently"
        else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    // ---------- Govee device pills (top-right, only when set up) ----------

    private fun buildDeviceRow() {
        val focusedIndex = currentFocus?.takeIf { it.parent === deviceRow }?.let { deviceRow.indexOfChild(it) }
        deviceRow.removeAllViews()
        for (device in govDevices) {
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_capsule_bg)
                foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.focus_ring_capsule)
                setPadding(dp(12), 0, dp(14), 0)
                isFocusable = true
                contentDescription = "${device.name}, ${if (device.isOn) "on" else "off"}. Toggle."
                setOnClickListener { toggleDevice(device) }
            }
            val bulb = ImageView(this).apply {
                setImageResource(R.drawable.ic_lucide_bulb)
                imageTintList = ColorStateList.valueOf(if (device.isOn) 0xFFFFD27A.toInt() else 0xB3FFFFFF.toInt())
            }
            pill.addView(bulb, LinearLayout.LayoutParams(dp(15), dp(15)))
            pill.addView(label(device.name, R.font.inter_medium, 11f, 0xFFFFFFFF.toInt()).apply { isDuplicateParentStateEnabled = true },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addFocusScale(pill, 1.04f)
            deviceRow.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(10) })
        }
        focusedIndex?.let { deviceRow.getChildAt(it.coerceAtMost(deviceRow.childCount - 1))?.requestFocus() }
    }

    private fun fetchDevicesIfNeeded() {
        val key = GoveeHelper.getApiKey(this) ?: return
        if (govDevices.isNotEmpty() && key == lastFetchedApiKey) return
        GoveeHelper.listDevices(this) { devices, _ ->
            if (devices != null) {
                govDevices = devices
                lastFetchedApiKey = key
                buildDeviceRow()
            }
        }
    }

    private fun toggleDevice(device: GoveeHelper.Device, done: () -> Unit = {}) {
        val newState = !device.isOn
        GoveeHelper.setPower(this, device, newState) { success, error ->
            if (success) {
                device.isOn = newState
                buildDeviceRow()
                done()
            } else {
                GlassUi.toast(this, error ?: "Couldn’t reach Govee")
            }
        }
    }

    // ---------- Helpers ----------

    private fun launchApp(app: AppEntry) {
        val intent = app.launchIntent ?: return
        RecentAppsTracker.recordOpened(this, app.packageName)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            GlassUi.toast(this, "${app.label} couldn’t be opened")
        }
    }

    private fun label(text: String, fontRes: Int, sizeDp: Float, color: Int) = TextView(this).apply {
        this.text = text
        typeface = font(fontRes)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun marginTop(dpValue: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(dpValue) }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(this, res)

    /** Calm tvOS-style lift on D-pad focus: small grow, soft shadow, eased (no bounce). */
    private fun addFocusScale(view: View, scale: Float) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s)
                .translationZ(if (hasFocus) dp(10).toFloat() else 0f)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .setDuration(220).start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val SYSTEM_TILE = 0xFF3A383E.toInt()
        private const val NIGHT_FADE_MS = 900L
    }
}
