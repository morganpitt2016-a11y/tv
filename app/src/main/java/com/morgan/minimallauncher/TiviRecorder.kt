package com.morgan.minimallauncher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recording live TV through TiviMate (TiviMate Premium records; other apps can't
 * ask it to). "Record" tunes TiviMate to the channel and presses its own Record
 * button, using the accessibility service. Programmes later on are remembered
 * here and recorded the same way just after they start - the TV needs to be on.
 */
object TiviRecorder {

    data class Scheduled(val id: Int, val channel: String, val title: String, val start: Long, val stop: Long)

    private const val KEY = "rec_schedule"
    /** Start a little after the programme does, so TiviMate's "now" is the right programme. */
    private const val AFTER_START_MS = 60_000L

    // ---------- Now ----------

    /** Record what's on [ch] now. */
    fun recordNow(activity: Activity, ch: LiveGuide.Channel) {
        if (!TiviTuner.canTune()) {
            GlassUi.toast(activity, "Turn on the accessibility service in Settings to record")
            return
        }
        val app = activity.applicationContext
        LiveGuide.openTiviMate(activity, ch) { ok -> if (ok) pressRecord(app) }
    }

    private fun pressRecord(app: Context) {
        // Let the picture start and TiviMate settle first.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            TiviTuner.record { ok ->
                say(app, if (ok) "Recording in TiviMate" else "Couldn’t find TiviMate’s Record button - see Settings ▸ TiviMate Tuning Log")
            }
        }, 2500)
    }

    // ---------- Later ----------

    fun list(c: Context): List<Scheduled> = try {
        val a = JSONArray(Prefs.string(c, KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Scheduled(it.getInt("id"), it.getString("ch"), it.getString("t"), it.getLong("s"), it.getLong("e"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, items: List<Scheduled>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("ch", it.channel).put("t", it.title).put("s", it.start).put("e", it.stop)) }
        Prefs.setString(c, KEY, a.toString())
    }

    private fun idOf(channel: String, start: Long) = (channel + start).hashCode()

    fun isScheduled(c: Context, ch: LiveGuide.Channel, p: LiveGuide.Programme) = list(c).any { it.id == idOf(ch.name, p.start) }

    fun schedule(c: Context, ch: LiveGuide.Channel, p: LiveGuide.Programme) {
        val item = Scheduled(idOf(ch.name, p.start), ch.name, p.title, p.start, p.stop)
        save(c, list(c).filter { it.id != item.id } + item)
        arm(c, item)
    }

    fun cancel(c: Context, id: Int) {
        save(c, list(c).filter { it.id != id })
        (c.getSystemService(AlarmManager::class.java)).cancel(pending(c, id))
    }

    /** Set the alarms again (after a restart), dropping anything already over. */
    fun rearm(c: Context) {
        val now = System.currentTimeMillis()
        val keep = list(c).filter { it.stop > now }
        save(c, keep)
        keep.forEach { arm(c, it) }
    }

    private fun pending(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, id, Intent(c, RecordReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(c: Context, item: Scheduled) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = maxOf(item.start + AFTER_START_MS, System.currentTimeMillis() + 5_000)
        val pi = pending(c, item.id)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    /** The alarm went off: tune TiviMate and record. */
    internal fun fire(c: Context, id: Int) {
        val item = list(c).firstOrNull { it.id == id } ?: return
        save(c, list(c).filter { it.id != id })
        if (System.currentTimeMillis() > item.stop) return
        val intent = LiveGuide.launchIntent(c, LiveGuide.TIVIMATE) ?: return say(c, "TiviMate isn’t installed - couldn’t record ${item.title}")
        if (!TiviTuner.canTune()) return say(c, "Couldn’t record ${item.title}: the accessibility service is off")
        say(c, "Recording ${item.title} on ${item.channel}")
        try { c.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { return }
        val restart = {
            try { c.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) } catch (e: Exception) {}
        }
        TiviTuner.tune(item.channel, restart) { ok ->
            if (ok) pressRecord(c) else say(c, "Couldn’t find ${item.channel} in TiviMate to record ${item.title}")
        }
    }

    private fun say(c: Context, t: String) = Toast.makeText(c.applicationContext, t, Toast.LENGTH_LONG).show()
}

class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        TiviRecorder.fire(c.applicationContext, i.getIntExtra("id", 0))
    }
}
