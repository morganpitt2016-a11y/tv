package com.morgan.minimallauncher

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pause and rewind live TV. While a channel is on, its stream is saved to a file on the TV as it
 * arrives, and the player reads from that file instead of straight from the internet - so it can
 * stop (the recording carries on), go back, and catch up to live again. Only one copy of the
 * channel is ever downloaded, so it uses no more of the Dodgy Box connection than before.
 */
class Timeshift(context: Context, val url: String) {
    private val file = File(context.cacheDir, "timeshift.ts")
    private val cap: Long = minOf(1_200L * 1024 * 1024, (context.cacheDir.usableSpace / 2).coerceAtLeast(64L * 1024 * 1024))

    @Volatile var written = 0L; private set
    @Volatile var failed: String? = null; private set
    @Volatile private var stopped = false
    @Volatile var full = false; private set
    private val startedAt = System.currentTimeMillis()
    private val lock = Object()
    private var thread: Thread? = null

    /** Rough bytes per second of this channel (for turning "30 seconds" into a place in the file). */
    val bytesPerSecond: Long get() {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1)
        return (written / secs).coerceAtLeast(150_000L)
    }

    fun start() {
        file.delete()
        thread = Thread({ download() }, "timeshift").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
        synchronized(lock) { lock.notifyAll() }
        file.delete()
    }

    private fun download() {
        var conn: HttpURLConnection? = null
        try {
            var target = URL(url)
            repeat(5) {
                val c = target.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", Xtream.PLAYER_AGENT)
                val code = c.responseCode
                if (code in 300..399) { target = URL(target, c.getHeaderField("Location") ?: return@repeat); c.disconnect() }
                else { conn = c; if (code !in 200..299) { failed = "HTTP $code"; return }; return@repeat }
            }
            val input = conn?.inputStream ?: run { failed = "no stream"; return }
            RandomAccessFile(file, "rw").use { out ->
                val buf = ByteArray(64 * 1024)
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    synchronized(lock) { written += n; lock.notifyAll() }
                    if (written >= cap) { full = true; break }
                }
            }
        } catch (e: Exception) {
            if (!stopped) failed = e.javaClass.simpleName
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Waits (a little) until there's data past [pos]; false if there never will be. */
    fun waitFor(pos: Long, timeoutMs: Long = 15_000): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (written <= pos && !stopped && failed == null && !full) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(minOf(left, 200))
            }
        }
        return written > pos
    }

    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        RandomAccessFile(file, "r").use { f ->
            f.seek(pos)
            return f.read(buf, off, minOf(len.toLong(), written - pos).toInt())
        }
    }

    /** The newest point to play from (a couple of seconds back, so it doesn't stall on the very edge). */
    fun liveEdge(): Long = align((written - 2 * bytesPerSecond).coerceAtLeast(0))

    /** TS packets are 188 bytes: start on a packet boundary. */
    fun align(pos: Long): Long = (pos / 188L) * 188L

    /** The player's data source: reads the saved stream from [startAt], waiting for more as it arrives. */
    inner class Source(private val startAt: Long) : BaseDataSource(true) {
        private var pos = 0L
        private var uri: Uri? = null
        private var raf: RandomAccessFile? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            pos = startAt + dataSpec.position
            transferInitializing(dataSpec)
            if (!waitFor(pos)) throw java.io.IOException(failed ?: "No picture yet")
            raf = RandomAccessFile(file, "r")
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!waitFor(pos)) return if (full || failed != null || stopped) C.RESULT_END_OF_INPUT else throw java.io.IOException("Stream stalled")
            val f = raf ?: return C.RESULT_END_OF_INPUT
            f.seek(pos)
            val n = f.read(buffer, offset, minOf(length.toLong(), written - pos).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
            pos += n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri
        override fun close() { try { raf?.close() } catch (e: Exception) {}; raf = null; if (uri != null) { uri = null; transferEnded() } }
    }

    fun factory(startAt: Long) = DataSource.Factory { Source(startAt) }
}
