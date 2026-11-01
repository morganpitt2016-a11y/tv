package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timings measured on the TV itself, so slow spots are found with numbers rather than guesses:
 * a rolling log of how long things took, and a speed test you can run from Settings.
 */
object Perf {
    private val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)

    fun now(): Long = SystemClock.elapsedRealtime()

    fun note(what: String, startedAt: Long) = record("$what: ${now() - startedAt} ms")

    fun record(line: String) = synchronized(log) {
        log.addLast("${clock.format(Date())}  $line")
        while (log.size > 80) log.removeFirst()
    }

    fun text(): String = synchronized(log) {
        if (log.isEmpty()) "Nothing timed yet. Use the TV for a bit, then look again." else log.joinToString("\n")
    }

    private fun timed(label: String, block: () -> String?): String {
        val t0 = now()
        val extra = try { block() } catch (e: Throwable) { "failed: ${e.javaClass.simpleName}" }
        return "$label: ${now() - t0} ms" + (extra?.let { "  ($it)" } ?: "")
    }

    /** Watches the screen for 3 seconds (how smoothly it draws), then times the main jobs. [done] runs on the main thread. */
    fun runSpeedTest(c: Context, done: (String) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        val gaps = ArrayList<Long>()
        val endAt = now() + 3000
        var last = 0L
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L) gaps += (frameTimeNanos - last) / 1_000_000
                last = frameTimeNanos
                if (now() < endAt) Choreographer.getInstance().postFrameCallback(this)
                else Thread { val r = report(app, gaps); main.post { done(r) } }.start()
            }
        })
    }

    private fun report(app: Context, gaps: List<Long>): String {
        val sb = StringBuilder()
        if (gaps.isNotEmpty()) {
            val slow = gaps.count { it > 24 }
            sb.appendLine("Smoothness at rest: %.1f ms a frame, worst %d ms, %d of %d frames slow".format(gaps.average(), gaps.maxOrNull() ?: 0L, slow, gaps.size))
        }
        sb.appendLine(timed("Scan installed apps") { AppListHelper.invalidate(); "${AppListHelper.loadLaunchableTvApps(app).size} apps" })
        sb.appendLine(timed("Installed apps (remembered)") { "${AppListHelper.loadLaunchableTvApps(app).size} apps" })
        sb.appendLine(timed("Draw 20 app tiles") {
            AppTiles.clear(); AppListHelper.loadLaunchableTvApps(app).take(20).forEach { AppTiles.forApp(app, it, 184, 110) }; null
        })
        sb.appendLine(timed("Read the saved TV guide") { LiveGuide.benchmarkDisk(app) })
        sb.appendLine(timed("Streaming services list") { "${WatchCatalog.providers(app).size} services" })
        return sb.toString().trim()
    }
}
