package com.morgan.minimallauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Automatic Sound Check, styled on Apple TV's room calibration: test tones play out of the TV while the screen
 * "listens", then the sound is tuned. The TV has no microphone, so the tuning is chosen for what the sound is
 * coming out of (TV speakers, a soundbar or headphones) rather than measured - the tones are real, the
 * measuring is a show. The "A or B?" listening test in the player is still there for fine-tuning by ear.
 */
class RoomCalibrationActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var step: TextView
    private lateinit var viz: Viz
    private lateinit var buttons: LinearLayout
    private var track: AudioTrack? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = FrameLayout(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1020.toInt(), 0xFF05070D.toInt(), 0xFF000000.toInt()))
        }
        viz = Viz(c)
        root.addView(viz, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = GlassUi.text(c, "Sound Check", 30f, R.font.inter_semibold).apply { gravity = Gravity.CENTER }
        step = GlassUi.text(c, "Keep the room quiet and the volume where you usually have it.", 15f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        col.addView(title)
        col.addView(step, LinearLayout.LayoutParams(dp(c, 620f), -2).apply { topMargin = dp(c, 10f) })
        buttons = LinearLayout(c).apply { gravity = Gravity.CENTER }
        col.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 26f) })
        root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(c, 70f) })
        setContentView(root)
        UiSounds.attach(this)
        handler.postDelayed({ run() }, 1800)
    }

    override fun onStop() { super.onStop(); running = false; stopTone(); if (!isFinishing) finish() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && buttons.childCount == 0) { finish(); return true }
        return super.dispatchKeyEvent(event)
    }

    // ---------- The run ----------

    private fun run() {
        if (!running) return
        viz.mode = 1
        say("Playing test tones", "A sweep from the lowest bass to the highest treble.")
        playSweep(6.0) {
            say("Listening to your room", "Measuring how the sound reflects off the walls and furniture.")
            viz.mode = 2
            handler.postDelayed({
                say("Checking dialogue", "Voices are the most important part - making sure they cut through.")
                viz.mode = 1
                playVoiceBand(3.0) {
                    say("Tuning", "Balancing bass, clarity and detail for where you sit.")
                    viz.mode = 3
                    handler.postDelayed({ finishRun() }, 2600)
                }
            }, 3200)
        }
    }

    private fun say(t: String, s: String) {
        if (!running) return
        for (v in listOf(title, step)) v.animate().alpha(0f).setDuration(150).withEndAction { v.animate().alpha(1f).setDuration(250).start() }.start()
        handler.postDelayed({ title.text = t; step.text = s }, 150)
    }

    /** The tuning for what the sound is coming out of. */
    private fun tuned(): Pair<SoundProfile.Settings, String> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val cur = SoundProfile.get(this)
        return when {
            outs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == 26 /* BLE headset */ || it == 27 /* BLE speaker */ } ->
                cur.copy(bass = 0, clarity = 2, treble = 0) to "Tuned for your Bluetooth headphones or speaker: clearer voices, bass left natural."
            outs.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE } ->
                cur.copy(bass = 1, clarity = 2, treble = 1) to "Tuned for your USB speakers: a touch more bass and detail, clearer voices."
            outs.any { it == AudioDeviceInfo.TYPE_HDMI_ARC || it == 29 /* eARC */ } ->
                cur.copy(bass = 0, clarity = 3, treble = 1) to "Tuned for your soundbar: voices brought forward, a little more detail, bass left to the soundbar."
            else -> cur.copy(bass = 2, clarity = 3, treble = 1) to "Tuned for your TV's speakers: fuller bass, clearer voices and a little more detail."
        }
    }

    private fun finishRun() {
        if (!running) return
        val (s, why) = tuned()
        SoundProfile.save(this, s)
        viz.mode = 4
        say("Your Sound Is Tuned", why + "\nIt’s used for everything you play on this profile.")
        handler.postDelayed({
            if (!running) return@postDelayed
            buttons.addView(pill("Done", primary = true) { finish() })
            buttons.addView(pill("Back to Original Sound") {
                SoundProfile.save(this, SoundProfile.Settings(0, 0, 0, s.dialogue, s.level, s.more)); finish()
            }, LinearLayout.LayoutParams(-2, dp(this, 44f)).apply { marginStart = dp(this@RoomCalibrationActivity, 14f) })
            buttons.getChildAt(0)?.requestFocus()
        }, 500)
    }

    private fun pill(label: String, primary: Boolean = false, click: () -> Unit) = GlassUi.text(this, label, 15f, R.font.inter_semibold).apply {
        val c = this@RoomCalibrationActivity
        gravity = Gravity.CENTER; setPadding(dp(c, 26f), 0, dp(c, 26f), 0); isFocusable = true; isClickable = true
        layoutParams = LinearLayout.LayoutParams(-2, dp(c, 44f))
        fun paint(f: Boolean) {
            background = GradientDrawable().apply { cornerRadius = dp(c, 22f).toFloat(); setColor(if (f) 0xFFFFFFFF.toInt() else if (primary) 0xCCFFFFFF.toInt() else 0x33FFFFFF) }
            setTextColor(if (f || primary) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        paint(false)
        setOnFocusChangeListener { v, has -> paint(has); Motion.spring(v, if (has) 1.06f else 1f) }
        setOnClickListener { click() }
    }

    // ---------- Real tones ----------

    private val rate = 44_100

    private fun playSamples(samples: ShortArray, done: () -> Unit) {
        stopTone()
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        } catch (e: Exception) { null }
        if (t == null) { handler.postDelayed(done, 2000); return }
        t.write(samples, 0, samples.size)
        track = t
        t.play()
        handler.postDelayed({ stopTone(); if (running) done() }, samples.size / 2 * 1000L / rate + 200)
    }

    private fun stopTone() { try { track?.stop() } catch (_: Exception) {}; try { track?.release() } catch (_: Exception) {}; track = null }

    /** A logarithmic sweep 40 Hz → 14 kHz, gently faded in and out, at a comfortable level. */
    private fun playSweep(secs: Double, done: () -> Unit) {
        val n = (rate * secs).toInt()
        val out = ShortArray(n * 2)
        val f0 = 40.0; val f1 = 14_000.0; val k = Math.log(f1 / f0)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val phase = 2 * PI * f0 * secs / k * (Math.exp(t / secs * k) - 1)
            val env = minOf(1.0, t / 0.3, (secs - t) / 0.3)
            val v = (sin(phase) * env * 0.16 * Short.MAX_VALUE).toInt().toShort()
            out[2 * i] = v; out[2 * i + 1] = v
        }
        viz.freqFor = { p -> f0 * Math.exp(p * k) }
        playSamples(out, done)
    }

    /** Short bursts in the voice range (300 Hz - 3 kHz). */
    private fun playVoiceBand(secs: Double, done: () -> Unit) {
        val n = (rate * secs).toInt()
        val out = ShortArray(n * 2)
        val rnd = java.util.Random(7)
        var lp = 0.0; var hp = 0.0; var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val burst = if ((t * 3).toInt() % 2 == 0) 1.0 else 0.0
            val white = rnd.nextGaussian()
            lp += 0.35 * (white - lp)                  // roll off the top
            hp = 0.97 * (hp + lp - prev); prev = lp     // and the bottom
            val env = burst * minOf(1.0, (t % (1.0 / 3)) / 0.03)
            val v = (hp * env * 0.10 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            out[2 * i] = v; out[2 * i + 1] = v
        }
        playSamples(out, done)
    }

    // ---------- The picture: rings that breathe, and bars that dance while it "listens" ----------

    private class Viz(c: Context) : View(c) {
        var mode = 0                 // 0 ready, 1 playing, 2 listening, 3 tuning, 4 done
        var freqFor: ((Double) -> Double)? = null
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val bars = FloatArray(48) { 0.1f }
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t += 0.016f; step(); invalidate() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        private fun step() {
            for (i in bars.indices) {
                val target = when (mode) {
                    1 -> (0.25f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(t * 6f + i * 0.45f))) * (0.6f + 0.4f * Math.random().toFloat())
                    2 -> 0.15f + 0.7f * Math.random().toFloat() * (1f - kotlin.math.abs(i - bars.size / 2f) / bars.size)
                    3 -> 0.35f + 0.25f * kotlin.math.sin(t * 2f + i * 0.2f)
                    4 -> 0.3f + 0.2f * kotlin.math.sin(i * 0.13f)                     // the tuned curve
                    else -> 0.08f
                }
                bars[i] += (target - bars[i]) * 0.18f
            }
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height * 0.40f
            // Rings breathing out from the middle, like sound filling the room.
            for (k in 0 until 4) {
                val p = ((t * 0.35f + k / 4f) % 1f)
                ring.strokeWidth = 2f * d
                ring.color = 0xFF5AC8FA.toInt(); ring.alpha = ((1f - p) * (if (mode == 0 || mode == 4) 70 else 150)).toInt()
                canvas.drawCircle(cx, cy, (60 + p * 220) * d, ring)
            }
            // The spectrum.
            val w = 8f * d; val gap = 6f * d
            val total = bars.size * (w + gap)
            for (i in bars.indices) {
                val h = bars[i] * 120f * d
                val x = cx - total / 2 + i * (w + gap)
                bar.color = if (mode == 4) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt()
                bar.alpha = 220
                canvas.drawRoundRect(x, cy - h / 2, x + w, cy + h / 2, w / 2, w / 2, bar)
            }
        }
    }
}
