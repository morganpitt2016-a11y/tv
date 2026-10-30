package com.morgan.minimallauncher

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Backup way to open the launcher automatically, for TVs where the
 * accessibility settings can't be used. With "Usage access" allowed it
 * checks once a second which screen is in front, and when the stock
 * Google TV home screen appears it opens Minimal Launcher instead.
 * "Display over other apps" is what lets Android allow that switch from
 * the background. Also handles waking from sleep.
 *
 * Stays idle (and isn't started) while the accessibility service is on,
 * since that already does the job.
 */
class AppWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var detector: HomeDetector
    private var lastCheck = 0L
    private var lastLaunch = 0L

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 1000)
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && AutoLaunch.onWake(context)) {
                RedirectDiag.event(context, "wake")
                launch(force = true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        detector = HomeDetector(this)
        RedirectDiag.watching(this, detector.describe() + " (usage access)")
        startInForeground()
        ContextCompat.registerReceiver(this, wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        lastCheck = System.currentTimeMillis()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun check() {
        if (!AutoLaunch.isEnabled(this) || HomeRedirectService.isServiceEnabled(this)) return
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastCheck - 500, now)
        lastCheck = now
        val e = UsageEvents.Event()
        var homeAppeared = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // ACTIVITY_RESUMED has the same value (1) as the older MOVE_TO_FOREGROUND.
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val pkg = e.packageName ?: continue
                RedirectDiag.seen(this, "$pkg/${e.className?.substringAfterLast('.').orEmpty()}")
                // The newest resumed screen decides: home appeared, or something replaced it.
                homeAppeared = detector.isHomeScreen(pkg, e.className.orEmpty())
            }
        }
        if (homeAppeared) launch(force = false)
    }

    private fun launch(force: Boolean) {
        val t = SystemClock.elapsedRealtime()
        if (!force && t - lastLaunch < 1500) return
        lastLaunch = t
        RedirectDiag.attempted(this)
        try {
            AutoLaunch.open(this)
        } catch (e: Exception) {
            RedirectDiag.error(this, "watch launch: ${e.javaClass.simpleName}")
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open automatically", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Minimal Launcher")
            .setContentText("Opens automatically instead of the Google TV home screen")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    companion object {
        private const val CHANNEL = "auto_open"

        fun hasUsageAccess(c: Context): Boolean {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** True when the backup method has everything it needs. */
        fun canRun(c: Context) = hasUsageAccess(c) && Settings.canDrawOverlays(c)

        /** Starts the watcher if it's needed and allowed; safe to call often. */
        fun startIfNeeded(c: Context) {
            if (!hasUsageAccess(c)) return
            if (HomeRedirectService.isServiceEnabled(c)) return
            if (!(AutoLaunch.isEnabled(c) || AutoLaunch.onWake(c))) return
            try {
                ContextCompat.startForegroundService(c, Intent(c, AppWatchService::class.java))
            } catch (e: Exception) {
                RedirectDiag.error(c, "watcher start: ${e.javaClass.simpleName}")
            }
        }
    }
}
