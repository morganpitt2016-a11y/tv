package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A little pixel cavapoo who lives along the top of the dock. He trots between
 * the apps, stops to sniff them, wags, pants, and curls up to sleep on top of
 * one. When you move onto an app he trots over to sniff it. (Drawn in code:
 * a 38x26 pixel sprite, scaled up with hard edges.)
 */
class DogPet(context: Context) : View(context) {

    private val SW = 38
    private val SH = 26
    private val sprite = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
    private val sc = Canvas(sprite)
    private val fill = Paint()                  // no anti-aliasing: hard pixel edges
    private val blit = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density
    private val k = 1.5f * d                    // screen pixels per sprite pixel

    private val FUR = 0xFFDB9B5C.toInt()
    private val FUR_D = 0xFFB87440.toInt()
    private val CREAM = 0xFFF5E3C6.toInt()
    private val OUT = 0xFF4A2C1A.toInt()
    private val DARK = 0xFF1C1210.toInt()
    private val COLLAR = 0xFFB3261E.toInt()
    private val GOLD = 0xFFE8B84A.toInt()
    private val TONGUE = 0xFFF28B9A.toInt()

    // The world: where he may walk, and where the apps are.
    private var minX = 0f
    private var maxX = 0f
    private var tiles: List<Float> = emptyList()

    // Life.
    private var x = -1f
    private var dir = 1f
    private var state = "idle"           // walk | sniff | idle | sleep
    private var timer = 1.5f
    private var targetX = 0f
    private var attending = false
    private var phase = 0f
    private var t = 0f
    private var blink = 3f
    private var zzz = 0f

    init {
        isFocusable = false
        isClickable = false
        text.typeface = GlassUi.font(context, R.font.inter_semibold)
        text.color = 0xFFFFFFFF.toInt()
    }

