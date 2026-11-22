package com.morgan.minimallauncher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

/**
 * TV Colour Lights for every app: a tiny (64x36) live copy of the whole screen - YouTube, TiviMate, Plex, the
 * launcher's own player - read about 30 times a second while something is playing with sound, and handed to the
 * [ColourEngine] for the Magic Hue lights. DRM apps (Netflix, Disney+, Prime) come through black - Android blanks
 * them for every capture - so then the lights just settle to a dim warm glow.
 *
 * Android's screen capture needs a yes from the user each time it starts ([ConsentActivity]); on this TV the
 * launcher has a standing yes (appops PROJECT_MEDIA, set once over adb), so it starts silently.
 */
class LightsCaptureService : Service() {

    companion object {
        @Volatile var running = false
            private set
        private var askedThisRun = false
        private const val CH = "tv_colour_lights"

        /** Start it if TV Colour Lights is on and it isn't running (asks for screen capture the first time). */
        fun ensure(activity: Activity, force: Boolean = false) {
            if (running || !MagicHue.syncOn(activity)) return
            if (askedThisRun && !force) return
            askedThisRun = true
            activity.startActivity(Intent(activity, ConsentActivity::class.java))
        }

        fun stop(c: Context) = c.stopService(Intent(c, LightsCaptureService::class.java))
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("lights-capture").apply { start() }
    private val bg = Handler(thread.looper)
    private var link: MagicHue.Link? = null
    private var engine: ColourEngine? = null
    private var quietSince = 0L
    private val px = IntArray(64 * 36)
    private lateinit var audio: AudioManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        if (projection != null || data == null) return START_NOT_STICKY
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "TV Colour Lights", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CH).setContentTitle("TV Colour Lights").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, bg)
            val r = ImageReader.newInstance(64, 36, PixelFormat.RGBA_8888, 2)
            display = p.createVirtualDisplay("tv-colour", 64, 36, 72, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg)
            projection = p; reader = r
            running = true
            Diag.send(this, "lights", "TV Colour Lights: screen capture running (every app)")
            bg.post(tick)
        } catch (e: Exception) {
            Diag.send(this, "lights", "TV Colour Lights: screen capture failed - ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val ip = MagicHue.ip(this@LightsCaptureService)
            val playing = audio.isMusicActive && MagicHue.syncOn(this@LightsCaptureService) && ip != null
            if (playing) {
                quietSince = 0L
                if (link == null) { link = MagicHue.Link(ip!!) { Diag.send(this@LightsCaptureService, "lights", it) }.also { it.begin(); engine = ColourEngine(it) } }
                read()?.let { engine?.frame(px, it) }
            } else if (link != null) {
                // A short pause (adverts loading, skipping) keeps the lights; a real stop hands them back.
                if (quietSince == 0L) quietSince = SystemClock.uptimeMillis()
                engine?.idle()
                if (SystemClock.uptimeMillis() - quietSince > 4000) { link?.end(); link = null; engine = null }
            }
            bg.postDelayed(this, if (link != null) 33 else 500)
        }
    }

    /** The newest frame into [px] as ARGB; how many pixels, or null if none. */
    private fun read(): Int? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride; val rs = plane.rowStride
            var i = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val o = y * rs + x * ps
                val r = buf.get(o).toInt() and 0xFF; val g = buf.get(o + 1).toInt() and 0xFF; val b = buf.get(o + 2).toInt() and 0xFF
                px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return i
        } catch (_: Exception) { return null } finally { img.close() }
    }

    override fun onDestroy() {
        running = false
        bg.removeCallbacks(tick)
        link?.end(); link = null
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        reader?.close()
        thread.quitSafely()
        super.onDestroy()
    }

    /** Asks Android for screen capture (silent when the launcher already has a standing yes), then starts the service. */
    class ConsentActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(Intent(this, LightsCaptureService::class.java).putExtra("code", resultCode).putExtra("data", data))
            } else Diag.send(this, "lights", "TV Colour Lights: screen capture not allowed")
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
