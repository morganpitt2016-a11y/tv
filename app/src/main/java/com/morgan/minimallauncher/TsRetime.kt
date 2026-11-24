package com.morgan.minimallauncher

import java.io.File
import java.io.RandomAccessFile

/**
 * Makes a recording's clock run smoothly from start to end.
 *
 * When the Dodgy Box stream drops mid-recording, the recorder reconnects and carries on appending - but the new
 * stream starts its own clock again, so the file's timestamps jump backwards (or leap forwards). Players then
 * think the recording is hours long, stop early or can't seek, and the ad-break finder loses its place.
 *
 * This walks the file once, in place (same size - no spare space needed), and shifts every timestamp after a jump
 * so it carries straight on from just before it: PTS/DTS in every PES header and the PCR in adaptation fields.
 */
object TsRetime {
    private const val MASK = (1L shl 33) - 1
    private const val HZ = 90_000L

    /**
     * One correction for the whole file. Joins are spotted per stream (each PID's own timestamps, so a stream on its
     * own timebase, or a sparse one like subtitles, can't trigger it):
     *  - a step back of 5-60 s: the server restarted us a little behind live, so that stretch would play twice -
     *    those packets become null packets (players skip them) until each stream passes where it was; sound carries
     *    straight on, the picture at its next keyframe;
     *  - anything bigger (a new clock), or the PCR leaping 10 s+ on: everything after is re-timed to carry straight on.
     */
    private class Fixer {
        var offset = 0L; var jumps = 0; var dups = 0; var dropped = 0L; private var seg = 0
        private val lastRaw = HashMap<Int, Long>(); private val lastSeg = HashMap<Int, Int>()
        private val pes = HashSet<Int>(); private val video = HashSet<Int>()
        private var dup = false; private var caught = false; private var tKey = 0; private var target = 0L
        private var after = HashMap<Int, Long>(); private val pending = HashSet<Int>()

        private fun detect(key: Int, raw: Long, pcr: Boolean) {
            val prev = lastRaw[key]
            if (prev != null && lastSeg[key] == seg) {
                val d = signed(raw - prev)
                if (d < -5 * HZ || (pcr && d > 10 * HZ)) {
                    dup = false
                    if (d > -60 * HZ && d < -5 * HZ) {
                        dup = true; caught = false; tKey = key; target = prev; dups++
                        after = HashMap(); for (p in pes) { val k = p * 4; if (lastSeg[k] == seg) lastRaw[k]?.let { after[p] = it } }
                        pending.clear(); pending.addAll(pes)
                    } else {
                        offset = (prev + offset + HZ / 25 - raw) and MASK      // carry on 40 ms after where this stream was
                        jumps++
                    }
                    seg++
                }
            }
            lastRaw[key] = raw; lastSeg[key] = seg
        }

        /** Fixes one 188-byte packet at [o] in place. */
        fun packet(b: ByteArray, o: Int) {
            val pusi = (b[o + 1].toInt() and 0x40) != 0
            val pid = ((b[o + 1].toInt() and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
            val afc = (b[o + 3].toInt() shr 4) and 3
            val end = o + 188
            var i = o + 4
            var pcrAt = -1; var rai = false; var ptsAt = -1; var dtsAt = -1
            if (afc and 2 != 0) {
                val al = b[i].toInt() and 0xFF
                if (al > 0 && i + 1 < end) {
                    rai = (b[i + 1].toInt() and 0x40) != 0
                    if (al >= 7 && i + 7 < end && (b[i + 1].toInt() and 0x10) != 0) pcrAt = i + 2
                }
                i += 1 + al
            }
            if (afc and 1 != 0 && pusi && i + 14 <= end && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val sid = b[i + 3].toInt() and 0xFF
                if (sid !in NO_HEADER && (b[i + 6].toInt() and 0xC0) == 0x80) {
                    pes += pid
                    if (sid in 0xE0..0xEF) video += pid
                    val fl = (b[i + 7].toInt() shr 6) and 3
                    if (fl >= 2) ptsAt = i + 9
                    if (fl == 3 && i + 19 <= end) dtsAt = i + 14
                }
            }
            val pcr = if (pcrAt >= 0) readPcr(b, pcrAt) else -1L
            if (pcr >= 0) detect(pid * 4 + 3, pcr, true)
            val pts = if (ptsAt >= 0) readTs(b, ptsAt) else -1L
            if (pts >= 0) detect(pid * 4, pts, false)
            val dts = if (dtsAt >= 0) readTs(b, dtsAt) else -1L
            if (dts >= 0) detect(pid * 4 + 1, dts, false)

            if (dup && pid in pes) {
                var keep = false
                if (!caught) {
                    val v = when (tKey) { pid * 4 + 3 -> pcr; pid * 4 -> pts; pid * 4 + 1 -> dts; else -> -1L }
                    if (v >= 0 && signed(v - target) >= 0) caught = true
                }
                if (caught) {
                    if (pid !in pending) keep = true
                    else if (pts >= 0) {
                        val a = after[pid]
                        val late = a == null || signed(pts - a) > 0
                        if (late && (pid !in video || rai || (a != null && signed(pts - a) > 10 * HZ))) { keep = true; pending.remove(pid) }
                    }
                    if (pending.isEmpty()) dup = false
                }
                if (!keep) {
                    b[o + 1] = ((b[o + 1].toInt() and 0xE0) or 0x1F).toByte(); b[o + 2] = 0xFF.toByte()   // null packet
                    dropped++
                    return
                }
            }
            if (offset != 0L) {
                if (pcrAt >= 0) writePcr(b, pcrAt, (pcr + offset) and MASK)
                if (ptsAt >= 0) writeTs(b, ptsAt, (pts + offset) and MASK)
                if (dtsAt >= 0) writeTs(b, dtsAt, (dts + offset) and MASK)
            }
        }
        val changed get() = jumps + dups
    }

    private val NO_HEADER = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)
    private fun signed(d: Long): Long { val x = d and MASK; return if (x >= (1L shl 32)) x - (1L shl 33) else x }

