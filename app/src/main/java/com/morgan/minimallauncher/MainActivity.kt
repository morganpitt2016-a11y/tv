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
    private lateinit var dockLabel: TextView
    private lateinit var dockBackdrop: ImageView
    private lateinit var profilePill: View
    private var pillPlaced = false
    private lateinit var wallpaperFront: View
    private lateinit var rotator: WallpaperRotator
    private lateinit var widgets: HomeWidgets

    private val handler = Handler(Looper.getMainLooper())
    private var govDevices: List<GoveeHelper.Device> = emptyList()
    private var lastFetchedApiKey: String? = null
    private var dockPackages: List<String> = emptyList()

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
        dockLabel = findViewById(R.id.dockLabel)
        wallpaperFront = findViewById(R.id.wallpaperFront)

        setupClockFont()
        rotator = WallpaperRotator(
            this, findViewById(R.id.photoA), findViewById(R.id.photoB),
            findViewById(R.id.frontA), findViewById(R.id.frontB)
        ) { photoShowing ->
            wallpaperFront.visibility = if (photoShowing) View.GONE else View.VISIBLE
        }
        setupProfilePill()
        setupDockGlass()
        rotator.onNewPhoto = { bmp -> updateDockBackdrop(bmp) }
        dateText.letterSpacing = 0.02f
        widgets = HomeWidgets(this, findViewById(R.id.widgetLeft), findViewById(R.id.widgetRight),
            lights = { govDevices }, openLights = { openQuickSettings() })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitStage() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back from the app grid returns to the dock; otherwise home stays put.
            override fun handleOnBackPressed() {
                if (inGrid) dock.getChildAt(0)?.requestFocus()
            }
        })
        ui.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> gridFocusMoved(now) }

        for ((button, scale) in listOf(nightModeButton to 1.08f, quickSettingsButton to 1.08f, cinemaButton to 1.08f)) {
            addFocusScale(button, scale)
        }
        nightModeButton.setOnClickListener { setNightMode(!isNightMode()) }
        quickSettingsButton.setOnClickListener { openQuickSettings() }
        cinemaButton.setOnClickListener { CinemaMode.toggle(this) }
        applyDimming(animate = false)

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
        // First launch: set up the account. Start-up / waking the TV: "Who's watching?"
        if (Accounts.needsSetup(this) || (Accounts.askWho && Prefs.bool(this, "who_at_start", true))) {
            Accounts.askWho = false
            startActivity(Intent(this, WhoActivity::class.java))
            @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        Accounts.askWho = false
        RedirectDiag.launcherShown(this)
        AppWatchService.startIfNeeded(this)
        RemoteService.startIfEnabled(this)
        SelfEnable.enableAll(this)
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
        buildAppGrid(allApps)
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
        val time = DateFormat.format(pattern, now).toString()
        if (clockText.text.toString() != time) {
            if (clockText.text.isNullOrEmpty() || clockText.text.toString() == "8:47") clockText.text = time
            else {
                // A soft dissolve as the minute turns over, rather than a hard jump.
                clockText.animate().alpha(0.15f).setDuration(260).setInterpolator(Motion.standard).withEndAction {
                    clockText.text = time
                    clockText.animate().alpha(if (inGrid) 0f else 1f).setDuration(420).setInterpolator(Motion.standard).start()
                }.start()
            }
        }
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
        // The thin cinema digits change the clock's width: measure it again so no digit is cut off.
        clockText.text = clockText.text
        clockText.requestLayout()
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
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.segment_bg)
                tag = if (isActive) "active" else null
                isFocusable = true
                isClickable = true
                contentDescription = if (isActive) "$name, selected profile" else "Switch to $name"
                setOnClickListener {
                    if (name == ProfileManager.getActiveProfile(this@MainActivity)) return@setOnClickListener
                    ProfileManager.setActiveProfile(this@MainActivity, name)
                    rotator.reload()
                    refreshAll(focusActiveProfile = true)
                    // The new person's dock and Continue Watching settle in.
                    for (v in listOf<View>(dock, keepWatchingRow, keepWatchingLabel)) {
                        v.alpha = 0f
                        v.animate().alpha(1f).setDuration(Motion.PANEL).setInterpolator(Motion.standard).start()
                    }
                }
            }
            addFocusScale(seg, 1.04f)
            profileSwitch.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(21)))
        }
        profileSwitch.post { movePill(animate = pillPlaced) }
    }

    /** The profile selector: a glass capsule with one white pill that slides to the active person. */
    private fun setupProfilePill() {
        val parent = profileSwitch.parent as ViewGroup
        val index = parent.indexOfChild(profileSwitch)
        val lp = profileSwitch.layoutParams
        parent.removeView(profileSwitch)
        val box = FrameLayout(this).apply {
            background = profileSwitch.background
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        profileSwitch.background = null
        profileSwitch.setPadding(0, 0, 0, 0)
        profilePill = View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.segment_active_bg)
            alpha = 0f
        }
        box.addView(profilePill, FrameLayout.LayoutParams(0, dp(21)))
        box.addView(profileSwitch, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(21)))
        parent.addView(box, index, lp)
    }

    private fun movePill(animate: Boolean) {
        val seg = (0 until profileSwitch.childCount).map { profileSwitch.getChildAt(it) }.firstOrNull { it.tag == "active" } ?: return
        if (seg.width == 0) return
        val toX = seg.left.toFloat()
        val toW = seg.width
        if (!animate) {
            profilePill.translationX = toX
            profilePill.layoutParams = profilePill.layoutParams.apply { width = toW }
            profilePill.alpha = 1f
            pillPlaced = true
            return
        }
        val fromX = profilePill.translationX
        val fromW = profilePill.width
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.PANEL
            interpolator = Motion.focusEase
            addUpdateListener {
                val f = it.animatedValue as Float
                profilePill.translationX = fromX + (toX - fromX) * f
                profilePill.layoutParams = profilePill.layoutParams.apply { width = (fromW + (toW - fromW) * f).toInt() }
            }
            start()
        }
    }

    // ---------- Dock glass ----------

    /** A real blur of the wallpaper behind the dock (Android 12+), under its light glass tint. */
    private fun setupDockGlass() {
        val lp = FrameLayout.LayoutParams(dock.layoutParams as FrameLayout.LayoutParams)
        dockBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
                }
            }
            clipToOutline = true
            alpha = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(dp(16).toFloat(), dp(16).toFloat(), Shader.TileMode.CLAMP))
            }
        }
        ui.addView(dockBackdrop, ui.indexOfChild(dock), lp)
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dockBackdrop.post { alignDockBackdrop() } }
    }

    private var backdropBitmap: android.graphics.Bitmap? = null

    private fun updateDockBackdrop(full: android.graphics.Bitmap) {
        if (Build.VERSION.SDK_INT < 31) return
        val w = 480
        backdropBitmap = android.graphics.Bitmap.createScaledBitmap(full, w, (w.toFloat() * full.height / full.width).toInt().coerceAtLeast(1), true)
        dockBackdrop.post {
            alignDockBackdrop()
            dockBackdrop.animate().alpha(1f).setDuration(1200).setInterpolator(Motion.standard).start()
        }
    }

    /** Keep the blurred glass the same size as the dock and lined up with the wallpaper behind it. */
    private fun alignDockBackdrop() {
        val lp = dockBackdrop.layoutParams as FrameLayout.LayoutParams
        if (dock.width > 0 && (lp.width != dock.width || lp.height != dock.height)) {
            lp.width = dock.width; lp.height = dock.height
            dockBackdrop.layoutParams = lp
            dockBackdrop.post { alignDockBackdrop() }
            return
        }
        val small = backdropBitmap ?: return
        val sw = stage.width.toFloat(); val sh = stage.height.toFloat()
        if (sw == 0f) return
        val s = maxOf(sw / small.width, sh / small.height)
        val m = android.graphics.Matrix()
        m.setScale(s, s)
        m.postTranslate((sw - small.width * s) / 2f - dock.left, (sh - small.height * s) / 2f - dock.top)
        dockBackdrop.imageMatrix = m
        if (dockBackdrop.drawable == null || (dockBackdrop.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== small) dockBackdrop.setImageBitmap(small)
    }

    // ---------- App dock ----------

    /**
     * The active profile's apps as wide tvOS-style tiles, then Watch and
     * Settings. The dock is only as wide as its tiles, so a few apps make
     * a small dock.
     */
    private fun buildDock(allApps: List<AppEntry>) {
        dock.removeAllViews()
        val order = ProfileManager.getDockOrder(this)
        val byPkg = allApps.associateBy { it.packageName }
        val apps = (if (order.isEmpty()) allApps.take(6) else order.mapNotNull { byPkg[it] }).take(9)
        dockPackages = apps.map { it.packageName }

        // Tiles shrink only when there are too many to fit the screen.
        val count = apps.size + 2
        val maxWidth = 900 - 28 - DOCK_GAP * (count - 1)
        dockTileW = minOf(92, maxWidth / count)
        dockTileH = (dockTileW / AppTiles.RATIO).toInt()
        val w = dp(dockTileW); val h = dp(dockTileH)

        for (app in apps) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(AppTiles.forApp(this@MainActivity, app, w, h))
            }
            val tile = addDockTile(image, 0, "Open ${app.label}") { v -> AppActions.launch(this, app, v) }
            tile.isLongClickable = true
            tile.setOnLongClickListener {
                AppActions.menu(this, app, tile, dockPackages) { refreshAll() }
                true
            }
        }
        addDockTile(systemTile(R.drawable.ic_lucide_play, "Watch"), 0xFF000000.toInt(), "Watch: films and shows on all your apps") {
            startActivity(Intent(this, WatchActivity::class.java))
        }
        addDockTile(glyph(R.drawable.ic_lucide_settings, (dockTileH * 0.42f).toInt()), 0, getString(R.string.settings), SETTINGS_GRADIENT) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private var dockTileW = 92
    private var dockTileH = 55

    private fun glyph(res: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
        val padV = (dp(dockTileH) - dp(sizeDp)) / 2
        val padH = (dp(dockTileW) - dp(sizeDp)) / 2
        setPadding(padH, padV, padH, padV)
    }

    /** Glyph and name, like the Apple TV app's own tile. */
    private fun systemTile(res: Int, name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val size = (dockTileH * 0.3f).toInt()
        addView(ImageView(this@MainActivity).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(size), dp(size)))
        addView(label(name, R.font.inter_semibold, dockTileH * 0.25f, 0xFFFFFFFF.toInt()).apply { setShadowLayer(0f, 0f, 0f, 0) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
    }

    private fun addDockTile(content: View, background: Int, label: String, gradient: IntArray? = null, onClick: (View) -> Unit): View {
        val tile = FrameLayout(this).apply {
            this.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (gradient != null) { colors = gradient; orientation = GradientDrawable.Orientation.TOP_BOTTOM } else setColor(background)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_light)
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick(it) }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val name = label.removePrefix("Open ").substringBefore(":")
        tile.setOnFocusChangeListener { v, has ->
            Motion.focus(v, has, 1.12f, 12f)
            showDockLabel(if (has) v else null, name)
        }
        val lp = LinearLayout.LayoutParams(dp(dockTileW), dp(dockTileH))
        if (dock.childCount > 0) lp.marginStart = dp(DOCK_GAP)
        dock.addView(tile, lp)
        return tile
    }

    /** The focused app's name, centred under its icon; fades out when focus leaves the dock. */
    private fun showDockLabel(tile: View?, name: String) {
        if (tile == null) {
            dockLabel.animate().alpha(0f).setDuration(Motion.FADE).start()
            return
        }
        dockLabel.text = name
        dockLabel.post {
            val centre = dock.left + tile.left + tile.width / 2f
            dockLabel.translationX = centre - dockLabel.width / 2f
            dockLabel.animate().alpha(1f).setInterpolator(Motion.standard).setDuration(Motion.FADE).start()
        }
    }

    // ---------- App grid (below the dock, like tvOS) ----------

    private val gridExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var gridKey = ""
    private var inGrid = false
    private var storeLogos: Map<String, String>? = null

    /** Every installed app, then the popular TV apps you don't have yet (choosing one offers the download). */
    private fun buildAppGrid(allApps: List<AppEntry>) {
        val installed = allApps.map { it.packageName }.toSet()
        val missing = AppTiles.STORE.filter { AppTiles.installedPackage(installed, it) == null }
        val key = allApps.joinToString(",") { it.packageName }
        if (key == gridKey && rails.childCount > 0) return
        gridKey = key
        rails.removeAllViews()
        val items: List<Any> = allApps + missing
        val w = dp(GRID_W); val h = dp(GRID_H)
        val side = dp((960 - GRID_COLS * GRID_W - (GRID_COLS - 1) * GRID_GAP) / 2)
        rails.setPadding(side, 0, side, dp(60))
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % GRID_COLS == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                rails.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(4) })
            }
            row?.addView(gridCell(item, w, h), LinearLayout.LayoutParams(w, -2).apply { if (i % GRID_COLS > 0) marginStart = dp(GRID_GAP) })
        }
        // Left/Right stop at the ends of each grid row instead of wrapping to another row.
        for (r in 0 until rails.childCount) {
            val rowView = rails.getChildAt(r) as? LinearLayout ?: continue
            val first = (rowView.getChildAt(0) as? ViewGroup)?.getChildAt(0)
            val last = (rowView.getChildAt(rowView.childCount - 1) as? ViewGroup)?.getChildAt(0)
            first?.let { it.id = View.generateViewId(); it.nextFocusLeftId = it.id }
            last?.let { it.id = View.generateViewId(); it.nextFocusRightId = it.id }
        }
    }

    private fun gridCell(item: Any, w: Int, h: Int): View {
        val name = when (item) { is AppEntry -> item.label; is AppTiles.StoreApp -> item.name; else -> "" }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val tile = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF1C1C1E.toInt()) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.tile_light)
            isFocusable = true
            isClickable = true
            contentDescription = if (item is AppTiles.StoreApp) "Get $name" else "Open $name"
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        val caption = label(name, R.font.inter_medium, 11f, 0xF2FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(tile, LinearLayout.LayoutParams(w, h))
            addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }
        tile.setOnFocusChangeListener { v, has ->
            Motion.focus(v, has, 1.1f, 12f)
            caption.animate().alpha(if (has) 1f else 0f).setDuration(Motion.FADE).setInterpolator(Motion.standard).start()
        }
        when (item) {
            is AppEntry -> {
                tile.setOnClickListener { AppActions.launch(this, item, it) }
                tile.isLongClickable = true
                tile.setOnLongClickListener { AppActions.menu(this, item, tile, dockPackages) { refreshAll() }; true }
            }
            is AppTiles.StoreApp -> {
                tile.setOnClickListener { AppTiles.offerDownload(this, item) }
                image.alpha = 0.92f
            }
        }
        // Artwork is drawn off the main thread so the home screen never stutters.
        val app = applicationContext
        gridExec.execute(Runnable {
            val bmp = try {
                when (item) {
                    is AppEntry -> AppTiles.forApp(app, item, w, h)
                    is AppTiles.StoreApp -> AppTiles.forStore(app, item, storeLogo(item), w, h)
                    else -> null
                }
            } catch (e: Throwable) { null }
            if (bmp != null) handler.post { image.setImageBitmap(bmp) }
        })
        return cell
    }

    /** The service's own logo from JustWatch, when there is one (runs on the grid thread). */
    private fun storeLogo(app: AppTiles.StoreApp): android.graphics.Bitmap? {
        val service = app.service ?: return null
        val logos = storeLogos ?: (try {
            WatchCatalog.providers(this).mapNotNull { p -> p.icon?.let { p.technical to it } }.toMap()
        } catch (e: Exception) { emptyMap() }).also { storeLogos = it }
        val url = logos[service] ?: return null
        return try { ImageLoader.fetch(applicationContext, url, ImageLoader.ICON) } catch (e: Exception) { null }
    }

    /** Moving into the grid glides the home screen up; moving back to the dock brings it down again. */
    private fun gridFocusMoved(now: View?) {
        var v: View? = now
        var gridRow: View? = null
        while (v != null && v !== ui) {
            val parent = v.parent as? View
            if (parent === rails) { gridRow = v; break }
            v = parent
        }
        val nowInGrid = gridRow != null
        if (nowInGrid) {
            val index = rails.indexOfChild(gridRow)
            val rowTop = rails.top + gridRow!!.top
            val target = -(rowTop - dp(if (index == 0) 165 else 200)).toFloat()
            ui.animate().translationY(target).setDuration(450).setInterpolator(Motion.standard).start()
        }
        if (nowInGrid == inGrid) return
        inGrid = nowInGrid
        if (!nowInGrid) ui.animate().translationY(0f).setDuration(450).setInterpolator(Motion.standard).start()
        val fade = if (nowInGrid) 0f else 1f
        clockText.animate().alpha(fade).setDuration(Motion.PANEL).setInterpolator(Motion.standard).start()
        railScrim.animate().alpha(1f - fade).setDuration(Motion.PANEL).setInterpolator(Motion.standard).start()
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
            // What we legitimately know: the title you opened from Watch in this app, and
            // what the app itself reports playing (title + real progress), if it shares that.
            val opened = WatchCatalog.lastForApp(this, app.packageName)?.takeIf { (_, at) -> at >= recent.openedAt - 120_000L }?.first
            val playing = NowPlaying.forPackage(this, app.packageName)
            val title = playing?.title ?: opened?.name ?: app.label
            val subtitle = listOfNotNull(
                if (title != app.label) app.label else null,
                playing?.artist?.takeIf { it.isNotBlank() && it != title },
                relativeTime(recent.openedAt).takeIf { playing == null },
            ).joinToString(" · ")

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isLongClickable = true
                clipChildren = false
                contentDescription = "$title, ${app.label}"
            }
            val frame = FrameLayout(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2C2B30.toInt()) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            when {
                opened?.backdrop != null -> ImageLoader.load(thumb, opened.backdrop, ImageLoader.CARD)
                playing?.art != null -> thumb.setImageBitmap(playing.art)
                else -> {
                    val banner = bannerFor(app)
                    if (banner != null) thumb.setImageDrawable(banner) else {
                        val pad = dp(12)
                        thumb.setPadding(pad, pad, pad, pad)
                        thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                        thumb.setImageBitmap(TileRenderer.forApp(app, dp(40)))
                    }
                }
            }
            // Which service it's on: the app's icon in the corner of the artwork.
            if (opened != null || playing != null) {
                frame.addView(ImageView(this).apply {
                    setImageBitmap(TileRenderer.forApp(app, dp(16)))
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0) }
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(6)) })
            }
            // Real progress only, when the app reports position and length.
            if (playing != null && playing.duration > 0) {
                frame.addView(View(this).apply { setBackgroundColor(0x40FFFFFF) }, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
                frame.addView(View(this).apply { setBackgroundColor(0xF2FFFFFF.toInt()) },
                    FrameLayout.LayoutParams((dp(100) * playing.progress).toInt(), dp(3), Gravity.BOTTOM))
            }
            item.addView(frame, LinearLayout.LayoutParams(dp(100), dp(56)))
            item.setOnClickListener { AppActions.launch(this, app, frame) }
            item.setOnLongClickListener { AppActions.menu(this, app, frame, dockPackages) { refreshAll() }; true }

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(title, R.font.inter_semibold, 14f, 0xFFFFFFFF.toInt()))
            text.addView(label(subtitle, R.font.inter_medium, 11f, 0xB3FFFFFF.toInt()), marginTop(3))
            item.addView(text, LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })

            item.setOnFocusChangeListener { _, has -> Motion.focus(frame, has, 1.1f, 10f) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(64))
            if (index > 0) lp.marginStart = dp(18)
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
                imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(if (device.isOn) 0xFFE0A100.toInt() else GlassUi.INK, if (device.isOn) 0xFFFFD27A.toInt() else 0xB3FFFFFF.toInt()))
                isDuplicateParentStateEnabled = true
            }
            pill.addView(bulb, LinearLayout.LayoutParams(dp(15), dp(15)))
            pill.addView(label(device.name, R.font.inter_medium, 11f, 0xFFFFFFFF.toInt()).apply {
                setTextColor(GlassUi.primaryText(GlassUi.INK, 0xFFFFFFFF.toInt()))
                isDuplicateParentStateEnabled = true
                setShadowLayer(0f, 0f, 0f, 0)
            },
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
        view.setOnFocusChangeListener { v, hasFocus -> Motion.focus(v, hasFocus, scale, if (spotlight) 12f else 8f) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val DOCK_GAP = 12
        private const val GRID_COLS = 6
        private const val GRID_W = 124
        private const val GRID_H = 74
        private const val GRID_GAP = 20
        private val SETTINGS_GRADIENT = intArrayOf(0xFF9A9AA0.toInt(), 0xFF6E6E73.toInt())
        private const val NIGHT_FADE_MS = 900L
        private const val AMBIENT_STEP_MS = 60_000L
    }
}
