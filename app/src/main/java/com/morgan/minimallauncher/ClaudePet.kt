package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

/**
 * A little pixel-art Claude who lives on the home screen. He strolls around,
 * looks about, naps at night, bops along to music and has the odd daydream.
 * Press OK on him to ask a question.
 */
class ClaudePet(context: Context) : View(context) {

    var onChat: (() -> Unit)? = null

    // ---------- Pixel art ----------
    // 16 wide. '.' clear, 'o' outline, 'B' body, 'b' shadow, 'H' highlight,
    // 'W' eye white, 'K' pupil.
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
    private val BODY_ROWS = rows.size

    private var body: Bitmap? = null
    private var px = 3f

    private val p = Paint()
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- Life ----------
    private var t = 0f
    private var gx = 0.3f                 // 0..1 across the band
    private var dir = 1f                  // facing / walking direction
    private var state = "walk"            // walk | idle | sit | sleep | dance
    private var idle = 1.5f
    private var blink = 2f
    private var walkPhase = 0f
    private var focused = false
    private var waveT = 0f
    private var zzz = 0f
    private var thought = 0f
    private var thoughtKind = 0
    private var musicPlaying = false
    private var musicCheck = 0f

    init { isFocusable = true; isClickable = true; setOnClickListener { onChat?.invoke() } }

