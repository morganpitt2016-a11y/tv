package com.morgan.minimallauncher

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max

/**
 * Listens through a finished recording, once, in the background - as fast as the TV can decode it, silently (a
 * hidden player whose "speaker" only measures loudness; nothing reaches the TV or the soundbar, no network).
 *
 * From the loudness, second by second:
 *  - ad breaks: UK channels separate adverts with brief silences, so a run of 3+ short silences 5-90 s apart,
 *    lasting 1-8 minutes in all, is a break. The player jumps over them.
 *  - highlights: for sport, the moments the crowd and commentators surge well above the match's usual level -
 *    built into a ~10 minute "just the highlights".
 * Saved next to the recording as <file>.json.
 */
object RecordingAnalyzer {
    data class Analysis(val ads: List<LongArray>, val highlights: List<LongArray>, val durationMs: Long)

    private val thread = HandlerThread("rec-analysis").apply { start() }
    private val h = Handler(thread.looper)
    @Volatile private var busy = false
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun sidecar(file: String) = File("$file.json")

    fun load(file: String): Analysis? = try {
        val o = JSONObject(sidecar(file).readText())
        fun ranges(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { longArrayOf(it.getLong(0), it.getLong(1)) } } }.orEmpty()
        Analysis(ranges("ads"), ranges("highlights"), o.optLong("duration"))
    } catch (e: Exception) { null }

    /** Every finished recording without an analysis yet, one at a time. */
    fun catchUp(c: Context) {
        Recordings.list(c).filter { it.done && File(it.file).exists() && !sidecar(it.file).exists() }.forEach { enqueue(c, it.file) }
    }

    fun enqueue(c: Context, file: String) {
        if (file !in queue) queue.add(file)
        next(c.applicationContext)
    }

    private fun next(app: Context) {
        if (busy) return
        val file = queue.poll() ?: return
        if (!File(file).exists() || sidecar(file).exists()) return next(app)
        busy = true
        h.post { analyse(app, file) }
    }

    // ---------- The silent player ----------

    /**
     * Loudness every 50 ms, placed by each buffer's own timestamp (the player's timeline - what a seek uses), so a gap,
     * a stream restart in the recording or a misread sample rate can't shift the ad times.
     */
    private class Meter {
        var rate = 48_000; var channels = 2
        private var sum = DoubleArray(20 * 60 * 30); private var cnt = IntArray(sum.size)
        var firstUs = Long.MIN_VALUE; var lastUs = 0L; var frames = 0L; var buffers = 0; var backJumps = 0
        private var prevUs = Long.MIN_VALUE
        fun configure(r: Int, ch: Int) { rate = r; channels = ch }
        private fun grow(i: Int) {
            if (i < sum.size) return
            val n = max(i + 1, sum.size * 2)
            sum = sum.copyOf(n); cnt = cnt.copyOf(n)
        }
        fun feed(buf: ByteBuffer, ptsUs: Long) {
            if (firstUs == Long.MIN_VALUE) firstUs = ptsUs
            if (prevUs != Long.MIN_VALUE && ptsUs < prevUs - 1_000_000L) backJumps++
            prevUs = ptsUs; buffers++
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var f = 0L
            while (b.remaining() >= 2 * channels) {
                var s = 0.0
                for (k in 0 until channels) { val v = b.short / 32768.0; s += v * v }
                val t = ptsUs - firstUs + f * 1_000_000L / rate
                if (t >= 0) { val i = (t / 50_000L).toInt(); grow(i); sum[i] += s / channels; cnt[i]++ }
                f++
            }
            frames += f
            lastUs = max(lastUs, ptsUs - firstUs + f * 1_000_000L / rate)
        }
        /** dB per 50 ms window; a window with no sound decoded counts as "not silent" (so a gap is never an ad). */
        fun windows(): List<Float> {
            val n = (lastUs / 50_000L).toInt().coerceAtMost(sum.size)
            return List(n) { i -> if (cnt[i] == 0) 0f else (10 * log10(max(sum[i] / cnt[i], 1e-10))).toFloat() }
        }
        val heardMs get() = lastUs / 1000
    }

    /** An audio "speaker" that plays instantly and silently: the player decodes as fast as it can. */
    private class MeterSink(private val m: Meter) : AudioSink {
        private var listener: AudioSink.Listener? = null
        private var lastUs = AudioSink.CURRENT_POSITION_NOT_SET.toLong(); private var endUs = 0L
        private var ended = false; private var params = PlaybackParameters.DEFAULT; private var attrs: AudioAttributes? = null
        private var frameUs = 0L
        override fun setListener(listener: AudioSink.Listener) { this.listener = listener }
        override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format) =
            if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_16BIT) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean) = if (lastUs < 0) AudioSink.CURRENT_POSITION_NOT_SET.toLong() else endUs
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) { m.configure(inputFormat.sampleRate, inputFormat.channelCount); ended = false }
        override fun play() {}
        override fun handleDiscontinuity() {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val frames = buffer.remaining() / (2 * m.channels)
            m.feed(buffer, presentationTimeUs)
            lastUs = presentationTimeUs
            endUs = presentationTimeUs + frames * 1_000_000L / m.rate
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() { ended = true }
        override fun isEnded() = ended
        override fun hasPendingData() = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { params = playbackParameters }
        override fun getPlaybackParameters() = params
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled() = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) { attrs = audioAttributes }
        override fun getAudioAttributes() = attrs
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun pause() {}
        override fun flush() { ended = false }
        override fun reset() { ended = false }
    }

    private fun analyse(app: Context, file: String) {
        val m = Meter()
        var player: ExoPlayer? = null
        val t0 = android.os.SystemClock.uptimeMillis()
        fun done(ok: Boolean, why: String = "") {
            val dur = player?.duration ?: C.TIME_UNSET
            player?.release(); player = null
            Diag.send(app, "recordings", "heard ${m.heardMs / 1000}s of ${if (dur > 0) "${dur / 1000}s" else "?"} · ${m.rate} Hz ${m.channels}ch · ${m.frames} frames in ${m.buffers} buffers · ${m.backJumps} time jumps · ${File(file).length() / 1_000_000} MB")
            if (ok) save(app, file, m)
            Diag.send(app, "recordings", if (ok) "analysed ${File(file).name} in ${(android.os.SystemClock.uptimeMillis() - t0) / 1000}s" else "couldn't analyse ${File(file).name}: $why")
            busy = false
            next(app)
        }
        try {
            val factory = object : DefaultRenderersFactory(app) {
                init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) }
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = MeterSink(m)
            }
            val p = ExoPlayer.Builder(app, factory).setLooper(thread.looper).setHandleAudioBecomingNoisy(false).build()
            player = p
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) done(true) }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { done(m.heardMs > 60_000L, error.errorCodeName) }
            })
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(file))))
            p.prepare(); p.play()
            // A safety stop (a broken file shouldn't hold the queue): keep what was heard.
            h.postDelayed({ if (player === p) done(m.heardMs > 60_000L, "took too long") }, 30 * 60_000L)
        } catch (e: Exception) { done(false, e.javaClass.simpleName) }
    }

    // ---------- Reading the loudness ----------

    private fun save(app: Context, file: String, m: Meter) {
        val db = m.windows()
        val ads = findAds(db)
        val highlights = findHighlights(db)
        val o = JSONObject().put("duration", db.size * 50L)
            .put("ads", JSONArray().apply { ads.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
            .put("highlights", JSONArray().apply { highlights.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
        try { sidecar(file).writeText(o.toString()) } catch (_: Exception) {}
        Diag.send(app, "recordings", "${File(file).name}: ${ads.size} ad breaks (${ads.sumOf { it[1] - it[0] } / 60000} min), ${highlights.size} highlights")
    }

    /** Ad breaks: clusters of brief silences (every advert starts and ends with one). Times in ms. */
    private fun findAds(db: List<Float>): List<LongArray> {
        val silences = ArrayList<Long>()
        var run = 0
        for (i in db.indices) {
            if (db[i] < -58f) run++ else { if (run in 3..60) silences += (i - run / 2) * 50L; run = 0 }
        }
        val out = ArrayList<LongArray>()
        var i = 0
        while (i < silences.size) {
            var j = i
            while (j + 1 < silences.size && silences[j + 1] - silences[j] in 5_000L..90_000L) j++
            val span = silences[j] - silences[i]
            if (j - i + 1 >= 3 && span in 60_000L..480_000L) out += longArrayOf(silences[i], silences[j])
            i = j + 1
        }
        return out
    }

    /** Sport highlights: the loudest surges above the match's own rolling level, ~10 minutes in all. */
    private fun findHighlights(db: List<Float>): List<LongArray> {
        if (db.size < 20 * 60 * 10) return emptyList()                 // under 10 minutes: nothing to cut down
        val sec = FloatArray(db.size / 20) { s -> var a = 0f; for (k in 0 until 20) a += db[s * 20 + k]; a / 20 }
        val excite = FloatArray(sec.size)
        val win = 90
        for (s in sec.indices) {
            val from = max(0, s - win); val to = minOf(sec.size, s + win)
            val sorted = sec.copyOfRange(from, to).sorted()
            excite[s] = sec[s] - sorted[sorted.size / 2]
        }
        // Peaks: 3 s of the surge sustained, at least 2 minutes apart, strongest first.
        val cand = ArrayList<Pair<Int, Float>>()
        for (s in 2 until sec.size - 2) {
            val v = (excite[s - 1] + excite[s] + excite[s + 1]) / 3
            if (v > 5f && (s == 0 || v >= (excite[s - 2] + excite[s - 1] + excite[s]) / 3)) cand += s to v
        }
        val picked = ArrayList<Int>()
        for ((s, _) in cand.sortedByDescending { it.second }) {
            if (picked.size >= 20) break
            if (picked.none { kotlin.math.abs(it - s) < 120 }) picked += s
        }
        // Each moment: 25 s of build-up before the roar, 12 s after.
        val segs = picked.sorted().map { longArrayOf(max(0, it - 25) * 1000L, minOf(sec.size, it + 12) * 1000L) }
        val merged = ArrayList<LongArray>()
        for (sg in segs) { val last = merged.lastOrNull(); if (last != null && sg[0] <= last[1]) last[1] = max(last[1], sg[1]) else merged += sg }
        return merged
    }
}
