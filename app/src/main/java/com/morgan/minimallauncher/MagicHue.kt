package com.morgan.minimallauncher

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Magic Hue (Magic Home / LEDENET) Wi-Fi LED controllers, driven directly over the home network - no cloud, no app,
 * fast enough (40+ changes a second) to follow the picture. Found by their UDP "HF-A11ASSISTHREAD" broadcast;
 * colours go over TCP port 5577.
 *
 * Used for TV Colour Lights: while a film or channel plays in the launcher's own player, the lights take on the
 * colours on screen (see [TvColourSync]); afterwards they go back to how they were.
 */
object MagicHue {
    private const val KEY_IP = "magichue_ip"
    private const val KEY_SYNC = "magichue_sync"
    private const val PORT = 5577

    fun ip(c: Context): String? = Prefs.string(c, KEY_IP)
    fun setIp(c: Context, ip: String?) = Prefs.setString(c, KEY_IP, ip)
    fun syncOn(c: Context) = Prefs.bool(c, KEY_SYNC, false) && ip(c) != null
    /** Cinema Mode has switched the lights off: nothing turns them back on until it ends. */
    fun allowed(c: Context) = !RoomLights.isDown(c)

    /** Cinema Mode: remember the lights' colour, then switch them off. */
    fun switchOff(c: Context) {
        val host = ip(c) ?: return
        val app = c.applicationContext
        Thread {
            val l = Link(host)
            l.begin(powerOn = false)
            Thread.sleep(700)
            l.before?.third?.let { Prefs.setString(app, "mh_before", it.joinToString(",")) }
            l.asleep = true
            l.end()
        }.start()
    }

