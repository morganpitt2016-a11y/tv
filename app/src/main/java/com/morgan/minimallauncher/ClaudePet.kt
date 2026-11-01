package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pixel-art Claude who lives in his bedroom (the picture behind this view).
 * Positions are fractions of the room picture: he strolls round the rug, sits,
 * naps on the bed at night, dances when music plays and daydreams. Select him
 * and press OK to ask a question.
 */
class ClaudePet(context: Context) : View(context) {

    var onChat: (() -> Unit)? = null

    // 16 wide. '.' clear, 'o' outline, 'B' body, 'b' shade, 'H' highlight, 'W' eye white, 'K' pupil.
    private val rows = arrayOf(
        "......oooo......",
        "....ooBBBBoo....",
        "...oBBBBBBBBo...",
        "..oBBBBBBBBBBo..",
        "..oBBHHBBHHBBo..",
        "..oBBBBBBBBBBo..",
        "..oBWWBBBBWWBo..",
        "..oBWKBBBBWKBo..",
        "..oBBBBBBBBBBo..",
        "..oBBooooooBBo..",
        "..oBBBBBBBBBBo..",
        "...oBBBBBBBBo...",
        "....obBBBBbo....",
        ".....oooooo.....",
    )
    private val palette = mapOf(
        'o' to 0xFF3A241A.toInt(), 'B' to 0xFFD97757.toInt(), 'b' to 0xFFB65538.toInt(),
        'H' to 0xFFF0A07E.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'K' to 0xFF2A1C14.toInt())
    private val COLS = 16
    private val ROWS = rows.size
    private val src = Rect(0, 0, COLS, ROWS)