    private fun readPcr(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 25) or ((b[p + 1].toLong() and 0xFF) shl 17) or
            ((b[p + 2].toLong() and 0xFF) shl 9) or ((b[p + 3].toLong() and 0xFF) shl 1) or ((b[p + 4].toLong() and 0xFF) shr 7)

    private fun writePcr(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 25).toByte(); b[p + 1] = (v shr 17).toByte(); b[p + 2] = (v shr 9).toByte(); b[p + 3] = (v shr 1).toByte()
        b[p + 4] = (((v and 1).toInt() shl 7) or (b[p + 4].toInt() and 0x7F)).toByte()
    }

    private fun readTs(b: ByteArray, i: Int): Long =
        (((b[i].toLong() shr 1) and 7) shl 30) or ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[i + 3].toLong() and 0xFF) shl 7) or ((b[i + 4].toLong() and 0xFF) shr 1)

    private fun writeTs(b: ByteArray, i: Int, v: Long) {
        b[i] = ((b[i].toInt() and 0xF0) or (((v shr 30) and 7).toInt() shl 1) or 1).toByte()
        b[i + 1] = ((v shr 22) and 0xFF).toByte()
        b[i + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[i + 3] = ((v shr 7) and 0xFF).toByte()
        b[i + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
    }

    data class Result(val restarts: Int, val repeats: Int, val droppedMb: Int)

    /** Smooths the file in place; returns what it found (all zero = the file was already fine and wasn't touched). */
    fun repair(file: File): Result {
        if (count(file) == 0) return Result(0, 0, 0)
        val clock = Fixer()
        RandomAccessFile(file, "rw").use { f ->
            val len = f.length()
            val buf = ByteArray(188 * 2048 + 188)
            var base = 0L
            while (base < len) {
                f.seek(base)
                val n = f.read(buf, 0, minOf(buf.size.toLong(), len - base).toInt())
                if (n <= 0) break
                var p = 0
                // Whole packets only; a stray partial packet at a join is skipped by finding the next sync byte.
                while (p + 188 <= n) {
                    if (buf[p].toInt() == 0x47 && (p + 188 == n || buf[p + 188].toInt() == 0x47)) { clock.packet(buf, p); p += 188 }
                    else p++
                }
                f.seek(base); f.write(buf, 0, p)
                if (p == 0) break
                base += p
                if (n < buf.size && base + 188 > len) break
            }
        }
        return Result(clock.jumps, clock.dups, (clock.dropped * 188 / 1_000_000).toInt())
    }

    /** A read-only pass: how many jumps the file has. */
    fun count(file: File): Int {
        val clock = Fixer()
        java.io.BufferedInputStream(file.inputStream(), 1 shl 20).use { input ->
            val buf = ByteArray(188 * 2048 + 188)
            var have = 0
            while (true) {
                val n = input.read(buf, have, buf.size - have)
                if (n <= 0) break
                have += n
                var p = 0
                while (p + 188 <= have) {
                    if (buf[p].toInt() == 0x47 && (p + 188 == have || buf[p + 188].toInt() == 0x47)) { clock.packet(buf, p); p += 188 }
                    else p++
                }
                System.arraycopy(buf, p, buf, 0, have - p); have -= p
            }
        }
        return clock.changed
    }
}