    /** Cinema Mode over: switch the lights back on in the colour they had. */
    fun switchBackOn(c: Context) {
        val host = ip(c) ?: return
        val app = c.applicationContext
        val saved = Prefs.string(app, "mh_before")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 } ?: return
        Prefs.setString(app, "mh_before", null)
        Thread {
            val l = Link(host)
            l.begin()
            Thread.sleep(500)
            l.colour(saved[0], saved[1], saved[2])
            Thread.sleep(400)
            l.hangUp()
        }.start()
    }
    fun setSync(c: Context, on: Boolean) = Prefs.setBool(c, KEY_SYNC, on)

    /** Every Magic Hue controller answering on this network: IP addresses (blocking, about 2 seconds). */
    fun discover(): List<String> {
        val found = linkedSetOf<String>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 400
                val ask = "HF-A11ASSISTHREAD".toByteArray()
                repeat(3) { s.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), 48899)) }
                val end = System.currentTimeMillis() + 2000
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                    val reply = String(p.data, 0, p.length)
                    // "192.168.1.127,2CF432D335F7,AK001-ZJ2101" (our own broadcast echoes back too - skip it)
                    if (reply.contains(',')) found += reply.substringBefore(',')
                }
            }
        } catch (_: Exception) {}
        return found.toList()
    }

    /** Anything colours can be sent to. */
    interface Sink { fun colour(r: Int, g: Int, b: Int) }

    /** A connection to one controller. All calls happen on its own thread, in order. */
    /**
     * ONE connection to the controller, shared by everything that drives the lights (films, Cinema Room, wallpaper,
     * soundscapes, calibration, Lights Down). These controllers jam when connections keep opening and closing on them,
     * and when commands arrive faster than they can take them - so all of it goes down one line, paced.
     */
    private object Line {
        var host: String? = null
        var sock: Socket? = null
        var out: OutputStream? = null
        var input: InputStream? = null
        var users = 0
        var lastSend = 0L

        @Synchronized fun ensure(h: String, log: (String) -> Unit): Boolean {
            if (host == h && sock?.isConnected == true && sock?.isClosed == false) return true
            drop()
            return try {
                val s = Socket(); s.tcpNoDelay = true; s.soTimeout = 1500
                s.connect(InetSocketAddress(h, PORT), 1500)
                host = h; sock = s; out = s.getOutputStream(); input = s.getInputStream(); true
            } catch (e: Exception) { log("Magic Hue: can't connect to $h (${e.javaClass.simpleName}: ${e.message})"); drop(); false }
        }

        @Synchronized fun send(b: IntArray) {
            // No more than ~25 commands a second: faster than that and the controller starts dropping or jamming.
            val wait = 40L - (android.os.SystemClock.uptimeMillis() - lastSend)
            if (wait > 0) Thread.sleep(wait)
            val bytes = ByteArray(b.size + 1)
            var sum = 0
            for (i in b.indices) { bytes[i] = b[i].toByte(); sum += b[i] }
            bytes[b.size] = (sum and 0xFF).toByte()
            try { out?.write(bytes); out?.flush() } catch (e: Exception) { drop() }
            lastSend = android.os.SystemClock.uptimeMillis()
        }

        @Synchronized fun state(): Triple<Boolean, Int, IntArray>? = try {
            drain()
            send(intArrayOf(0x81, 0x8A, 0x8B))
            val r = ByteArray(14); var n = 0
            while (n < 14) { val k = input!!.read(r, n, 14 - n); if (k < 0) break; n += k }
            if (n >= 9) Triple(r[2].toInt() and 0xFF == 0x23, r[3].toInt() and 0xFF, intArrayOf(r[6].toInt() and 0xFF, r[7].toInt() and 0xFF, r[8].toInt() and 0xFF)) else null
        } catch (_: Exception) { null }

        @Synchronized fun drain() { try { val i = input ?: return; while (i.available() > 0) i.skip(i.available().toLong()) } catch (_: Exception) {} }

        @Synchronized fun acquire() { users++ }
        /** The last user gone: hang up a moment later (unless someone else picks the line up meanwhile). */
        @Synchronized fun release() {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) Thread { Thread.sleep(3000); synchronized(this) { if (users == 0) drop() } }.start()
        }

        @Synchronized fun drop() {
            try { sock?.close() } catch (_: Exception) {}
            sock = null; out = null; input = null
        }
    }

    class Link(private val host: String, private val log: (String) -> Unit = {}) : Sink {
        private val exec = Executors.newSingleThreadExecutor()
        @Volatile private var pending: IntArray? = null
        @Volatile private var busy = false
        @Volatile private var holding = false

        /** How the lights were before we started (on/off and colour), to put them back afterwards. */
        var before: Triple<Boolean, Int, IntArray>? = null
            private set

        private fun ensure(): Boolean {
            if (!holding) { holding = true; Line.acquire() }
            return Line.ensure(host, log)
        }

        private fun send(vararg b: Int) = Line.send(b)

        /** Remember the current state, then switch the lights on. */
        fun begin(powerOn: Boolean = true) = exec.execute {
            if (!ensure()) return@execute
            before = Line.state()
            // Some controllers (AK001) report on/off unreliably, so just ask for "on" once and carry on.
            if (powerOn) send(0x71, 0x23, 0x0F)
            Thread.sleep(250)
            Line.drain()
            log("Magic Hue: connected (colour before: ${before?.third?.joinToString(",")})")
        }

        /** The next colour to show. Only the newest is sent if the lights fall behind - never a queue of old ones. */
        override fun colour(r: Int, g: Int, b: Int) {
            pending = intArrayOf(r, g, b)
            if (busy) return
            busy = true
            if (exec.isShutdown) { busy = false; return }
            try { exec.execute {
                try {
                    while (true) {
                        val c = pending ?: break
                        pending = null
                        if (!ensure()) break
                        // 0x41: change colour without saving it to the controller's memory (spares its flash).
                        send(0x41, c[0], c[1], c[2], 0x00, 0xF0, 0x0F)
                    }
                } finally { busy = false }
            } } catch (e: java.util.concurrent.RejectedExecutionException) { busy = false }   // hung up meanwhile
        }

        /** You fell asleep watching: when this hangs up, the lights switch off instead of going back as they were. */
        @Volatile var asleep = false

        /** Put the lights back to the colour they had, then hang up (on/off is left alone - it can't be trusted). */
        fun end() = exec.execute {
            val b = before
            if (asleep) { if (ensure()) send(0x71, 0x24, 0x0F) }
            else if (ensure() && b != null) send(0x31, b.third[0], b.third[1], b.third[2], 0x00, 0xF0, 0x0F)
            Thread.sleep(150)
            close()
            exec.shutdown()
        }

        /** Hang up without changing the lights (the next thing to use them carries on from here). */
        fun hangUp() = exec.execute { close(); exec.shutdown() }

        private fun close() { if (holding) { holding = false; Line.release() } }
    }
}