    private val sprite: Bitmap = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888).also { bmp ->
        for (y in rows.indices) for (x in 0 until COLS) {
            val ch = rows[y].getOrElse(x) { '.' }
            bmp.setPixel(x, y, if (ch == '.') Color.TRANSPARENT else palette[ch] ?: Color.TRANSPARENT)
        }
    }

    private val p = Paint()
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    // Where things are in the room picture (fractions of its width / height).
    private val spots = listOf(0.45f to 0.70f, 0.38f to 0.72f, 0.52f to 0.69f, 0.43f to 0.76f, 0.47f to 0.65f)
    private val bed = 0.335f to 0.60f
    private val danceFloor = 0.45f to 0.70f

    // ---------- Life ----------
    private var t = 0f
    private var nx = 0.45f
    private var ny = 0.70f
    private var tx = nx
    private var ty = ny
    private var dir = 1f
    private var state = "idle"            // idle | walk | sit | sleep | dance | talk
    private var idleT = 1f
    private var blink = 2f
    private var walkPhase = 0f
    private var focused = false
    private var waveT = 0f
    private var zzz = 0f
    private var thought = 0f
    private var thoughtKind = 0
    private var musicPlaying = false
    private var musicCheck = 0f

    init {
        isFocusable = true
        isClickable = true
        textP.typeface = GlassUi.font(context, R.font.inter_semibold)
        setOnClickListener { greet() }
    }

    private var talkT = 0f

    /** OK pressed: he turns to you and says hi, then the chat opens. */
    private fun greet() {
        if (talkT > 0f) return
        talkT = 1.0f
        waveT = 1.0f
    }

    private var running = false
    private val frame = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(now: Long) {
            if (last != 0L) { step(((now - last) / 1e9f).coerceAtMost(0.05f)); invalidate() }
            last = now
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun resume() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(frame) } }
    fun pause() { running = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); pause() }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focused = gainFocus
    }

    // ---------- Update ----------

    private fun step(dt: Float) {
        t += dt
        blink -= dt
        if (blink < -0.12f) blink = 2f + Random.nextFloat() * 4f
        if (waveT > 0f) waveT -= dt
        thought -= dt
        musicCheck -= dt
        if (musicCheck <= 0f) {
            musicCheck = 1.6f
            musicPlaying = try { NowPlaying.current(context)?.playing == true } catch (e: Exception) { false }
        }

        if (talkT > 0f) {
            talkT -= dt
            state = "talk"
            if (talkT <= 0f) { state = "idle"; idleT = 1.5f; onChat?.invoke() }
            return
        }

        val goal: Pair<Float, Float>? = when {
            musicPlaying -> danceFloor
            isNight() -> bed
            else -> null
        }
        if (goal != null) {
            tx = goal.first; ty = goal.second
            state = if (!moveToward(dt)) "walk" else if (goal == bed) "sleep" else "dance"
        } else {
            if (state == "sleep" || state == "dance") { state = "idle"; idleT = 1.2f }
            when (state) {
                "walk" -> if (moveToward(dt)) { state = "idle"; idleT = 1.5f + Random.nextFloat() * 3f }
                "sit" -> { idleT -= dt; if (idleT <= 0f) { state = "idle"; idleT = 0.6f } }
                else -> {
                    idleT -= dt
                    if (idleT <= 0f) {
                        if (Random.nextFloat() < 0.2f) { state = "sit"; idleT = 3f + Random.nextFloat() * 3f }
                        else { val s = spots.random(); tx = s.first; ty = s.second; state = "walk" }
                    }
                }
            }
        }
        if (state == "sleep") zzz += dt else zzz = 0f
        if (thought < -3f && state != "sleep" && Random.nextFloat() < dt * 0.4f) {
            thought = 3f; thoughtKind = Random.nextInt(4)
        }
    }

    /** Walk toward (tx, ty); true once there. */
    private fun moveToward(dt: Float): Boolean {
        val dx = tx - nx; val dy = ty - ny
        val dist = hypot(dx, dy * 0.6f)
        if (dist < 0.004f) { nx = tx; ny = ty; return true }
        val f = min(1f, 0.075f * dt / dist)
        nx += dx * f; ny += dy * f
        if (abs(dx) > 0.002f) dir = if (dx > 0) 1f else -1f
        walkPhase += dt * 10f
        return false
    }

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    // ---------- Draw ----------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val depth = 0.85f + (ny - 0.55f) * 0.6f
        val px = w * 0.05f / COLS * depth
        val sw = COLS * px
        val bh = ROWS * px
        val fx = nx * w; val fy = ny * h

        val sleeping = state == "sleep"
        val sitting = state == "sit"
        val dancing = state == "dance"
        val walking = state == "walk"
        val hop = when {
            walking -> abs(sin(walkPhase)) * px * 0.9f
            dancing -> abs(sin(t * 7f)) * px * 1.5f
            else -> 0f
        }
        val breathe = sin(t * 2f) * px * 0.25f
        val bodyTopY = fy - bh - px * 1.2f - hop + breathe

        // Shadow.
        p.isAntiAlias = true
        p.color = 0x55000000
        val shw = sw * (if (sleeping) 0.55f else 0.42f)
        canvas.drawOval(RectF(fx - shw, fy - px * 0.9f, fx + shw, fy + px * 1.1f), p)
        p.isAntiAlias = false

        canvas.save()
        canvas.translate(fx + if (dancing) sin(t * 7f) * px * 0.8f else 0f, fy)
        if (sleeping) canvas.rotate(-90f, 0f, -bh * 0.5f)      // curled up on the bed
        if (sitting) canvas.scale(1f, 0.88f, 0f, 0f)
        canvas.scale(dir, 1f)
        val top = -bh - px * 1.2f - hop + breathe

        // Feet.
        p.color = palette['o']!!
        val shuffle = if (walking) sin(walkPhase) * px * 1.1f else 0f
        canvas.drawRect(-sw * 0.22f + shuffle, -px * 1.2f, -sw * 0.22f + shuffle + px * 2.4f, 0f, p)
        canvas.drawRect(sw * 0.08f - shuffle, -px * 1.2f, sw * 0.08f - shuffle + px * 2.4f, 0f, p)

        // Body.
        val dst = RectF(-sw / 2f, top, sw / 2f, top + bh)
        p.isFilterBitmap = false
        canvas.drawBitmap(sprite, src, dst, p)
        // Eyes shut when asleep or blinking.
        if (sleeping || (blink < 0f && blink > -0.12f)) {
            p.color = palette['o']!!
            canvas.drawRect(dst.left + 3 * px, top + 6f * px, dst.left + 5 * px, top + 8f * px, p)
            canvas.drawRect(dst.left + 9 * px, top + 6f * px, dst.left + 11 * px, top + 8f * px, p)
            p.color = palette['B']!!
            canvas.drawRect(dst.left + 3 * px, top + 6f * px, dst.left + 5 * px, top + 7.2f * px, p)
            canvas.drawRect(dst.left + 9 * px, top + 6f * px, dst.left + 11 * px, top + 7.2f * px, p)
        }
        // Rosy cheeks when happy.
        if (talkT > 0f || dancing) {
            p.color = 0x66C45A3B
            canvas.drawRect(dst.left + 3 * px, top + 8.5f * px, dst.left + 4.5f * px, top + 9.5f * px, p)
            canvas.drawRect(dst.left + 9.5f * px, top + 8.5f * px, dst.left + 11 * px, top + 9.5f * px, p)
        }
        // The little Claude spark on his head.
        drawSpark(canvas, 0f, top - px * 0.3f, px * 2.1f, 0xFFFFC9A8.toInt(), t * 1.2f)
        canvas.restore()

        // Wave when selected.
        if (waveT > 0f && !sleeping) {
            val swing = sin((1.2f - waveT) * 20f)
            p.color = palette['b']!!
            p.isAntiAlias = true
            canvas.drawCircle(fx + dir * sw * 0.58f + px * swing, bodyTopY + bh * 0.35f - px * (1.5f + swing), px * 1.4f, p)
            p.isAntiAlias = false
        }

        // Sleepy Zzz.
        if (sleeping) {
            textP.textAlign = Paint.Align.LEFT
            for (i in 0 until 3) {
                val ph = (zzz * 0.5f + i * 0.5f) % 1.5f
                textP.color = (((1f - ph / 1.5f) * 220).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                textP.textSize = (9f + i * 3f) * d
                canvas.drawText("z", fx + sw * 0.4f + ph * 6f * d, fy - bh * 0.9f - ph * 16f * d, textP)
            }
        }

        // Music notes while something plays, otherwise the odd daydream.
        if (musicPlaying && !sleeping) {
            textP.textAlign = Paint.Align.CENTER
            textP.textSize = 15f * d
            for (i in 0 until 2) {
                val ph = (t * 0.9f + i * 0.5f) % 1f
                textP.color = (((1f - ph) * 230).toInt() shl 24) or 0xD97757
                canvas.drawText(if (i == 0) "♪" else "♫", fx + sw * (0.45f + i * 0.35f), bodyTopY - ph * 28f * d, textP)
            }
        } else if (thought > 0f && !sleeping && talkT <= 0f) {
            val a = (thought.coerceIn(0f, 1f) * 255).toInt()
            val bx = fx + sw * 0.6f; val by = bodyTopY - px * 3f
            p.isAntiAlias = true
            p.color = ((a * 0.55f).toInt() shl 24) or 0x000000
            canvas.drawCircle(bx, by, 11f * d, p)
            p.isAntiAlias = false
            textP.textAlign = Paint.Align.CENTER
            textP.color = (a shl 24) or 0xFFFFFF
            textP.textSize = 13f * d
            val g = when (thoughtKind) { 0 -> "♥"; 1 -> "♪"; 2 -> "✨"; else -> "☕" }
            canvas.drawText(g, bx, by + 4.5f * d, textP)
        }

        // A quick "Hi!" as he turns to you, just before the chat opens.
        if (talkT > 0f) bubble(canvas, "Hi!", fx, bodyTopY - 10f * d, w)
        // Otherwise only a quiet hint at the bottom - he carries on with his day.
        else if (focused) hint(canvas, "Press OK to talk to Claude", w, h)
    }

    private fun bubble(canvas: Canvas, label: String, fx: Float, bottomY: Float, w: Float) {
        textP.textAlign = Paint.Align.CENTER
        textP.textSize = 12f * d
        textP.color = 0xFFFFFFFF.toInt()
        val tw = textP.measureText(label)
        val padX = 12f * d
        val cxm = fx.coerceIn(tw / 2 + padX + 4f * d, w - tw / 2 - padX - 4f * d)
        val bottom = bottomY.coerceAtLeast(34f * d)
        val r = RectF(cxm - tw / 2 - padX, bottom - 26f * d, cxm + tw / 2 + padX, bottom)
        p.isAntiAlias = true
        p.color = 0xF21C1C1E.toInt()
        canvas.drawRoundRect(r, 13f * d, 13f * d, p)
        val tail = Path()
        tail.moveTo(fx - 5f * d, r.bottom - 0.5f * d); tail.lineTo(fx + 5f * d, r.bottom - 0.5f * d); tail.lineTo(fx, r.bottom + 7f * d); tail.close()
        canvas.drawPath(tail, p)
        p.isAntiAlias = false
        canvas.drawText(label, cxm, r.centerY() + 4.2f * d, textP)
    }

    private fun hint(canvas: Canvas, label: String, w: Float, h: Float) {
        textP.textAlign = Paint.Align.CENTER
        textP.textSize = 11f * d
        val tw = textP.measureText(label)
        val r = RectF(w / 2 - tw / 2 - 12f * d, h - 38f * d, w / 2 + tw / 2 + 12f * d, h - 14f * d)
        p.isAntiAlias = true
        p.color = 0x991C1C1E.toInt()
        canvas.drawRoundRect(r, 12f * d, 12f * d, p)
        p.isAntiAlias = false
        textP.color = 0xCCFFFFFF.toInt()
        canvas.drawText(label, w / 2, r.centerY() + 4f * d, textP)
    }

    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, r: Float, colour: Int, spin: Float) {
        p.isAntiAlias = true
        p.color = colour
        val rays = 6
        val step = (2.0 * PI / rays).toFloat()
        for (i in 0 until rays) {
            val a = spin + i * step
            val path = Path()
            path.moveTo(cx + cos(a) * r, cy + sin(a) * r)
            val a1 = a + step / 2f
            path.lineTo(cx + cos(a1) * r * 0.35f, cy + sin(a1) * r * 0.35f)
            val a2 = a - step / 2f
            path.lineTo(cx + cos(a2) * r * 0.35f, cy + sin(a2) * r * 0.35f)
            path.close()
            canvas.drawPath(path, p)
        }
        canvas.drawCircle(cx, cy, r * 0.32f, p)
        p.isAntiAlias = false
    }
}
