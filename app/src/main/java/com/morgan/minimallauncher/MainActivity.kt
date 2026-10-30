package com.morgan.minimallauncher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var scenery: View
    private lateinit var ui: FrameLayout
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var profileSwitch: LinearLayout
    private lateinit var deviceRow: LinearLayout
    private lateinit var nightModeButton: View
    private lateinit var quickSettingsButton: View
    private lateinit var cinemaButton: View
    private lateinit var nightOverlay: View
    private lateinit var ambientShade: View
    private lateinit var railScrim: View
    private lateinit var idleDim: View
    private lateinit var keepWatchingLabel: TextView
    private lateinit var keepWatchingRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var rails: LinearLayout
    private lateinit var wallpaperFront: View
    private lateinit var rotator: WallpaperRotator
    private lateinit var widgets: HomeWidgets

    private val handler = Handler(Looper.getMainLooper())
    private var govDevices: List<GoveeHelper.Device> = emptyList()
    private var lastFetchedApiKey: String? = null
    private var dockPackages: List<String> = emptyList()
    private var railsShown = false
    private var railOffset = 0f

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        stage = findViewById(R.id.stage)
        scenery = findViewById(R.id.scenery)
        ui = findViewById(R.id.ui)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        profileSwitch = findViewById(R.id.profileSwitch)
        deviceRow = findViewById(R.id.deviceRow)
        nightModeButton = findViewById(R.id.nightModeButton)
        quickSettingsButton = findViewById(R.id.quickSettingsButton)
        cinemaButton = findViewById(R.id.cinemaButton)
        nightOverlay = findViewById(R.id.nightOverlay)
        ambientShade = findViewById(R.id.ambientShade)
        railScrim = findViewById(R.id.railScrim)
        idleDim = findViewById(R.id.idleDim)
        keepWatchingLabel = findViewById(R.id.keepWatchingLabel)
        keepWatchingRow = findViewById(R.id.keepWatchingRow)
        dock = findViewById(R.id.dock)
        rails = findViewById(R.id.rails)
        wallpaperFront = findViewById(R.id.wallpaperFront)

        setupClockFont()
        rotator = WallpaperRotator(
            this, findViewById(R.id.photoA), findViewById(R.id.photoB),
            findViewById(R.id.frontA), findViewById(R.id.frontB)
        ) { photoShowing ->
            wallpaperFront.visibility = if (photoShowing) View.GONE else View.VISIBLE
        }
        widgets = HomeWidgets(this, findViewById(R.id.widgetLeft), findViewById(R.id.widgetRight),
            lights = { govDevices }, openLights = { openQuickSettings() })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitStage() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back from the rails returns to the top; otherwise home has nowhere to go.
                if (railsShown) {
                    showRails(null)
                    dock.getChildAt(0)?.requestFocus()
                }
            }
        })

        for ((button, scale) in listOf(nightModeButton to 1.08f, quickSettingsButton to 1.08f, cinemaButton to 1.08f)) {
            addFocusScale(button, scale)
        }
        nightModeButton.setOnClickListener { setNightMode(!isNightMode()) }
        quickSettingsButton.setOnClickListener { openQuickSettings() }
        cinemaButton.setOnClickListener { CinemaMode.toggle(this) }
        applyDimming(animate = false)

        // Moving into the rails glides the whole screen up; leaving them glides back.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            if (new == null) return@addOnGlobalFocusChangeListener
            showRails(if (isInside(new, rails)) new else null)
        }
        UiSounds.attach(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Cinema" from the iPhone remote.
        if (intent.getBooleanExtra(EXTRA_CINEMA, false) && !Prefs.cinemaOn(this)) {
            handler.postDelayed({ CinemaMode.toggle(this) }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        RedirectDiag.launcherShown(this)
        AppWatchService.startIfNeeded(this)
        RemoteService.startIfEnabled(this)
        handler.post(clockTick)
        rotator.start()
        widgets.start()
        refreshAll()
        applyDimming(animate = false)
        handler.post(ambientTick)
        wake()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(ambientTick)
        handler.removeCallbacks(startDimming)
        handler.removeCallbacks(startIdleScreen)
        rotator.stop()
        widgets.stop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val wasDim = idleDim.visibility == View.VISIBLE && idleDim.alpha > 0.08f
            wake()
            // The first press only wakes a dimmed screen (like tvOS).
            if (wasDim) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Rebuilds every row. Rebuilding removes the focused button, which
     * would make Android jump focus somewhere else, so remember which
     * row/position had focus and put it back afterwards.
     */
    private fun refreshAll(focusActiveProfile: Boolean = false) {
        val saved = savedFocus()
        val allApps = AppListHelper.loadLaunchableTvApps(this)
        buildProfileSwitch()
        buildDock(allApps)
        buildKeepWatching(allApps)
        buildRails(allApps)
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
     * flat-sided numerals.
     */
    private fun setupClockFont() {
        clockText.typeface = font(R.font.roboto_flex)
        clockText.setFontVariationSettings("'wdth' 72, 'wght' 480, 'opsz' 144, 'YTFI' 788")
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        clockText.text = DateFormat.format(pattern, now)
        dateText.text = DateFormat.format("EEEE d MMMM", now)
    }

    // ---------- Night mode, cinema mode, ambient lighting, idle ----------

    fun isNightMode() = getPreferences(MODE_PRIVATE).getBoolean("night", false)

    fun setNightMode(on: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("night", on).apply()
        applyDimming(animate = true)
    }

    /** Night mode and cinema mode both darken the scene, fading in and out. */
    fun applyDimming(animate: Boolean) {
        val cinema = Prefs.cinemaOn(this)
        nightModeButton.isActivated = isNightMode()
        cinemaButton.isActivated = cinema
        // Cinema mode: a thin, warm, cinema-style clock.
        clockText.setFontVariationSettings(if (cinema) "'wdth' 60, 'wght' 170, 'opsz' 144, 'YTFI' 788" else "'wdth' 72, 'wght' 480, 'opsz' 144, 'YTFI' 788")
        clockText.setTextColor(if (cinema) 0xE6F2E6D0.toInt() else 0xEDFFFFFF.toInt())
        val target = when {
            cinema -> 0.72f
            isNightMode() -> 0.55f
            else -> 0f
        }
        nightOverlay.animate().cancel()
        if (!animate) {
            nightOverlay.alpha = target
            nightOverlay.visibility = if (target > 0f) View.VISIBLE else View.GONE
            return
        }
        nightOverlay.visibility = View.VISIBLE
        nightOverlay.animate().alpha(target).setDuration(NIGHT_FADE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (target == 0f) nightOverlay.visibility = View.GONE }
            .start()
    }

    /**
     * Ambient lighting: the wallpaper is a touch darker late in the
     * evening and at night, and very slowly "breathes" so the scene feels
     * alive without being noticeable.
     */
    private val ambientTick = object : Runnable {
        override fun run() {
            val target = if (!Prefs.ambientLight(this@MainActivity)) 0f else {
                val cal = Calendar.getInstance()
                val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
                val base = when {
                    h < 6f -> 0.24f
                    h < 8f -> 0.24f - (h - 6f) * 0.11f
                    h < 18f -> 0.02f
                    h < 22f -> 0.02f + (h - 18f) * 0.055f
                    else -> 0.24f
                }
                val breathe = 0.025f * sin(2 * PI * (System.currentTimeMillis() % 120_000) / 120_000.0).toFloat()
                (base + breathe).coerceIn(0f, 0.3f)
            }
            ambientShade.animate().alpha(target).setDuration(AMBIENT_STEP_MS).setInterpolator(LinearInterpolator()).start()
            handler.postDelayed(this, AMBIENT_STEP_MS)
        }
    }

    private val startDimming = Runnable {
        idleDim.visibility = View.VISIBLE
        idleDim.animate().alpha(0.6f).setDuration(25_000).setInterpolator(LinearInterpolator()).start()
    }

    private val startIdleScreen = Runnable {
        AmbientActivity.start(this, Prefs.idleMode(this))
    }

    /** Any remote press: undim and restart the idle timers. */
    private fun wake() {
        handler.removeCallbacks(startDimming)
        handler.removeCallbacks(startIdleScreen)
        if (idleDim.visibility == View.VISIBLE) {
            idleDim.animate().cancel()
            idleDim.animate().alpha(0f).setDuration(350).withEndAction { idleDim.visibility = View.GONE }.start()
        }
        Prefs.dimAfter(this).takeIf { it > 0 }?.let { handler.postDelayed(startDimming, it * 60_000L) }
        Prefs.idleAfter(this).takeIf { it > 0 }?.let { handler.postDelayed(startIdleScreen, it * 60_000L) }
    }

    // ---------- Quick settings ----------

    fun openQuickSettings() {
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
                isClickable = true
                contentDescription = if (isActive) "$name, selected profile" else "Switch to $name"
                setOnClickListener {
                    if (name == ProfileManager.getActiveProfile(this@MainActivity)) return@setOnClickListener
                    ProfileManager.setActiveProfile(this@MainActivity, name)
                    rotator.reload()
                    refreshAll(focusActiveProfile = true)
                }
            }
            addFocusScale(seg, 1.04f)
            profileSwitch.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(21)))
        }
    }

    // ---------- App dock ----------

    /** Up to 10 of the active profile's apps in their chosen order, then Settings and More. */
    private fun buildDock(allApps: List<AppEntry>) {
        dock.removeAllViews()
        val order = ProfileManager.getDockOrder(this)
        val byPkg = allApps.associateBy { it.packageName }
        val apps = (if (order.isEmpty()) allApps else order.mapNotNull { byPkg[it] }).take(10)
        dockPackages = apps.map { it.packageName }

        val tileSize = dp(56)
        for (app in apps) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(TileRenderer.render(app.icon, tileSize))
            }
            val tile = addDockTile(image, 0, "Open ${app.label}") { v -> AppActions.launch(this, app, v) }
            tile.isLongClickable = true
            tile.setOnLongClickListener {
                AppActions.menu(this, app, tile, dockPackages) { refreshAll() }
                true
            }
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

    private fun addDockTile(content: View, background: Int, label: String, onClick: (View) -> Unit): View {
        val tile = FrameLayout(this).apply {
            this.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(background)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_bezel)
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick(it) }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addFocusScale(tile, 1.09f, spotlight = true)
        val lp = LinearLayout.LayoutParams(dp(56), dp(56))
        if (dock.childCount > 0) lp.marginStart = dp(13)
        dock.addView(tile, lp)
        return tile
    }

    // ---------- Continue watching (artwork + text, no cards) ----------

    private fun buildKeepWatching(allApps: List<AppEntry>) {
        keepWatchingRow.removeAllViews()
        val recents = if (Prefs.widget(this, Prefs.W_RECENT)) RecentAppsTracker.getRecent(this, allApps, limit = 3) else emptyList()
        val visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        keepWatchingLabel.visibility = visibility
        keepWatchingRow.visibility = visibility
        keepWatchingLabel.text = "Continue Watching"

        for ((index, recent) in recents.withIndex()) {
            val app = recent.app
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isLongClickable = true
                clipChildren = false
                contentDescription = "Open ${app.label}"
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2C2B30.toInt()) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_bezel)
                isDuplicateParentStateEnabled = true
                val banner = bannerFor(app)
                if (banner != null) setImageDrawable(banner) else {
                    val pad = dp(12)
                    setPadding(pad, pad, pad, pad)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(TileRenderer.render(app.icon, dp(40)))
                }
            }
            item.addView(thumb, LinearLayout.LayoutParams(dp(100), dp(56)))
            item.setOnClickListener { AppActions.launch(this, app, thumb) }
            item.setOnLongClickListener { AppActions.menu(this, app, thumb, dockPackages) { refreshAll() }; true }

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(app.label, R.font.inter_semibold, 14f, 0xFFFFFFFF.toInt()))
            text.addView(label(relativeTime(recent.openedAt), R.font.inter_medium, 11f, 0xB3FFFFFF.toInt()), marginTop(3))
            item.addView(text, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })

            item.setOnFocusChangeListener { _, has ->
                val ease = DecelerateInterpolator(2f)
                thumb.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f)
                    .translationZ(if (has) dp(10).toFloat() else 0f).setInterpolator(ease).setDuration(220).start()
                GlassUi.spotlight(thumb, has)
                text.animate().translationX(if (has) dp(4).toFloat() else 0f).setInterpolator(ease).setDuration(220).start()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(64))
            if (index > 0) lp.marginStart = dp(22)
            keepWatchingRow.addView(item, lp)
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

    // ---------- Rails below the dock ----------

    private fun buildRails(allApps: List<AppEntry>) {
        val byPkg = allApps.associateBy { it.packageName }
        val profile = ProfileManager.getActiveProfile(this)
        val favourites = ProfileManager.getFavourites(this).mapNotNull { byPkg[it] }
        val recent = RecentAppsTracker.getRecent(this, allApps, limit = 12).map { it.app }
        val recommended = RecentAppsTracker.recommended(this, allApps, exclude = favourites.map { it.packageName }.toSet(), limit = 12)
        val list = mutableListOf<Pair<String, List<AppEntry>>>()
        list += "Favourites" to favourites
        list += "Recommended for $profile" to recommended
        list += "Recently Watched" to recent
        list += "All Apps" to allApps
        Rails.build(this, rails, list,
            onOpen = { app, v -> AppActions.launch(this, app, v) },
            onMenu = { app, v -> AppActions.menu(this, app, v, dockPackages) { refreshAll() } })
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: Any? = v.parent
        while (p is View) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    /**
     * Cinematic scroll: with focus in a rail ([focused]), the controls,
     * clock and rails glide up so that rail sits near the top, and the
     * wallpaper blurs and darkens behind them. Null glides back.
     */
    private fun showRails(focused: View?) {
        val target = if (focused == null) 0f else {
            var rv: View = focused
            while (rv !is RecyclerView && rv.parent is View) rv = rv.parent as View
            -((rails.top + rv.top) - dp(150)).toFloat()
        }
        val shown = focused != null
        if (target == railOffset && shown == railsShown) return
        railOffset = target
        val ease = DecelerateInterpolator(2.2f)
        ui.animate().translationY(target).setDuration(480).setInterpolator(ease).start()
        clockText.animate().translationY(target * 0.6f).alpha(if (shown) 0f else 1f).setDuration(480).setInterpolator(ease).start()
        if (shown != railsShown) {
            railsShown = shown
            railScrim.animate().alpha(if (shown) 1f else 0f).setDuration(480).start()
            if (Build.VERSION.SDK_INT >= 31) {
                val from = if (shown) 0.1f else 22f
                val to = if (shown) 22f else 0.1f
                android.animation.ValueAnimator.ofFloat(from, to).apply {
                    duration = 480
                    addUpdateListener {
                        val r = it.animatedValue as Float
                        scenery.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                    }
                    start()
                }
            }
        }
    }

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
                isClickable = true
                contentDescription = "${device.name}, ${if (device.isOn) "on" else "off"}. Toggle."
                setOnClickListener { toggleDevice(device) }
            }
            val bulb = ImageView(this).apply {
                setImageResource(R.drawable.ic_lucide_bulb)
                imageTintList = ColorStateList.valueOf(if (device.isOn) 0xFFFFD27A.toInt() else 0xB3FFFFFF.toInt())
            }
            pill.addView(bulb, LinearLayout.LayoutParams(dp(15), dp(15)))
            pill.addView(label(device.name, R.font.inter_medium, 11f, 0xFFFFFFFF.toInt()),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addFocusScale(pill, 1.04f)
            deviceRow.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(10) })
        }
        focusedIndex?.let { deviceRow.getChildAt(it.coerceAtMost(deviceRow.childCount - 1))?.requestFocus() }
        widgets.refreshLights()
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

    fun lights() = govDevices

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

    private fun label(text: String, fontRes: Int, sizeDp: Float, color: Int) = TextView(this).apply {
        this.text = text
        typeface = font(fontRes)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x59000000)
    }

    private fun marginTop(dpValue: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(dpValue) }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(this, res)

    /** Calm tvOS-style lift on D-pad focus: small grow, soft shadow (or glow), eased. */
    private fun addFocusScale(view: View, scale: Float, spotlight: Boolean = false) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            if (spotlight) GlassUi.spotlight(v, hasFocus)
            v.animate().scaleX(s).scaleY(s)
                .translationZ(if (hasFocus) dp(10).toFloat() else 0f)
                .setInterpolator(DecelerateInterpolator(2f))
                .setDuration(220).start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val SYSTEM_TILE = 0xFF3A383E.toInt()
        private const val NIGHT_FADE_MS = 900L
        private const val AMBIENT_STEP_MS = 60_000L
    }
}
