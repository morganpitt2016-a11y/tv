package com.morgan.minimallauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The full-screen idle displays, used by AmbientActivity and the system
 * screensaver:
 *  - AMBIENT: a digital picture frame - full-screen photos, artwork or
 *    your own album, a large minimalist clock, weather, and what's playing
 *    with a music visualiser.
 *  - AERIAL: slowly drifting landscapes (by category) with the time,
 *    weather and the location of each picture.
 *  - SLEEP: a dark screen with a dim clock that drifts to protect the panel.
 */
class AmbientScreen(private val c: Context, mode: String) {

    var mode: String = mode
        private set

    val view = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }

    private val handler = Handler(Looper.getMainLooper())
    private val photoA = photoView()
    private val photoB = photoView()
    private val scrim = View(c)
    private val overlay = FrameLayout(c)
    private val clock = GlassUi.text(c, "", 120f, R.font.inter_regular)
    private val date = GlassUi.text(c, "", 16f, R.font.inter_medium)
    private val weather = GlassUi.text(c, "", 14f, R.font.inter_medium, 0xD9FFFFFF.toInt())
    private val place = GlassUi.text(c, "", 15f, R.font.inter_semibold)
    private val credit = GlassUi.text(c, "", 10.5f, R.font.inter_regular, 0x99FFFFFF.toInt())
    private val music = LinearLayout(c)
    private val bars = Bars(c)

    private var pics: List<Slide> = emptyList()
    private var index = 0
    private var showingA = false
    private var running = false
    private var visualizer: Visualizer? = null

    private class Slide(val load: () -> Bitmap?, val place: String, val credit: String)

    init {
        view.addView(photoA, FrameLayout.LayoutParams(-1, -1))
        view.addView(photoB, FrameLayout.LayoutParams(-1, -1))
        view.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        view.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        listOf(clock, date, weather, place, credit).forEach { it.setShadowLayer(dp(c, 10f).toFloat(), 0f, 0f, 0x66000000) }
    }

    // ---------- Lifecycle ----------

    fun start() {
        if (running) return
        running = true
        layout()
        handler.post(tick)
        if (mode != SLEEP) {
            loadPictures()
            handler.post(slideTick)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        listOf(photoA, photoB, clock, place).forEach { it.animate().cancel() }
        releaseVisualizer()
    }

    /** Next picture now (Left/Right on the remote). */
    fun next() {
        if (!running || mode == SLEEP) return
        handler.removeCallbacks(slideTick)
        handler.post(slideTick)
    }

    /** Fade to the dark sleep clock (after the time limit). */
    fun switchTo(newMode: String) {
        if (newMode == mode) return
        stop()
        mode = newMode
        photoA.animate().alpha(0f).setDuration(4000).start()
        photoB.animate().alpha(0f).setDuration(4000).start()
        start()
    }

    // ---------- Layout per mode ----------

    private fun layout() {
        overlay.removeAllViews()
        listOf(clock, date, weather, place, credit, music).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        when (mode) {
            AMBIENT -> {
                scrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8C000000.toInt(), 0x1A000000, 0x00000000))
                clock.textSize = 112f
                GlassUi.flex(clock, 250, 82)
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                col.addView(clock)
                col.addView(date, lp(top = 2f))
                col.addView(weather, lp(top = 6f))
                overlay.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(c, 56f), 0, 0, dp(c, 44f))
                })
                buildMusic()
                overlay.addView(music, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(c, 56f), dp(c, 50f))
                })
                overlay.addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(c, 28f), dp(c, 40f), 0)
                })
            }
            AERIAL -> {
                scrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x59000000, 0x00000000, 0x00000000, 0x66000000))
                clock.textSize = 46f
                GlassUi.flex(clock, 380, 90)
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                col.addView(clock)
                col.addView(weather.apply { gravity = Gravity.END }, lp(top = 2f))
                overlay.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(c, 34f), dp(c, 48f), 0)
                })
                val loc = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                loc.addView(place)
                loc.addView(credit, lp(top = 4f))
                overlay.addView(loc, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(c, 48f), 0, 0, dp(c, 40f))
                })
            }
            else -> { // SLEEP
                scrim.background = null
                clock.textSize = 150f
                GlassUi.flex(clock, 200, 80)
                clock.setTextColor(0xFFFFFFFF.toInt())
                clock.alpha = 0f
                clock.animate().alpha(0.32f).setDuration(3000).start()
                overlay.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        }
        updateClock()
        refreshWeather()
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, top) }

    // ---------- Clock, weather, music ----------

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (mode == AMBIENT) refreshMusic()
            // Sleep clock drifts a little every minute so nothing stays lit in one place.
            if (mode == SLEEP && Calendar.getInstance().get(Calendar.SECOND) == 0) drift()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        clock.text = DateFormat.format(if (DateFormat.is24HourFormat(c)) "H:mm" else "h:mm", now)
        date.text = DateFormat.format("EEEE d MMMM", now)
        if (now.get(Calendar.MINUTE) % 15 == 0 && now.get(Calendar.SECOND) == 0) refreshWeather()
    }

    private fun refreshWeather() {
        if (!Prefs.widget(c, Prefs.W_WEATHER) || mode == SLEEP) { weather.text = ""; return }
        Weather.refresh(c) { w ->
            weather.text = w?.let { "${it.tempText}  ${it.condition} · ${it.place}" } ?: ""
        }
    }

    private fun drift() {
        val dx = Random.nextInt(-dp(c, 140f), dp(c, 140f)).toFloat()
        val dy = Random.nextInt(-dp(c, 90f), dp(c, 90f)).toFloat()
        clock.animate().translationX(dx).translationY(dy).setDuration(20_000).setInterpolator(LinearInterpolator()).start()
    }

    private val musicTitle = GlassUi.text(c, "", 14f, R.font.inter_semibold)
    private val musicArtist = GlassUi.text(c, "", 11.5f, R.font.inter_medium, 0xB3FFFFFF.toInt())
    private val musicArt = ImageView(c)

    private fun buildMusic() {
        music.removeAllViews()
        music.gravity = Gravity.CENTER_VERTICAL
        music.alpha = 0f
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        col.addView(bars, LinearLayout.LayoutParams(dp(c, 120f), dp(c, 28f)))
        col.addView(musicTitle.apply { gravity = Gravity.END; maxLines = 1 }, lp(top = 8f))
        col.addView(musicArtist.apply { gravity = Gravity.END; maxLines = 1 }, lp(top = 2f))
        music.addView(col, LinearLayout.LayoutParams(dp(c, 220f), -2))
        musicArt.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0x33FFFFFF) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        (musicArt.parent as? android.view.ViewGroup)?.removeView(musicArt)
        (bars.parent as? android.view.ViewGroup)?.removeView(bars)
        (musicTitle.parent as? android.view.ViewGroup)?.removeView(musicTitle)
        (musicArtist.parent as? android.view.ViewGroup)?.removeView(musicArtist)
        music.addView(musicArt, LinearLayout.LayoutParams(dp(c, 64f), dp(c, 64f)).apply { marginStart = dp(c, 14f) })
    }

    private var lastMusic: String? = null

    private fun refreshMusic() {
        val info = if (Prefs.widget(c, Prefs.W_MUSIC)) NowPlaying.current(c) else null
        val playing = info?.playing == true
        bars.active = playing
        if (playing && Prefs.visualiser(c)) ensureVisualizer() else releaseVisualizer()
        val key = info?.let { "${it.title}|${it.artist}|${it.playing}" }
        if (key == lastMusic) return
        lastMusic = key
        if (info == null) { music.animate().alpha(0f).setDuration(600).start(); return }
        musicTitle.text = info.title
        musicArtist.text = listOf(info.artist, info.app).filter { it.isNotBlank() }.joinToString(" · ")
        if (info.art != null) musicArt.setImageBitmap(info.art) else musicArt.setImageResource(R.drawable.ic_lucide_music)
        bars.visibility = if (Prefs.visualiser(c)) View.VISIBLE else View.GONE
        music.animate().alpha(1f).setDuration(600).start()
    }

    /** Real audio levels need the microphone permission (Settings › Ambient); otherwise the bars just sway. */
    private fun ensureVisualizer() {
        if (visualizer != null) return
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        visualizer = try {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        fft?.let { bars.feed(it) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) { null }
    }

    private fun releaseVisualizer() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (e: Exception) {}
        visualizer = null
    }

    // ---------- Pictures ----------

    private fun loadPictures() {
        val source = if (mode == AERIAL) "aerial" else Prefs.ambientSource(c)
        fun builtin(): List<Slide> {
            val cat = WallpaperLibrary.categories.firstOrNull { it.id == Prefs.aerialCategory(c) }
            val list = if (mode == AERIAL && cat != null) WallpaperLibrary.bundled.filter { it.number in cat.numbers } else WallpaperLibrary.bundled
            return list.shuffled().map { p -> Slide({ WallpaperLibrary.decode(c, p) }, p.place, "") }
        }
        fun fromPics(list: List<PhotoSources.Pic>) = list.shuffled().map { p ->
            Slide({ WallpaperLibrary.decodeFile(p.file, 1920) }, p.place, p.credit)
        }
        val initial: List<Slide> = when (source) {
            "aerial" -> fromPics(PhotoSources.aerial(c, Prefs.aerialCategory(c)) { if (running) pics = fromPics(it) })
            "art" -> fromPics(PhotoSources.artwork(c) { if (running) pics = fromPics(it) })
            "album" -> WallpaperLibrary.album(c).shuffled().map { p -> Slide({ WallpaperLibrary.decode(c, p) }, "", "") }
            else -> emptyList()
        }
        pics = initial.ifEmpty { builtin() }
        index = 0
    }

    private val slideTick = object : Runnable {
        override fun run() {
            showNext()
            handler.postDelayed(this, if (mode == AERIAL) AERIAL_MS else AMBIENT_MS)
        }
    }

    private fun showNext() {
        if (pics.isEmpty()) return
        if (index >= pics.size) index = 0
        val slide = pics[index++]
        Thread {
            val bmp = try { slide.load() } catch (e: OutOfMemoryError) { null }
            handler.post { if (running && bmp != null) crossfade(bmp, slide) }
        }.start()
    }

    /** Crossfade, then a slow "moving landscape" pan and zoom (Ken Burns). */
    private fun crossfade(bmp: Bitmap, slide: Slide) {
        val incoming = if (showingA) photoB else photoA
        val outgoing = if (showingA) photoA else photoB
        showingA = !showingA
        incoming.animate().cancel()
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.bringToFront()
        scrim.bringToFront()
        overlay.bringToFront()

        val duration = if (mode == AERIAL) AERIAL_MS + FADE_MS else AMBIENT_MS + FADE_MS
        val w = view.width.coerceAtLeast(1).toFloat()
        val h = view.height.coerceAtLeast(1).toFloat()
        val moving = mode == AERIAL
        val s0 = if (moving) 1.12f else 1.02f
        val s1 = if (moving) 1.22f else 1.07f
        fun pan(scale: Float) = if (moving) Random.nextFloat() * 2 - 1 to Random.nextFloat() * 2 - 1 else 0f to 0f
        val (ax, ay) = pan(s0)
        var (bx, by) = pan(s1)
        // Make sure it actually travels a little.
        if (moving && hypot(bx - ax, by - ay) < 0.8f) { bx = -ax; by = -ay }
        incoming.scaleX = s0; incoming.scaleY = s0
        incoming.translationX = ax * (s0 - 1) / 2 * w
        incoming.translationY = ay * (s0 - 1) / 2 * h
        incoming.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(DecelerateInterpolator()).withEndAction {
            outgoing.setImageDrawable(null)
        }.start()
        incoming.animate().scaleX(s1).scaleY(s1)
            .translationX(bx * (s1 - 1) / 2 * w).translationY(by * (s1 - 1) / 2 * h)
            .setDuration(duration).setInterpolator(LinearInterpolator()).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).start()

        // Location and credit: shown for a while with each picture.
        val showPlace = Prefs.showLocation(c) && slide.place.isNotBlank()
        place.text = if (showPlace) slide.place else ""
        credit.text = if (showPlace) slide.credit else ""
        for (t in listOf(place, credit)) {
            t.animate().cancel()
            t.alpha = 0f
            t.animate().alpha(1f).setStartDelay(FADE_MS).setDuration(1200).withEndAction {
                if (mode == AERIAL) t.animate().alpha(0f).setStartDelay(12_000).setDuration(1500).start()
            }.start()
        }
    }

    private fun photoView() = ImageView(c).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    /** Soft rounded level bars; real FFT data when available, otherwise a gentle sway. */
    private class Bars(c: Context) : View(c) {
        var active = false
        private val n = 18
        private val levels = FloatArray(n)
        private val target = FloatArray(n)
        private var lastFeed = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
        private val rect = RectF()

        fun feed(fft: ByteArray) {
            lastFeed = System.currentTimeMillis()
            for (i in 0 until n) {
                val k = 2 + i * 2
                if (2 * k + 1 >= fft.size) break
                val mag = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                target[i] = (mag / 64f).coerceIn(0.06f, 1f)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val t = System.currentTimeMillis()
            val real = t - lastFeed < 500
            for (i in 0 until n) {
                if (!real) target[i] = if (active) (0.25f + 0.55f * abs(sin(t / 380.0 + i * 0.7) * sin(t / 970.0 + i * 1.3)).toFloat()) else 0.06f
                levels[i] += (target[i] - levels[i]) * 0.25f
                val bw = width / (n * 1.6f)
                val x = width - (n - i) * bw * 1.6f
                val bh = (height * levels[i]).coerceAtLeast(bw)
                rect.set(x, height - bh, x + bw, height.toFloat())
                canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
            }
            postInvalidateOnAnimation()
        }
    }

    companion object {
        const val AMBIENT = "ambient"
        const val AERIAL = "aerial"
        const val SLEEP = "sleep"
        private const val AMBIENT_MS = 60_000L
        private const val AERIAL_MS = 40_000L
        private const val FADE_MS = 2500L
    }
}
