package com.morgan.minimallauncher

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import java.nio.ByteBuffer

/**
 * Picture colour for video the lights can't read off the screen (10-bit: reading the screen cost the film frames).
 *
 * Each keyframe - the complete picture the encoder puts at every new shot - is copied as it goes into the TV's own
 * decoder (bytes the player already has: no extra connection, nothing changes in playback), and decoded small in
 * the background by Android's software decoder at the lowest priority. One true colour per shot, in time with it.
 * If the software decoder is missing or too slow on a title, it stops for that title (the lights stay a warm glow).
 */
class KeyframeTap(private val onPicture: (IntArray, Int, Long) -> Unit, private val log: (String) -> Unit) {
    companion object {
        const val W = 96; const val H = 54
        private const val SW = "c2.android.hevc.decoder"
        val available: Boolean by lazy {
            try { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { it.name == SW } } catch (e: Exception) { false }
        }
    }

    private val thread = HandlerThread("keyframe-tap", Process.THREAD_PRIORITY_LOWEST).apply { start() }
    private val h = Handler(thread.looper)
    @Volatile private var format: Format? = null
    private var codec: MediaCodec? = null
    private var codecFor: Format? = null
    @Volatile private var busy = false
    /** On only while the picture can't be read any other way (tunnelled 10-bit video). */
    @Volatile var enabled = false
    @Volatile private var off = false
    private var done = 0; private var slow = 0; private var totalMs = 0L
    private var seen = 0; private var notes = 0
    private fun note(msg: String) { if (notes++ < 12) log("keyframe: $msg") }

    fun reset() { off = false; done = 0; slow = 0; totalMs = 0L; seen = 0; notes = 0; h.post { release() } }

    fun onFormat(f: Format) { if (f.sampleMimeType == "video/hevc") { format = f; note("format ${f.codecs} ${f.width}x${f.height}, csd ${f.initializationData.size} parts") } }

    /** From the video renderer, for every sample going into the decoder. */
    fun onSample(isKey: Boolean, data: ByteBuffer?, timeUs: Long) {
        if (!enabled || off || !isKey || data == null || !available) return
        seen++
        if (seen <= 2) note("keyframe #$seen at ${timeUs / 1000} ms, ${data.remaining()} bytes, busy=$busy, format=${format != null}")
        if (busy) return
        val f = format ?: return
        val bytes = try { ByteArray(data.remaining()).also { data.duplicate().get(it) } } catch (e: Throwable) { return }
        if (bytes.isEmpty()) return
        busy = true
        h.post {
            try { decode(f, bytes, timeUs) }
            catch (e: IllegalStateException) { note("decoder state ${e.message} - starting it again"); release() }
            catch (e: Throwable) { off = true; log("keyframe colour off for this title: ${e.javaClass.simpleName}: ${e.message}"); release() }
            finally { busy = false }
        }
    }

    private fun decode(f: Format, bytes: ByteArray, timeUs: Long) {
        val t0 = SystemClock.uptimeMillis()
        val c = codec?.takeIf { codecFor === f } ?: run {
            release()
            MediaCodec.createByCodecName(SW).also { cc ->
                cc.configure(MediaFormatUtil.createMediaFormatFromFormat(f), null, null, 0)
                cc.start(); codec = cc; codecFor = f
                note("software decoder started (${cc.name}) for ${f.width}x${f.height}")
            }
        }
        val ii = c.dequeueInputBuffer(50_000)
        if (ii < 0) { note("no decoder input slot"); c.flush(); return }
        val ib = c.getInputBuffer(ii) ?: return
        ib.clear()
        if (bytes.size > ib.capacity()) { note("keyframe ${bytes.size} bytes > input ${ib.capacity()}"); c.flush(); return }
        ib.put(bytes)
        c.queueInputBuffer(ii, 0, bytes.size, timeUs, 0)
        val ei = c.dequeueInputBuffer(50_000)
        if (ei >= 0) c.queueInputBuffer(ei, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val info = MediaCodec.BufferInfo()
        while (SystemClock.uptimeMillis() - t0 < 800) {
            val oi = c.dequeueOutputBuffer(info, 20_000)
            if (oi < 0) continue
            val img = try { c.getOutputImage(oi) } catch (e: Exception) { null }
            if (img != null && info.size > 0) {
                val px = sample(img)
                val size = "${img.width}x${img.height}"
                img.close(); c.releaseOutputBuffer(oi, false); c.flush()
                val ms = SystemClock.uptimeMillis() - t0
                done++; totalMs += ms
                if (done <= 3 || done % 100 == 0) log("keyframe colour: ${ms} ms (avg ${totalMs / done} ms over $done) $size")
                // Too slow to be in time with the shots (or heavy on the TV): stop for this title.
                if (ms > 450) { slow++; if (slow >= 3) { off = true; log("keyframe colour off: too slow ($ms ms)"); release(); return } }
                onPicture(px, W, timeUs)
                return
            }
            img?.close(); c.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { note("decoder gave no picture (end of stream, size ${info.size}, image ${img != null})"); break }
        }
        if (SystemClock.uptimeMillis() - t0 >= 800) note("decoder timed out (no picture in 800 ms)")
        c.flush()
    }

    /** A 96x54 ARGB picture from the decoded frame (8-bit or 10-bit), BT.709 limited range. */
    private fun sample(img: Image): IntArray {
        val out = IntArray(W * H)
        val crop = img.cropRect
        val p = img.planes
        val tenBit = img.format == ImageFormat.YCBCR_P010 || p[0].pixelStride == 2
        fun read(plane: Image.Plane, x: Int, y: Int): Int {
            val b = plane.buffer
            val i = y * plane.rowStride + x * plane.pixelStride
            if (i < 0 || i >= b.limit()) return 128
            return if (tenBit) (b.get(i + 1).toInt() and 0xFF) else (b.get(i).toInt() and 0xFF)
        }
        for (gy in 0 until H) for (gx in 0 until W) {
            val x = crop.left + (gx * 2 + 1) * crop.width() / (W * 2)
            val y = crop.top + (gy * 2 + 1) * crop.height() / (H * 2)
            val yy = (read(p[0], x, y) - 16) / 219f
            val u = (read(p[1], x / 2, y / 2) - 128) / 224f
            val v = (read(p[2], x / 2, y / 2) - 128) / 224f
            val r = yy + 1.5748f * v; val g = yy - 0.1873f * u - 0.4681f * v; val bl = yy + 1.8556f * u
            fun c8(x: Float) = (x * 255f).toInt().coerceIn(0, 255)
            out[gy * W + gx] = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(bl)
        }
        return out
    }

    private fun release() { try { codec?.release() } catch (_: Exception) {}; codec = null; codecFor = null }

    fun shutdown() { h.post { release(); thread.quitSafely() } }
}
