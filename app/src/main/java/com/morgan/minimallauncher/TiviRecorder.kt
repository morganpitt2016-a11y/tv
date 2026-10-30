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
                say(app, if (ok) "Recording on Dodgy Box" else "Couldn’t find the Record button - see Settings ▸ TiviMate Tuning Log")
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
        armSeriesCheck(c)
        refreshSeries(c)
    }

    // ---------- Series link ----------
    // "Record Series": every episode of the show on that channel that appears in the
    // TV guide is scheduled - now, and again each time the guide is refreshed.

    data class Series(val channel: String, val title: String)

    private const val SERIES_KEY = "rec_series"
    private const val SERIES_ID = 0x5E41E5
    private const val SERIES_EVERY_MS = 6 * 3600_000L

    private fun seriesKey(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun seriesList(c: Context): List<Series> = try {
        val a = JSONArray(Prefs.string(c, SERIES_KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Series(it.getString("ch"), it.getString("t")) }
    } catch (e: Exception) { emptyList() }

    private fun saveSeries(c: Context, items: List<Series>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("ch", it.channel).put("t", it.title)) }
        Prefs.setString(c, SERIES_KEY, a.toString())
    }

    fun isSeries(c: Context, ch: LiveGuide.Channel, p: LiveGuide.Programme) =
        seriesList(c).any { it.channel == ch.name && seriesKey(it.title) == seriesKey(p.title) }

    /** Link the series and schedule every episode the guide knows about. Returns how many. */
    fun addSeries(c: Context, ch: LiveGuide.Channel, p: LiveGuide.Programme, channels: List<LiveGuide.Channel>): Int {
        val rule = Series(ch.name, p.title)
        saveSeries(c, seriesList(c).filterNot { it.channel == rule.channel && seriesKey(it.title) == seriesKey(rule.title) } + rule)
        armSeriesCheck(c)
        return scheduleSeries(c, channels)
    }

    fun removeSeries(c: Context, ch: LiveGuide.Channel, p: LiveGuide.Programme) {
        val key = seriesKey(p.title)
        saveSeries(c, seriesList(c).filterNot { it.channel == ch.name && seriesKey(it.title) == key })
        // Drop the episodes it had scheduled too.
        list(c).filter { it.channel == ch.name && seriesKey(it.title) == key }.forEach { cancel(c, it.id) }
    }

    /** Schedule every upcoming episode of every linked series. Returns how many were new. */
    fun scheduleSeries(c: Context, channels: List<LiveGuide.Channel>): Int {
        val rules = seriesList(c)
        if (rules.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val have = list(c).map { it.id }.toSet()
        var added = 0
        for (rule in rules) {
            val ch = channels.firstOrNull { it.name == rule.channel } ?: continue
            for (p in ch.programmes) {
                // Upcoming episodes (one already on is recorded straight away instead).
                if (p.start <= now) continue
                if (seriesKey(p.title) != seriesKey(rule.title)) continue
                if (idOf(ch.name, p.start) in have) continue
                schedule(c, ch, p)
                added++
            }
        }
        return added
    }

    /** Check the guide for new episodes every few hours. */
    private fun armSeriesCheck(c: Context) {
        if (seriesList(c).isEmpty()) return
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, SERIES_ID, Intent(c, RecordReceiver::class.java).putExtra("series", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + SERIES_EVERY_MS, SERIES_EVERY_MS, pi)
    }

    internal fun refreshSeries(c: Context) {
        if (seriesList(c).isEmpty()) return
        LiveGuide.load(c) { channels -> if (channels != null) scheduleSeries(c, channels) }
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
            if (ok) pressRecord(c) else say(c, "Couldn’t find ${item.channel} on Dodgy Box to record ${item.title}")
        }
    }

    private fun say(c: Context, t: String) = Toast.makeText(c.applicationContext, t, Toast.LENGTH_LONG).show()
}

class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("series", false)) TiviRecorder.refreshSeries(c.applicationContext)
        else TiviRecorder.fire(c.applicationContext, i.getIntExtra("id", 0))
    }
}