    private var running = false
    private val frame = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(now: Long) {
            if (last != 0L) { step(((now - last) / 1e9f).coerceAtMost(0.05f)); invalidate() }
            last = now
            if (running) Choreographer.getInstance().postFrameCallback(this) else last = 0L
        }
    }

    fun resume() { if (!running && visibility == VISIBLE) { running = true; Choreographer.getInstance().postFrameCallback(frame) } }
    fun pause() { running = false }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); pause() }

    /** The stretch of dock he can walk along, and the middle of each app on it. */
    fun setWorld(min: Float, max: Float, appCentres: List<Float>) {
        minX = min; maxX = max; tiles = appCentres
        if (x < 0f) { x = (min + max) / 2f; targetX = x }
        x = x.coerceIn(min, max)
        targetX = targetX.coerceIn(min, max)
    }

    /** You've moved onto an app: he trots over to sniff it. */
    fun attend(cx: Float) {
        if (tiles.isEmpty()) return
        targetX = cx.coerceIn(minX, maxX)
        attending = true
        state = "walk"
    }

    // ---------- Behaviour ----------

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    private fun randomApp(): Float {
        if (tiles.isEmpty()) return (minX + maxX) / 2f
        return (tiles.random() + (Random.nextFloat() - 0.5f) * 14f * d).coerceIn(minX, maxX)
    }

    private fun step(dt: Float) {
        t += dt
        blink -= dt
        if (blink < -0.12f) blink = 2f + Random.nextFloat() * 4f
        if (x < 0f) return

        when (state) {
            "walk" -> {
                val dx = targetX - x
                val speed = 30f * d * (if (attending) 1.5f else 1f)
                if (abs(dx) <= speed * dt) {
                    x = targetX
                    // Arrived at an app.
                    when {
                        attending -> { attending = false; state = "sniff"; timer = 2.5f + Random.nextFloat() * 2f }
                        isNight() -> { state = "sleep"; timer = 60f + Random.nextFloat() * 60f }
                        else -> {
                            val r = Random.nextFloat()
                            when {
                                r < 0.5f -> { state = "sniff"; timer = 2f + Random.nextFloat() * 2f }
                                r < 0.75f -> { state = "sleep"; timer = 10f + Random.nextFloat() * 12f }
                                else -> { state = "idle"; timer = 1.5f + Random.nextFloat() * 2f }
                            }
                        }
                    }
                } else {
                    dir = if (dx > 0) 1f else -1f
                    x += dir * speed * dt
                    phase += dt * 10f
                }
            }
            "sleep" -> { zzz += dt; timer -= dt; if (timer <= 0f && !isNight()) { state = "idle"; timer = 1.2f; zzz = 0f } }
            else -> {
                timer -= dt
                if (timer <= 0f) {
                    if (isNight() && state != "walk") { targetX = randomApp(); state = "walk" }
                    else { targetX = randomApp(); state = "walk" }
                }
            }
        }
    }

    // ---------- The sprite ----------

    private fun oval(l: Int, t: Int, r: Int, b: Int, color: Int, outline: Boolean = true) {
        if (outline) { fill.color = OUT; sc.drawOval(RectF(l - 1f, t - 1f, r + 1f, b + 1f), fill) }
        fill.color = color
        sc.drawOval(RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), fill)
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int, color: Int) {
        fill.color = color
        sc.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fill)
    }

    /** Standing, walking or sniffing (head dropped to the app). */
    private fun drawStanding(bob: Int, swing: Int, wag: Int, tongue: Boolean, eyeOpen: Boolean, drop: Int, twitch: Int) {
        sc.drawColor(0, PorterDuff.Mode.CLEAR)
        val hx = if (drop > 0) 2 else 0
        val hy = drop
        // Far legs.
        rect(12 + swing, 18, 15 + swing, 24, FUR_D); rect(12 + swing, 22, 15 + swing, 24, CREAM)
        rect(17 - swing, 18, 20 - swing, 24, FUR_D); rect(17 - swing, 22, 20 - swing, 24, CREAM)
        // Curly tail.
        oval(1, 6 + wag - bob, 7, 13 - bob, FUR)
        rect(2, 6 + wag - bob, 5, 8 + wag - bob, CREAM)
        // Body and chest.
        oval(6, 9 - bob, 24, 19 - bob, FUR)
        oval(19, 12 - bob, 25, 19 - bob, CREAM, outline = false)
        rect(9, 11 - bob, 11, 12 - bob, CREAM); rect(13, 10 - bob, 14, 11 - bob, FUR_D)   // curls
        // Near legs.
        rect(8 - swing, 18, 11 - swing, 24, FUR); rect(8 - swing, 22, 11 - swing, 24, CREAM)
        rect(21 + swing, 18, 24 + swing, 24, FUR); rect(21 + swing, 22, 24 + swing, 24, CREAM)
        // Collar.
        rect(19, 13 - bob, 23, 15 - bob, COLLAR); rect(21, 14 - bob, 22, 15 - bob, GOLD)
        // Head.
        oval(20 + hx, 3 + hy, 31 + hx, 14 + hy, FUR)
        rect(23 + hx, 3 + hy, 25 + hx, 4 + hy, CREAM); rect(26 + hx, 4 + hy, 27 + hx, 5 + hy, CREAM); rect(21 + hx, 4 + hy, 22 + hx, 5 + hy, FUR_D)
        oval(19 + hx, 6 + hy, 24 + hx, 15 + hy, FUR_D)                                   // floppy ear
        oval(27 + hx, 8 + hy, 33 + hx, 13 + hy, CREAM)                                   // muzzle
        rect(32 + hx + twitch, 8 + hy, 34 + hx + twitch, 10 + hy, DARK)                  // nose
        if (eyeOpen) { rect(27 + hx, 6 + hy, 29 + hx, 8 + hy, DARK); rect(27 + hx, 6 + hy, 28 + hx, 7 + hy, 0xFFFFFFFF.toInt()) }
        else rect(27 + hx, 7 + hy, 29 + hx, 8 + hy, DARK)
        rect(29 + hx, 12 + hy, 31 + hx, 13 + hy, OUT)
        if (tongue) rect(30 + hx, 13 + hy, 31 + hx, 15 + hy, TONGUE)
    }

    /** Curled up asleep, chin on his paws. */
    private fun drawSleeping(breath: Int) {
        sc.drawColor(0, PorterDuff.Mode.CLEAR)
        oval(1, 17, 8, 24, FUR)                                                          // tail wrapped round
        oval(4, 15 - breath, 27, 24, FUR)
        rect(8, 17 - breath, 11, 18 - breath, CREAM); rect(14, 16 - breath, 15, 17 - breath, FUR_D)
        oval(21, 14, 32, 24, FUR)
        rect(24, 14, 26, 15, CREAM); rect(27, 15, 28, 16, CREAM)
        oval(20, 16, 25, 24, FUR_D)                                                      // ear
        oval(28, 19, 34, 23, CREAM)                                                      // muzzle
        rect(33, 19, 35, 21, DARK)                                                       // nose
        rect(27, 19, 29, 20, DARK)                                                       // shut eye
        rect(24, 22, 30, 24, CREAM)                                                      // paws
        rect(19, 21, 23, 22, COLLAR)
    }

    override fun onDraw(canvas: Canvas) {
        if (x < 0f) return
        val groundY = height - 2f * d
        val moving = state == "walk"
        val sleeping = state == "sleep"
        val sniffing = state == "sniff"

        val eyeOpen = !(blink < 0f && blink > -0.12f)
        if (sleeping) drawSleeping(if (sin(t * 1.6f) > 0f) 1 else 0)
        else drawStanding(
            bob = if (moving && sin(phase * 2f) > 0f) 1 else 0,
            swing = if (moving) (sin(phase) * 2f).roundToInt() else 0,
            wag = (sin(t * (if (moving) 14f else 9f)) * 1.6f).roundToInt(),
            tongue = moving || (!sniffing && sin(t * 3f) > 0.5f),
            eyeOpen = eyeOpen,
            drop = if (sniffing) 8 else 0,
            twitch = if (sniffing && sin(t * 22f) > 0f) 1 else 0,
        )

        canvas.save()
        canvas.translate(x, groundY)
        canvas.scale(dir * k, k)
        blit.isFilterBitmap = false
        canvas.drawBitmap(sprite, -SW / 2f, -24f, blit)
        canvas.restore()

        if (sleeping) {
            text.textAlign = Paint.Align.LEFT
            for (i in 0 until 3) {
                val ph = (zzz * 0.5f + i * 0.5f) % 1.5f
                text.color = (((1f - ph / 1.5f) * 220).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                text.textSize = (7f + i * 2.5f) * d
                canvas.drawText("z", x + dir * 12f * d + ph * 6f * d, groundY - 16f * d - ph * 14f * d, text)
            }
        }
    }
}
