package com.morgan.minimallauncher

import android.app.ActivityManager
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Google-TV-style quick settings: a glass panel that slides in from the
 * right with status pills, the last app you used, shortcut tiles (Wi-Fi,
 * sound, screensaver, wallpaper, night mode, Claude, Govee lights), round
 * shortcut buttons and a memory/storage line. Back closes it.
 */
class QuickSettings(private val activity: AppCompatActivity, private val host: Host) {

    interface Host {
        fun isNightMode(): Boolean
        fun setNightMode(on: Boolean)
        fun nextWallpaper()
        fun lights(): List<GoveeHelper.Device>
        fun toggleLight(device: GoveeHelper.Device, done: () -> Unit)
    }

    private val c: Context = activity
    private lateinit var dialog: Dialog
    private lateinit var panel: LinearLayout

    fun show() {
        dialog = Dialog(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(c).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00000000, 0x59000000, 0xA6000000.toInt())
            )
            alpha = 0f
        }
        panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(c, 28f).toFloat(); setColor(0xD9161619.toInt()) },
                GlassUi.panel(c, 28f, 0x14)
            ))
            setPadding(dp(c, 20f), dp(c, 22f), dp(c, 20f), dp(c, 18f))
            elevation = dp(c, 30f).toFloat()
        }
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(panel)
        }
        root.addView(scroll, FrameLayout.LayoutParams(dp(c, 372f), -1, Gravity.END).apply {
            setMargins(0, dp(c, 18f), dp(c, 18f), dp(c, 18f))
        })
        build()

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        UiSounds.attach(dialog)
        GlassUi.blurBehind(activity, true)
        dialog.setOnDismissListener { GlassUi.blurBehind(activity, false) }

        scroll.translationX = dp(c, 120f).toFloat()
        val ease = DecelerateInterpolator(2.2f)
        root.animate().alpha(1f).setDuration(260).setInterpolator(ease).start()
        scroll.animate().translationX(0f).setDuration(320).setInterpolator(ease).start()
        firstFocus?.requestFocus()
    }

    private var firstFocus: View? = null

    private fun build() {
        panel.removeAllViews()

        panel.addView(GlassUi.text(c, "Control Centre", 17f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(c, 12f) })

        // ---- Top: status pills + settings ----
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill(R.drawable.ic_lucide_sparkle, ProfileManager.getActiveProfile(c)) { open(Intent(c, SettingsActivity::class.java), inApp = true) },
            LinearLayout.LayoutParams(-2, dp(c, 34f)))
        top.addView(pill(if (online()) R.drawable.ic_lucide_wifi else R.drawable.ic_lucide_wifi_off, networkName()) { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            LinearLayout.LayoutParams(0, dp(c, 34f), 1f).apply { marginStart = dp(c, 8f) })
        top.addView(round(R.drawable.ic_lucide_settings, "All TV settings") { openSettings(Settings.ACTION_SETTINGS) },
            LinearLayout.LayoutParams(dp(c, 34f), dp(c, 34f)).apply { marginStart = dp(c, 8f) })
        panel.addView(top, LinearLayout.LayoutParams(-1, dp(c, 34f)))

        // ---- Last app (like the media card) ----
        val recent = try {
            RecentAppsTracker.getRecent(c, AppListHelper.loadLaunchableTvApps(c), limit = 1).firstOrNull()
        } catch (e: Exception) { null }
        if (recent != null) panel.addView(recentCard(recent.app), LinearLayout.LayoutParams(-1, dp(c, 78f)).apply { topMargin = dp(c, 14f) })

        // ---- Tiles ----
        val tiles = mutableListOf<View>()
        tiles += tile(if (online()) R.drawable.ic_lucide_wifi else R.drawable.ic_lucide_wifi_off, "Wi-Fi", networkName()) {
            openSettings(Settings.ACTION_WIFI_SETTINGS)
        }
        tiles += tile(R.drawable.ic_lucide_volume, "Sound", soundSummary()) { openSettings(Settings.ACTION_SOUND_SETTINGS) }
        tiles += tile(R.drawable.ic_lucide_mountain, "Aerial", "Start now") {
            dialog.dismiss(); AmbientActivity.start(activity, AmbientActivity.AERIAL)
        }
        tiles += tile(R.drawable.ic_lucide_frame, "Ambient", "Picture frame") {
            dialog.dismiss(); AmbientActivity.start(activity, AmbientActivity.AMBIENT)
        }
        val cinema = Prefs.cinemaOn(c)
        tiles += tile(R.drawable.ic_lucide_film, "Cinema", if (cinema) "On" else "Off", active = cinema) {
            dialog.dismiss()
            (activity as? MainActivity)?.let { CinemaMode.toggle(it) }
        }
        tiles += tile(R.drawable.ic_lucide_clock, "Sleep Clock", "Dark display") {
            dialog.dismiss(); AmbientActivity.start(activity, AmbientActivity.SLEEP)
        }
        tiles += tile(R.drawable.ic_lucide_image, "Wallpaper", "Next photo") {
            host.nextWallpaper()
            dialog.dismiss()
        }
        val night = host.isNightMode()
        tiles += tile(if (night) R.drawable.ic_lucide_moon else R.drawable.ic_lucide_sun, "Night mode", if (night) "On" else "Off", active = night) {
            host.setNightMode(!host.isNightMode())
            refocusAfterRebuild("Night mode")
        }
        tiles += tile(R.drawable.ic_lucide_sparkle, "Claude", "Ask anything") {
            open(Intent(c, ClaudeRoomActivity::class.java), inApp = true)
        }
        for (light in host.lights()) {
            tiles += tile(R.drawable.ic_lucide_bulb, light.name, if (light.isOn) "On" else "Off", active = light.isOn) {
                host.toggleLight(light) { refocusAfterRebuild(light.name) }
            }
        }
        for (pair in tiles.chunked(2)) {
            val row = LinearLayout(c)
            pair.forEachIndexed { i, t ->
                row.addView(t, LinearLayout.LayoutParams(0, dp(c, 62f), 1f).apply { if (i == 1) marginStart = dp(c, 10f) })
            }
            if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 10f) })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10f) })
        }

        // ---- Round shortcuts ----
        val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER }
        val shortcuts = listOf(
            Triple(R.drawable.ic_lucide_search, "Search") { search() },
            Triple(R.drawable.ic_lucide_grid, "Apps") { open(Intent(c, AllAppsActivity::class.java), inApp = true) },
            Triple(R.drawable.ic_lucide_monitor, "Display & sound") { openSettings(Settings.ACTION_DISPLAY_SETTINGS) },
            Triple(R.drawable.ic_lucide_settings, "Launcher settings") { open(Intent(c, SettingsActivity::class.java), inApp = true) },
        )
        shortcuts.forEachIndexed { i, (icon, label, action) ->
            buttons.addView(round(icon, label, action), LinearLayout.LayoutParams(dp(c, 46f), dp(c, 46f)).apply {
                if (i > 0) marginStart = dp(c, 18f)
            })
        }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })

        // ---- Memory / storage ----
        panel.addView(GlassUi.text(c, memoryLine(), 11f, R.font.inter_medium, 0x80FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 14f) })

        firstFocus = tiles.firstOrNull()
    }

    /** Rebuild (to show new on/off states) and keep focus on the same tile. */
    private fun refocusAfterRebuild(title: String) {
        if (!dialog.isShowing) return
        build()
        findTile(panel, title)?.requestFocus()
    }

    private fun findTile(v: View, title: String): View? {
        if (v.tag == title) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTile(v.getChildAt(i), title)?.let { return it }
        return null
    }

    // ---------- Pieces ----------

    private fun tile(icon: Int, title: String, subtitle: String, active: Boolean = false, onClick: () -> Unit): View {
        val t = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 16f, if (active) 0x38FFFFFF else 0x1AFFFFFF)
            setPadding(dp(c, 14f), 0, dp(c, 12f), 0)
            isFocusable = true
            isClickable = true
            tag = title
            contentDescription = "$title, $subtitle"
            setOnClickListener { onClick() }
        }
        t.addView(icon(icon, 18f), LinearLayout.LayoutParams(dp(c, 18f), dp(c, 18f)))
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        text.addView(GlassUi.rowText(c, title, 13f, R.font.inter_semibold, GlassUi.primaryText()))
        text.addView(GlassUi.rowText(c, subtitle, 11f, R.font.inter_regular, GlassUi.secondaryText()),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 3f) })
        t.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 11f) })
        GlassUi.focusLift(t, 1.03f)
        return t
    }

    private fun pill(icon: Int, label: String, onClick: () -> Unit): View {
        val p = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 17f, 0x1AFFFFFF)
            setPadding(dp(c, 12f), 0, dp(c, 14f), 0)
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        p.addView(icon(icon, 14f), LinearLayout.LayoutParams(dp(c, 14f), dp(c, 14f)))
        p.addView(GlassUi.rowText(c, label, 11.5f, R.font.inter_medium, GlassUi.primaryText()),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 7f) })
        GlassUi.focusLift(p, 1.04f)
        return p
    }

    private fun round(icon: Int, label: String, onClick: () -> Unit): View {
        val b = FrameLayout(c).apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xF5FFFFFF.toInt()) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) })
            }
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        b.addView(icon(icon, 17f), FrameLayout.LayoutParams(dp(c, 17f), dp(c, 17f), Gravity.CENTER))
        GlassUi.focusLift(b, 1.08f)
        return b
    }

    @Suppress("UNUSED_PARAMETER")
    private fun icon(res: Int, sizeDp: Float) = ImageView(c).apply {
        setImageResource(res)
        imageTintList = GlassUi.primaryText()
        isDuplicateParentStateEnabled = true
    }

    private fun recentCard(app: AppEntry): View {
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 18f, 0x1AFFFFFF)
            setPadding(dp(c, 10f), 0, dp(c, 14f), 0)
            isFocusable = true
            isClickable = true
            contentDescription = "Open ${app.label}"
            setOnClickListener {
                dialog.dismiss()
                AppActions.launch(activity, app, it)
            }
        }
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF2C2B30.toInt()) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            val banner = try {
                c.packageManager.getApplicationBanner(app.packageName)
            } catch (e: PackageManager.NameNotFoundException) { null }
            if (banner != null) setImageDrawable(banner) else setImageBitmap(TileRenderer.forApp(app, dp(c, 58f)))
        }
        card.addView(thumb, LinearLayout.LayoutParams(dp(c, 103f), dp(c, 58f)))
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        text.addView(GlassUi.rowText(c, "CONTINUE", 9.5f, R.font.inter_semibold, GlassUi.secondaryText()).apply { letterSpacing = 0.08f })
        text.addView(GlassUi.rowText(c, app.label, 15f, R.font.inter_semibold, GlassUi.primaryText()),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 12f) })
        GlassUi.focusLift(card, 1.02f)
        return card
    }

    // ---------- Status ----------

    private fun online(): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    private fun networkName(): String = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Not connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION")
                val ssid = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
                    ?.trim('"')
                if (ssid.isNullOrBlank() || ssid.contains("unknown")) "Connected" else ssid
            }
            else -> "Connected"
        }
    } catch (e: Exception) { "Connected" }

    private fun soundSummary(): String = try {
        val am = c.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val where = when {
            outs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth"
            outs.any { it == AudioDeviceInfo.TYPE_HDMI || it == AudioDeviceInfo.TYPE_HDMI_ARC } -> "TV speakers"
            else -> "Speakers"
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        "$where · ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%"
    } catch (e: Exception) { "TV speakers" }

    private fun memoryLine(): String = try {
        val info = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val stat = StatFs(Environment.getDataDirectory().path)
        "${Formatter.formatShortFileSize(c, info.availMem)} RAM · ${Formatter.formatShortFileSize(c, stat.availableBytes)} storage free"
    } catch (e: Exception) { "" }

    // ---------- Actions ----------

    private fun startScreensaver() {
        val tries = listOf(
            Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"),
            Intent(Settings.ACTION_DREAM_SETTINGS),
        )
        if (!tryAll(tries)) GlassUi.toast(activity, "Screensaver isn’t available on this TV")
    }

    private fun search() {
        val tries = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        if (!tryAll(tries)) GlassUi.toast(activity, "Search isn’t available on this TV")
    }

    private fun openSettings(action: String) {
        if (!tryAll(listOf(Intent(action), Intent(Settings.ACTION_SETTINGS)))) {
            GlassUi.toast(activity, "That settings page isn’t available")
        }
    }

    private fun open(intent: Intent, inApp: Boolean) {
        if (!inApp) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryAll(listOf(intent))) dialog.dismiss() else GlassUi.toast(activity, "Couldn’t open that")
    }

    private fun tryAll(intents: List<Intent>): Boolean {
        for (i in intents) {
            try {
                activity.startActivity(i)
                dialog.dismiss()
                return true
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        return false
    }
}