    private val frame = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(now: Long) {
            if (last != 0L) { val dt = ((now - last) / 1e9f).coerceAtMost(0.05f); step(dt); invalidate() }
            last = now
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var running = false
    fun resume() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(frame) } }
    fun pause() { running = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); pause() }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, r: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, r)
        focused = gainFocus
        if (gainFocus) { waveT = 1.2f; if (state == "sleep") { state = "idle"; idle = 3f } }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        px = (h * 0.34f / COLS).coerceAtLeast(2f)
        body = buildSprite()
        textP.typeface = GlassUi.font(context, R.font.inter_semibold)
    }

    private fun buildSprite(): Bitmap {
        val bmp = Bitmap.createBitmap(COLS, COLS, Bitmap.Config.ARGB_8888)
        for (y in rows.indices) for (x in 0 until COLS) {
            val ch = rows[y].getOrElse(x) { '.' }
            bmp.setPixel(x, y, if (ch == '.') Color.TRANSPARENT else palette[ch] ?: Color.TRANSPARENT)
        }
        return bmp
    }

    // ---------- Update ----------

    private fun step(dt: Float) {
        t += dt
        blink -= dt; if (blink < -0.1f) blink = 2f + Math.random().toFloat() * 4f
        if (waveT > 0) waveT -= dt
        musicCheck -= dt
        if (musicCheck <= 0f) { musicCheck = 1.6f; musicPlaying = try { NowPlaying.current(context)?.playing == true } catch (e: Exception) { false } }

        val night = isNight()
        val want = when {
            focused -> "idle"
            musicPlaying -> "dance"
            night -> "sleep"
            else -> state.takeIf { it == "walk" || it == "idle" || it == "sit" } ?: "walk"
        }
        if (want == "dance" && state != "dance") state = "dance"
        if (want == "sleep" && state != "sleep") { state = "sleep"; dir = -1f }
        if (want == "idle" && (state == "dance" || state == "sleep")) { state = "idle"; idle = 2f }

        when (state) {
            "walk" -> {
                gx += dir * 0.06f * dt
                walkPhase += dt * 9f
                if (gx > 0.92f) { gx = 0.92f; dir = -1f; pause2() }
                if (gx < 0.08f) { gx = 0.08f; dir = 1f; pause2() }
                if (Math.random() < dt * 0.25) { state = "idle"; idle = 1.5f + Math.random().toFloat() * 2.5f }
            }
            "idle" -> {
                idle -= dt
                if (idle <= 0f) {
                    val r = Math.random()
                    when { r < 0.15 -> { state = "sit"; idle = 3f + Math.random().toFloat() * 3f }
                           else -> { state = "walk"; dir = if (Math.random() < 0.5) -1f else 1f } }
                }
            }
            "sit" -> { idle -= dt; if (idle <= 0f) state = "walk" }
            "sleep" -> { gx += (0.08f - gx) * dt * 0.6f; zzz += dt }
            "dance" -> { walkPhase += dt * 6f; gx += sin(t * 2f) * 0.01f * dt }
        }
        if (state != "sleep") zzz = 0f

        // Daydreams now and then (not while asleep).
        thought -= dt
        if (thought < -3f && state != "sleep" && Math.random() < dt * 0.4) {
            thought = 3f; thoughtKind = (Math.random() * 4).toInt()
        }
    }

    private fun pause2() { state = "idle"; idle = 1f + Math.random().toFloat() * 2f }

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    // ---------- Draw ----------

    override fun onDraw(canvas: Canvas) {
        val bmp = body ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val spriteW = COLS * px
        val bodyH = BODY_ROWS * px
        val footH = px * 2f
        val ground = h * 0.82f
        val x = (gx * w).coerceIn(spriteW, w - spriteW)

        val sitting = state == "sit"
        val sleeping = state == "sleep"
        val dancing = state == "dance"
        val walking = state == "walk"
        val breathe = sin(t * 2f) * px * 0.3f
        val walkBob = if (walking) abs(sin(walkPhase)) * px * 0.8f else 0f
        val danceBob = if (dancing) abs(sin(t * 7f)) * px * 1.4f else 0f
        val sink = if (sitting || sleeping) px * 1.6f else 0f
        val top = ground - bodyH - footH - walkBob - danceBob + sink + breathe

        // Shadow.
        p.isAntiAlias = true
        p.color = 0x40000000
        val shW = spriteW * (0.42f - danceBob / px * 0.02f)
        canvas.drawOval(RectF(x - shW, ground - px * 0.8f, x + shW, ground + px * 1.2f), p)
        p.isAntiAlias = false

        // Soft glow when selected.
        if (focused) {
            p.isAntiAlias = true
            p.shader = RadialGradient(x, top + bodyH * 0.5f, spriteW, 0x66D97757, 0x00D97757, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, top + bodyH * 0.5f, spriteW, p); p.shader = null; p.isAntiAlias = false
        }

        // Feet (little shuffle while walking).
        p.color = palette['o']!!
        val step = if (walking) sin(walkPhase) * px * 1.2f else 0f
        drawPixelRect(canvas, x - px * 3.5f + (if (walking) step else -px * 0.5f), ground - footH, px * 2.4f, footH)
        drawPixelRect(canvas, x + px * 1.1f - (if (walking) step else -px * 0.5f), ground - footH, px * 2.4f, footH)

        // Body (flip to face the walking direction).
        canvas.save()
        if (dir < 0) { canvas.scale(-1f, 1f, x, 0f) }
        val dst = RectF(x - spriteW / 2f, top, x + spriteW / 2f, top + bodyH)
        p.isFilterBitmap = false
        canvas.drawBitmap(bmp, null, dst, p)
        // Eyes: blink / sleep overlays.
        val closed = sleeping || blink < 0f && blink > -0.1f
        if (closed) {
            p.color = palette['o']!!
            val eyR = px
            canvas.drawRect(dst.left + 3 * px, top + 6.5f * px, dst.left + 5 * px, top + 7f * px, p)
            canvas.drawRect(dst.left + 9 * px, top + 6.5f * px, dst.left + 11 * px, top + 7f * px, p)
        }
        // Rosy cheeks when happy.
        if (focused || dancing) {
            p.color = 0x55C45A3B
            canvas.drawRect(dst.left + 3 * px, top + 8.5f * px, dst.left + 4.5f * px, top + 9.5f * px, p)
            canvas.drawRect(dst.left + 9.5f * px, top + 8.5f * px, dst.left + 11 * px, top + 9.5f * px, p)
        }
        canvas.restore()

        // Antenna spark (always his little Claude touch).
        drawSpark(canvas, x, top - px * 0.5f, px * 2.0f, 0xFFFFC9A8.toInt(), t * 1.2f)

        // Wave when selected.
        if (waveT > 0f) {
            p.color = palette['b']!!
            val swing = sin((1.2f - waveT) * 20f)
            val hx = x + spriteW * 0.5f * dir
            canvas.drawCircle(hx + px * swing, top + bodyH * 0.2f - px * (2f + swing), px * 1.4f, p)
        }

        // Sleepy Zzz.
        if (sleeping) {
            textP.textAlign = Paint.Align.LEFT
            for (i in 0 until 3) {
                val ph = (zzz * 0.5f + i * 0.5f) % 1.5f
                textP.color = ((1f - ph / 1.5f) * 200).toInt().coerceIn(0, 255) shl 24 or 0xFFFFFF
                textP.textSize = px * (2.2f + i)
                canvas.drawText("z", x + spriteW * 0.4f + ph * px * 3, top - ph * px * 7, textP)
            }
        }

        // Daydream bubble (music, heart, spark, tea) or music notes while playing.
        if (musicPlaying && !sleeping) {
            textP.textAlign = Paint.Align.CENTER
            for (i in 0 until 2) {
                val ph = (t * 1.2f + i * 0.5f) % 1f
                textP.color = ((1f - ph) * 220).toInt() shl 24 or 0xD97757
                textP.textSize = px * 3f
                canvas.drawText(if (i == 0) "♪" else "♫", x + spriteW * (0.5f + i * 0.3f), top - ph * px * 8, textP)
            }
        } else if (thought > 0f && !sleeping) {
            val a = (thought.coerceIn(0f, 1f) * 255).toInt()
            textP.textAlign = Paint.Align.CENTER
            textP.color = (a shl 24) or 0xFFFFFF
            p.color = (a / 2 shl 24)
            p.isAntiAlias = true
            canvas.drawCircle(x + spriteW * 0.5f, top - px * 3f, px * 2.4f, p)
            p.isAntiAlias = false
            textP.textSize = px * 2.4f
            val g = when (thoughtKind) { 0 -> "♥"; 1 -> "♪"; 2 -> "✨"; else -> "☕" }
            canvas.drawText(g, x + spriteW * 0.5f, top - px * 2.1f, textP)
        }

        // "Ask me" bubble when selected.
        if (focused) {
            val label = "Chat with Claude   OK"
            textP.textAlign = Paint.Align.CENTER
            textP.textSize = px * 2.6f
            val tw = textP.measureText(label)
            val bx = x.coerceIn(tw / 2 + px * 3, w - tw / 2 - px * 3)
            val by = top - px * 7f
            p.color = 0xF21C1C1E.toInt(); p.isAntiAlias = true
            val r = RectF(bx - tw / 2 - px * 3, by - px * 3.2f, bx + tw / 2 + px * 3, by + px * 1.6f)
            canvas.drawRoundRect(r, px * 2f, px * 2f, p)
            val tail = Path(); tail.moveTo(bx - px * 1.5f, r.bottom - px * 0.2f); tail.lineTo(bx + px * 1.5f, r.bottom - px * 0.2f); tail.lineTo(bx, r.bottom + px * 1.6f); tail.close()
            canvas.drawPath(tail, p); p.isAntiAlias = false
            textP.color = 0xFFFFFFFF.toInt()
            canvas.drawText(label, bx, by, textP)
        }
    }

    private fun drawPixelRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRect(x, y, x + w, y + h, p)
    }

    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, r: Float, colour: Int, spin: Float) {
        p.isAntiAlias = true; p.color = colour
        val rays = 6
        for (i in 0 until rays) {
            val a = spin + i * (2.0 * Math.PI / rays).toFloat()
            val path = Path()
            path.moveTo(cx + Math.cos(a.toDouble()).toFloat() * r, cy + Math.sin(a.toDouble()).toFloat() * r)
            val a1 = a + (Math.PI / rays).toFloat()
            path.lineTo(cx + Math.cos(a1.toDouble()).toFloat() * r * 0.35f, cy + Math.sin(a1.toDouble()).toFloat() * r * 0.35f)
            val a2 = a - (Math.PI / rays).toFloat()
            path.lineTo(cx + Math.cos(a2.toDouble()).toFloat() * r * 0.35f, cy + Math.sin(a2.toDouble()).toFloat() * r * 0.35f)
            path.close(); canvas.drawPath(path, p)
        }
        canvas.drawCircle(cx, cy, r * 0.32f, p); p.isAntiAlias = false
    }
}
