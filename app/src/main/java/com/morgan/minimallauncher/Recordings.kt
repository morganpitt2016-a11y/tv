package com.morgan.minimallauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Recordings made by the app itself from your provider's live channels (needs the
 * Dodgy Box login). Each one is saved on the TV and listed in My List and Watch.
 */
object Recordings {

    data class Rec(val id: String, val title: String, val channel: String, val start: Long, val stop: Long,
                   val file: String, val image: String?, val done: Boolean)

    private const val KEY = "recordings"

    fun list(c: Context): List<Rec> = try {
        val a = JSONArray(Prefs.string(c, KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Rec(it.getString("id"), it.getString("t"), it.getString("ch"), it.getLong("s"), it.getLong("e"),
                it.getString("f"), it.optString("img").ifBlank { null }, it.optBoolean("done"))
        }.filter { File(it.file).exists() || !it.done }.sortedByDescending { it.start }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(c: Context, items: List<Rec>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("ch", it.channel).put("s", it.start).put("e", it.stop)
            .put("f", it.file).put("img", it.image ?: "").put("done", it.done)) }
        Prefs.setString(c, KEY, a.toString())
    }

    @Synchronized private fun upsert(c: Context, r: Rec) = save(c, list(c).filter { it.id != r.id } + r)

    fun delete(c: Context, r: Rec) {
        RecordService.stop(c, r.id)
        File(r.file).delete()
        save(c, list(c).filter { it.id != r.id })
    }

    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "recordings").apply { mkdirs() }

    /** Start recording [channel] now until [stop] (plus a couple of minutes, in case it overruns). */
    fun start(c: Context, channel: String, title: String, stop: Long, image: String?): Boolean {
        val app = c.applicationContext
        val id = (channel + title + System.currentTimeMillis() / 60_000).hashCode().toUInt().toString(16)
        val safe = title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().take(40).ifBlank { "Recording" }
        val file = File(dir(app), "$safe-$id.ts").path
        upsert(app, Rec(id, title, channel, System.currentTimeMillis(), stop + 2 * 60_000, file, image, false))
        ContextCompat.startForegroundService(app, Intent(app, RecordService::class.java)
            .putExtra("id", id).putExtra("channel", channel))
        return true
    }

    internal fun finished(c: Context, id: String) {
        list(c).firstOrNull { it.id == id }?.let { r ->
            val f = File(r.file)
            if (!f.exists() || f.length() < 512 * 1024) { f.delete(); save(c, list(c).filter { it.id != id }) }
            else upsert(c, r.copy(done = true, stop = System.currentTimeMillis()))
        }
    }

    fun isRecording(r: Rec) = !r.done && System.currentTimeMillis() < r.stop
}

/** Saves live channels to the TV's storage, one stream per recording, until each ends. */
class RecordService : Service() {

    private val running = ConcurrentHashMap<String, Thread>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Recording")
            .setContentText("Saving a programme from Dodgy Box")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 7, n, type)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minimal:record").apply {
            setReferenceCounted(false); acquire(6 * 3600_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        if (intent?.getBooleanExtra("stop", false) == true) { id?.let { running.remove(it)?.interrupt() }; checkIdle(); return START_NOT_STICKY }
        val channel = intent?.getStringExtra("channel")
        if (id == null || channel == null || running.containsKey(id)) { checkIdle(); return START_NOT_STICKY }
        val t = Thread { record(id, channel) }
        running[id] = t
        t.start()
        return START_NOT_STICKY
    }

    private fun record(id: String, channel: String) {
        val app = applicationContext
        try {
            val rec = Recordings.list(app).firstOrNull { it.id == id } ?: return
            val live = Xtream.findLive(app, channel) ?: return
            val out = FileOutputStream(File(rec.file), true)
            val buf = ByteArray(64 * 1024)
            out.use {
                // Keep going until the programme ends; reconnect if the stream drops.
                while (System.currentTimeMillis() < rec.stop && !Thread.currentThread().isInterrupted) {
                    if (Recordings.dir(app).usableSpace < 300L * 1024 * 1024) break  // TV nearly full
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(Xtream.liveUrl(app, live.id)).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
                        conn.setRequestProperty("User-Agent", Net.USER_AGENT)
                        conn.inputStream.use { input ->
                            while (System.currentTimeMillis() < rec.stop && !Thread.currentThread().isInterrupted) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    } catch (e: Exception) {
                        try { Thread.sleep(3000) } catch (ie: InterruptedException) { break }
                    } finally { conn?.disconnect() }
                }
            }
        } catch (e: Exception) {
        } finally {
            Recordings.finished(app, id)
            running.remove(id)
            checkIdle()
        }
    }

    private fun checkIdle() {
        if (running.isEmpty()) { wake?.let { if (it.isHeld) it.release() }; stopSelf() }
    }

    override fun onDestroy() {
        running.values.forEach { it.interrupt() }
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "recordings"
        fun stop(c: Context, id: String) {
            try { c.startService(Intent(c, RecordService::class.java).putExtra("id", id).putExtra("stop", true)) } catch (e: Exception) {}
        }
    }
}
