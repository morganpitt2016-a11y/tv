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
    /** The most it keeps on the TV (about half an hour of HD): once full, the oldest part is reused, like a tape loop. */
    private val cap: Long = (minOf(600L * 1024 * 1024, context.cacheDir.usableSpace / 3).coerceAtLeast(64L * 1024 * 1024) / 188L) * 188L

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
                    // Write round the loop: position = total so far, wrapped at the cap.
                    var off = 0
                    while (off < n) {
                        val phys = (written + off) % cap
                        val len = minOf((n - off).toLong(), cap - phys).toInt()
                        out.seek(phys); out.write(buf, off, len); off += len
                    }
                    synchronized(lock) { written += n; lock.notifyAll() }
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

    /** The oldest point still kept (the loop has written over anything earlier). */
    fun oldest(): Long = align((written - cap + 4 * bytesPerSecond).coerceAtLeast(0))

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
            pos = maxOf(startAt + dataSpec.position, oldest())
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
            if (pos < written - cap) pos = oldest()             // paused so long the loop caught up: carry on from the oldest kept
            val phys = pos % cap
            f.seek(phys)
            val n = f.read(buffer, offset, minOf(minOf(length.toLong(), written - pos), cap - phys).toInt())
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
