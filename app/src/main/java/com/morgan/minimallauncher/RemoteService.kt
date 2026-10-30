package com.morgan.minimallauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Keeps the iPhone remote's web server running in the background while it's switched on. */
class RemoteService : Service() {

    private var server: RemoteServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "iPhone remote", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Minimal Launcher remote")
            .setContentText("Your iPhone can control this TV")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 2, n, type)
        server = RemoteServer(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isEnabled(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "remote"

        fun isEnabled(c: Context) = Prefs.bool(c, "remote_on", false)

        fun setEnabled(c: Context, on: Boolean) {
            Prefs.setBool(c, "remote_on", on)
            if (on) startIfEnabled(c) else c.stopService(Intent(c, RemoteService::class.java))
        }

        fun startIfEnabled(c: Context) {
            if (!isEnabled(c)) return
            try {
                ContextCompat.startForegroundService(c, Intent(c, RemoteService::class.java))
            } catch (e: Exception) {}
        }

        /** QR code for the pairing link (white on transparent, for the glass dialog). */
        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) for (x in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
            return bmp
        }
    }
}
