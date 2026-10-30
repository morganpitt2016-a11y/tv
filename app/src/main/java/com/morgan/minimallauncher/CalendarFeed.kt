package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Upcoming events from a calendar's private iCal (.ics) link - e.g.
 * Google Calendar › Settings › your calendar › "Secret address in iCal
 * format", or iCloud's public calendar link. Read-only; refreshed every
 * 30 minutes. Simple repeating events (daily / weekly / monthly / yearly)
 * are expanded.
 */
object CalendarFeed {
    private const val PREFS = "calendar"
    private const val MAX_AGE = 30 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    data class Event(val title: String, val start: Long, val allDay: Boolean)

    @Volatile private var events: List<Event> = emptyList()
    @Volatile private var fetchedAt = 0L

    fun getUrl(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", null)?.takeIf { it.isNotBlank() }

    fun setUrl(c: Context, url: String?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("url", url?.trim()).apply()
        fetchedAt = 0L
        events = emptyList()
    }

    /** Events from now on (all-day events from today), soonest first. Main-thread callback. */
    fun upcoming(c: Context, done: (List<Event>?) -> Unit) {
        val url = getUrl(c) ?: run { done(null); return }
        if (System.currentTimeMillis() - fetchedAt < MAX_AGE) { done(filtered()); return }
        Thread {
            val list = try {
                parse(Net.get(url.replaceFirst("webcal://", "https://")))
            } catch (e: Exception) { null }
            if (list != null) { events = list; fetchedAt = System.currentTimeMillis() }
            main.post { done(if (list == null && events.isEmpty()) null else filtered()) }
        }.start()
    }

    private fun filtered(): List<Event> {
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return events.filter { if (it.allDay) it.start >= today else it.start >= now - 15 * 60_000 }.sortedBy { it.start }
    }

    /** "Today 18:30", "Tomorrow", "Sat 09:00"… */
    fun whenText(c: Context, e: Event): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        val ev = Calendar.getInstance().apply { timeInMillis = e.start }
        val day = ev.get(Calendar.DAY_OF_YEAR) to ev.get(Calendar.YEAR)
        val dayText = when (day) {
            today -> "Today"
            tomorrow -> "Tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(ev.time)
        }
        if (e.allDay) return dayText
        val time = android.text.format.DateFormat.getTimeFormat(c).format(ev.time)
        return "$dayText $time"
    }

    // ---------- iCal parsing ----------

    private fun parse(text: String): List<Event> {
        // Unfold continuation lines (RFC 5545 §3.1).
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
        val out = mutableListOf<Event>()
        val horizon = System.currentTimeMillis() + 60L * 24 * 3600 * 1000
        val past = System.currentTimeMillis() - 24L * 3600 * 1000
        var inEvent = false
        var title = ""
        var start: Pair<Long, Boolean>? = null
        var rrule: String? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; title = ""; start = null; rrule = null }
                line == "END:VEVENT" -> {
                    inEvent = false
                    val s = start ?: continue
                    for (t in occurrences(s.first, rrule, past, horizon)) out += Event(title.ifBlank { "Event" }, t, s.second)
                }
                !inEvent -> {}
                line.startsWith("SUMMARY") -> title = unescape(line.substringAfter(':'))
                line.startsWith("DTSTART") -> start = parseDate(line)
                line.startsWith("RRULE:") -> rrule = line.removePrefix("RRULE:")
            }
        }
        return out.filter { it.start in past..horizon }
    }

    private fun occurrences(first: Long, rrule: String?, from: Long, to: Long): List<Long> {
        if (rrule == null) return listOf(first)
        val parts = rrule.split(";").associate { it.substringBefore('=') to it.substringAfter('=') }
        val field = when (parts["FREQ"]) {
            "DAILY" -> Calendar.DAY_OF_YEAR
            "WEEKLY" -> Calendar.WEEK_OF_YEAR
            "MONTHLY" -> Calendar.MONTH
            "YEARLY" -> Calendar.YEAR
            else -> return listOf(first)
        }
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val until = parts["UNTIL"]?.let { parseDate("X:$it")?.first } ?: Long.MAX_VALUE
        var count = parts["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        val cal = Calendar.getInstance().apply { timeInMillis = first }
        val result = mutableListOf<Long>()
        var guard = 0
        while (cal.timeInMillis <= to && cal.timeInMillis <= until && count > 0 && guard++ < 5000) {
            if (cal.timeInMillis >= from) result += cal.timeInMillis
            count--
            cal.add(field, interval)
        }
        return result
    }

    /** Returns (millis, allDay). */
    private fun parseDate(line: String): Pair<Long, Boolean>? = try {
        val params = line.substringBefore(':')
        val value = line.substringAfter(':').trim()
        if (value.length == 8) {
            val f = SimpleDateFormat("yyyyMMdd", Locale.US)
            f.parse(value)!!.time to true
        } else {
            val utc = value.endsWith("Z")
            val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            val tzid = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
            f.timeZone = when {
                utc -> TimeZone.getTimeZone("UTC")
                tzid != null -> TimeZone.getTimeZone(tzid)
                else -> TimeZone.getDefault()
            }
            f.parse(value.removeSuffix("Z"))!!.time to false
        }
    } catch (e: Exception) { null }

    private fun unescape(s: String) = s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\\\", "\\")
}
