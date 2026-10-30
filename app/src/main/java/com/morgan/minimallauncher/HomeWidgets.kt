package com.morgan.minimallauncher

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.morgan.minimallauncher.GlassUi.dp

/**
 * The small home-screen widgets, drawn as plain text on the wallpaper
 * (no cards), in two quiet columns either side of the clock:
 * left - weather and the next calendar event;
 * right - what's playing and the lights.
 */
class HomeWidgets(
    private val activity: AppCompatActivity,
    private val left: LinearLayout,
    private val right: LinearLayout,
    private val lights: () -> List<GoveeHelper.Device>,
    private val openLights: () -> Unit,
) {
    private val c = activity
    private val handler = Handler(Looper.getMainLooper())
    private var lastMusicKey: String? = null

    private val musicPoll = object : Runnable {
        override fun run() {
            refreshMusic()
            handler.postDelayed(this, 3000)
        }
    }
    private val slowPoll = object : Runnable {
        override fun run() {
            refreshWeather(); refreshCalendar()
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    private val weatherBox = column()
    private val calendarBox = column()
    private val musicBox = column(Gravity.END)
    private val homeBox = column(Gravity.END)

    init {
        left.addView(weatherBox)
        left.addView(calendarBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })
        right.addView(musicBox)
        right.addView(homeBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 16f) })
    }

    fun start() {
        refreshLights()
        handler.post(slowPoll)
        handler.post(musicPoll)
    }

    fun stop() {
        handler.removeCallbacks(slowPoll)
        handler.removeCallbacks(musicPoll)
    }

    // ---------- Weather ----------

    private fun refreshWeather() {
        if (!Prefs.widget(c, Prefs.W_WEATHER)) { weatherBox.removeAllViews(); return }
        Weather.cached(c)?.let { showWeather(it) }
        Weather.refresh(c) { now -> if (now != null && !activity.isDestroyed) showWeather(now) }
    }

    private fun showWeather(w: Weather.Now) {
        weatherBox.removeAllViews()
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(c).apply { setImageResource(w.icon); alpha = 0.95f }, LinearLayout.LayoutParams(dp(c, 22f), dp(c, 22f)))
        row.addView(GlassUi.text(c, w.tempText, 30f, R.font.inter_regular).apply {
            GlassUi.flex(this, 300, 100)
            shade()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 9f) })
        weatherBox.addView(row)
        weatherBox.addView(small("${w.condition} · ${w.place}"), gap(2f))
        weatherBox.addView(small(w.rangeText, 0x8CFFFFFF.toInt()), gap(2f))
    }

    // ---------- Calendar ----------

    private fun refreshCalendar() {
        if (!Prefs.widget(c, Prefs.W_CALENDAR) || CalendarFeed.getUrl(c) == null) { calendarBox.removeAllViews(); return }
        CalendarFeed.upcoming(c) { events ->
            if (activity.isDestroyed) return@upcoming
            calendarBox.removeAllViews()
            val next = events?.firstOrNull()
            calendarBox.addView(label("UP NEXT"))
            if (next == null) {
                calendarBox.addView(small(if (events == null) "Calendar unavailable" else "Nothing coming up"), gap(4f))
                return@upcoming
            }
            calendarBox.addView(GlassUi.text(c, next.title, 14f, R.font.inter_semibold).apply { oneLine(); shade() }, gap(5f))
            calendarBox.addView(small(CalendarFeed.whenText(c, next)), gap(2f))
            events.getOrNull(1)?.let { calendarBox.addView(small("then ${it.title}", 0x8CFFFFFF.toInt()).apply { oneLine() }, gap(4f)) }
        }
    }

    // ---------- Now playing ----------

    private fun refreshMusic() {
        if (!Prefs.widget(c, Prefs.W_MUSIC)) { musicBox.removeAllViews(); lastMusicKey = null; return }
        val info = NowPlaying.current(c)
        val key = info?.let { "${it.title}|${it.artist}|${it.playing}" }
        if (key == lastMusicKey) return
        lastMusicKey = key
        val hadFocus = musicBox.findFocus() != null
        musicBox.removeAllViews()
        if (info == null) return

        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        text.addView(label(if (info.playing) "NOW PLAYING" else "PAUSED").apply { gravity = Gravity.END })
        text.addView(GlassUi.text(c, info.title, 13.5f, R.font.inter_semibold).apply { oneLine(); gravity = Gravity.END; shade() }, gap(4f))
        text.addView(small(listOf(info.artist, info.app).filter { it.isNotBlank() }.joinToString(" · ")).apply { oneLine(); gravity = Gravity.END }, gap(2f))
        row.addView(text, LinearLayout.LayoutParams(dp(c, 150f), -2))

        val art = FrameLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0x33FFFFFF) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        if (info.art != null) art.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(info.art) })
        else art.addView(ImageView(c).apply { setImageResource(R.drawable.ic_lucide_music); alpha = 0.8f },
            FrameLayout.LayoutParams(dp(c, 18f), dp(c, 18f), Gravity.CENTER))
        row.addView(art, LinearLayout.LayoutParams(dp(c, 44f), dp(c, 44f)).apply { marginStart = dp(c, 12f) })

        // Play/pause: a small round glass button.
        val button = FrameLayout(c).apply {
            setBackgroundResource(R.drawable.glass_circle_bg)
            isFocusable = true
            isClickable = true
            contentDescription = if (info.playing) "Pause" else "Play"
            setOnClickListener { NowPlaying.togglePlay(info); handler.postDelayed({ refreshMusic() }, 400) }
            addView(ImageView(c).apply {
                setImageResource(if (info.playing) R.drawable.ic_lucide_pause else R.drawable.ic_lucide_play)
                imageTintList = androidx.core.content.ContextCompat.getColorStateList(c, R.color.icon_on_glass)
                isDuplicateParentStateEnabled = true
            }, FrameLayout.LayoutParams(dp(c, 14f), dp(c, 14f), Gravity.CENTER))
        }
        lift(button)
        row.addView(button, LinearLayout.LayoutParams(dp(c, 34f), dp(c, 34f)).apply { marginStart = dp(c, 10f) })
        musicBox.addView(row)
        if (hadFocus) button.requestFocus()
    }

    // ---------- Smart home ----------

    fun refreshLights() {
        homeBox.removeAllViews()
        val devices = lights()
        if (!Prefs.widget(c, Prefs.W_HOME) || devices.isEmpty()) return
        val on = devices.count { it.isOn }
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(c, 10f), dp(c, 6f), dp(c, 10f), dp(c, 6f))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(c, 14f).toFloat(); setColor(0x26FFFFFF); setStroke(dp(c, 1f), 0x8CFFFFFF.toInt())
                })
            }
            contentDescription = "Lights, $on on. Open controls."
            setOnClickListener { openLights() }
        }
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        text.addView(label("HOME").apply { gravity = Gravity.END })
        text.addView(GlassUi.text(c, when (on) {
            0 -> "All lights off"
            devices.size -> if (on == 1) "Light on" else "All $on lights on"
            else -> "$on of ${devices.size} lights on"
        }, 13.5f, R.font.inter_semibold).apply { shade() }, gap(4f))
        row.addView(text)
        row.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_lucide_home)
            imageTintList = android.content.res.ColorStateList.valueOf(if (on > 0) 0xFFFFD27A.toInt() else 0xB3FFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(c, 20f), dp(c, 20f)).apply { marginStart = dp(c, 10f) })
        lift(row)
        homeBox.addView(row)
    }

    // ---------- Bits ----------

    private fun column(gravity: Int = Gravity.START) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        clipChildren = false
    }

    private fun label(t: String) = GlassUi.text(c, t, 9.5f, R.font.inter_semibold, 0xA6FFFFFF.toInt()).apply {
        letterSpacing = 0.1f
        shade()
    }

    private fun small(t: String, color: Int = 0xD9FFFFFF.toInt()) = GlassUi.text(c, t, 11.5f, R.font.inter_medium, color).apply { shade() }

    private fun TextView.shade() = setShadowLayer(dp(c, 6f).toFloat(), 0f, 0f, 0x59000000)

    private fun TextView.oneLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun gap(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, top) }

    private fun lift(v: View) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).setDuration(220).start()
        }
    }
}
