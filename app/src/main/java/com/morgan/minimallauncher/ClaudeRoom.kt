package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.sign
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cosy little isometric bedroom where a Claude character hangs out. It's
 * drawn by hand (no 3D engine, so it's light on the TV) with a faked 3D
 * angle: a bed, rug, lamp, plant, window and a framed spark on the wall.
 * The window follows the real time of day, and the lamp glows at night.
 * Claude breathes, blinks and gives a little wave when you select him.
 */
class ClaudeRoomView(context: Context) : View(context) {

    // Claude brand coat.
    private val CLAUDE = 0xFFD97757.toInt()
    private val CLAUDE_DARK = 0xFFC45A3B.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f                 // seconds since start
    private var selected = false
    private var waveT = 0f             // wave timer when selected
    private var blink = 0f
    private var nextBlink = 2.5f
    private val fm = FloatArray(2)

    // Claude's position on the floor grid, and where he's heading.
    private var cx = 2.9f; private var cy = 2.9f
    private var tx = 2.9f; private var ty = 2.9f
    private var facing = 1f               // +1 faces right-front, -1 left-front
    private var walkPhase = 0f
    private var idleTimer = 1.5f
    private var state = "idle"            // idle | walk | sleep | dance
    private var zzz = 0f
    private val notes = ArrayList<Note>()
    private var musicPlaying = false
    private var musicCheck = 0f
    private var steam = 0f

    private class Note(var gx: Float, var gy: Float, var life: Float, val kind: Int)

    private val waypoints = listOf(
        2.1f to 2.2f, 3.9f to 2.3f, 3.0f to 3.4f, 2.3f to 4.0f, 4.3f to 2.0f, 1.9f to 3.0f)

    /** 0 unit = this many px; the room is laid out in a small grid then scaled. */
    private var unit = 1f
    private var ox = 0f
    private var oy = 0f

    var onSelect: (() -> Unit)? = null

    private val callback = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(now: Long) {
            if (last != 0L) {
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                t += dt
                if (selected && waveT > 0f) waveT -= dt
                blink -= dt
                if (blink < -0.12f) { blink = nextBlink; nextBlink = 2f + Math.random().toFloat() * 4f }
                update(dt)
                invalidate()
            }
            last = now
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Choreographer.getInstance().postFrameCallback(callback) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Choreographer.getInstance().removeFrameCallback(callback) }

    fun wake(on: Boolean) {
        if (on && !selected) waveT = 1.3f
        selected = on
    }

    /** OK / tap selects Claude. */
    fun poke() { waveT = 1.3f; onSelect?.invoke() }

    private fun update(dt: Float) {
        steam = (steam + dt) % 100f
        // Check what's playing now and then (cheap enough every ~1.5s).
        musicCheck -= dt
        if (musicCheck <= 0f) { musicCheck = 1.5f; musicPlaying = try { NowPlaying.current(context)?.playing == true } catch (e: Exception) { false } }

        val deepNight = timeTint() > 0.9f
        // Decide mood.
        val want = when {
            musicPlaying -> "dance"
            deepNight && !selected -> "sleep"
            else -> if (state == "sleep" || state == "dance") "idle" else state
        }
        if (want == "sleep" && state != "sleep") { state = "sleep"; tx = 4.1f; ty = 4.3f }
        if (want == "dance" && state != "dance") { state = "dance" }
        if (want == "idle" && (state == "sleep" || state == "dance")) { state = "idle"; idleTimer = 0.4f }

        when (state) {
            "dance" -> { zzz = 0f; moveToward(3.0f, 3.2f, dt, 1.0f) }
            "sleep" -> { moveToward(tx, ty, dt, 1.0f); if (reached()) zzz += dt }
            else -> {
                zzz = 0f
                if (!reached()) { state = "walk"; moveToward(tx, ty, dt, 1.3f) }
                else {
                    state = "idle"
                    idleTimer -= dt
                    if (idleTimer <= 0f) {
                        val wp = waypoints[(Math.random() * waypoints.size).toInt()]
                        tx = wp.first; ty = wp.second
                        idleTimer = 2f + Math.random().toFloat() * 3f
                    }
                }
            }
        }

        // Music notes drift up from Claude while something plays.
        if (musicPlaying) {
            if (Math.random() < dt * 3.0) notes += Note(cx + (Math.random().toFloat() - 0.5f), cy, 1.6f, (Math.random() * 3).toInt())
        }
        val it = notes.iterator()
        while (it.hasNext()) { val n = it.next(); n.life -= dt; n.gy -= dt * 0.1f; if (n.life <= 0f) it.remove() }
    }

    private fun moveToward(gx: Float, gy: Float, dt: Float, speed: Float) {
        val dx = gx - cx; val dy = gy - cy
        val d = hypot(dx, dy)
        if (d < 0.02f) return
        val step = (speed * dt).coerceAtMost(d)
        cx += dx / d * step; cy += dy / d * step
        walkPhase += step * 6f
        if (abs(dx) > 0.01f) facing = sign(dx - dy)
    }

    private fun reached() = hypot(tx - cx, ty - cy) < 0.06f

    // ---------- Isometric helpers ----------
    // A point (gx, gy, gz) in the little room grid -> screen. x runs right-back,
    // y runs left-back, z is up. Classic 2:1 isometric.

    private fun iso(gx: Float, gy: Float, gz: Float): FloatArray {
        fm[0] = ox + (gx - gy) * unit
        fm[1] = oy + (gx + gy) * unit * 0.5f - gz * unit
        return fm
    }

    private fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, colour: Int, path: Path = Path()) {
        path.reset()
        path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]); path.lineTo(c[0], c[1]); path.lineTo(d[0], d[1]); path.close()
        p.style = Paint.Style.FILL; p.color = colour
        // store corners (iso returns a shared array) - caller passes fresh arrays
        // nothing else needed
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        unit = w * 0.072f
        ox = w / 2f
        oy = h * 0.30f
    }

    // Convenience: fresh point arrays (iso reuses one).
    private fun pt(gx: Float, gy: Float, gz: Float) = floatArrayOf(ox + (gx - gy) * unit, oy + (gx + gy) * unit * 0.5f - gz * unit)

    private fun face(canvas: Canvas, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, colour: Int) {
        val path = Path().apply { moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close() }
        p.style = Paint.Style.FILL; p.color = colour
        canvas.drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val night = timeTint()

        // Backdrop wash.
        p.shader = LinearGradient(0f, 0f, 0f, h, blend(0xFF2A2440.toInt(), 0xFF141222.toInt(), night), blend(0xFF15131F.toInt(), 0xFF0B0A12.toInt(), night), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL; canvas.drawRect(0f, 0f, w, h, p); p.shader = null

        val size = 6f  // room is 6x6 units
        // Floor.
        face(canvas, pt(0f, 0f, 0f), pt(size, 0f, 0f), pt(size, size, 0f), pt(0f, size, 0f), blend(0xFF6E5A4A.toInt(), 0xFF4A3D33.toInt(), night))
        // Left wall (y = 0 plane).
        face(canvas, pt(0f, 0f, 0f), pt(size, 0f, 0f), pt(size, 0f, 3.4f), pt(0f, 0f, 3.4f), blend(0xFFBfae8e.toInt(), 0xFF6E6552.toInt(), night))
        // Right wall (x = 0 plane).
        face(canvas, pt(0f, 0f, 0f), pt(0f, size, 0f), pt(0f, size, 3.4f), pt(0f, 0f, 3.4f), blend(0xFFA99A7D.toInt(), 0xFF5E5647.toInt(), night))

        // Rug.
        face(canvas, pt(1.2f, 1.2f, 0.02f), pt(4.2f, 1.2f, 0.02f), pt(4.2f, 4.2f, 0.02f), pt(1.2f, 4.2f, 0.02f), blend(0xFFCf6b4e.toInt(), 0xFF7E4432.toInt(), night))
        face(canvas, pt(1.6f, 1.6f, 0.03f), pt(3.8f, 1.6f, 0.03f), pt(3.8f, 3.8f, 0.03f), pt(1.6f, 3.8f, 0.03f), blend(0xFFE0845F.toInt(), 0xFF8E4E39.toInt(), night))

        drawWindow(canvas, night)
        drawWallSpark(canvas, night)
        drawBed(canvas, night)
        drawPlant(canvas, night)
        drawLamp(canvas, night)
        drawClaude(canvas, night)
        drawNotes(canvas)

        // Night overlay dims everything a touch for mood.
        if (night > 0.05f) { p.color = (((night * 90).toInt()) shl 24); canvas.drawRect(0f, 0f, w, h, p) }

        // Prompt.
        p.shader = null
        p.color = if (selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = unit * 0.42f
        p.typeface = GlassUi.font(context, R.font.inter_semibold)
        canvas.drawText(if (selected) "Press OK to chat with Claude" else "Say hi to Claude", w / 2f, h - unit * 0.9f, p)
    }

    // ---------- Furniture ----------

    private fun drawBed(canvas: Canvas, night: Float) {
        // Base block near the right wall.
        block(canvas, 2.4f, 4.0f, 5.6f, 5.6f, 0.9f, blend(0xFF8A6A54.toInt(), 0xFF4F3C30.toInt(), night), blend(0xFF6E5343.toInt(), 0xFF3C2D24.toInt(), night))
        // Mattress + duvet on top.
        val top = 0.9f
        face(canvas, pt(2.4f, 4.0f, top), pt(5.6f, 4.0f, top), pt(5.6f, 5.6f, top), pt(2.4f, 5.6f, top), blend(0xFFE9E2D6.toInt(), 0xFF9A9384.toInt(), night))
        face(canvas, pt(2.4f, 4.6f, top + 0.02f), pt(5.6f, 4.6f, top + 0.02f), pt(5.6f, 5.6f, top + 0.02f), pt(2.4f, 5.6f, top + 0.02f), blend(CLAUDE, CLAUDE_DARK, night))
        // Pillow.
        block(canvas, 2.6f, 4.05f, 3.5f, 4.5f, top + 0.25f, blend(0xFFFFFFFF.toInt(), 0xFFBfb9ac.toInt(), night), blend(0xFFEDE7DA.toInt(), 0xFFA7A192.toInt(), night), base = top)
    }

    private fun drawLamp(canvas: Canvas, night: Float) {
        val gx = 5.1f; val gy = 1.0f
        // Side table.
        block(canvas, gx - 0.5f, gy - 0.5f, gx + 0.5f, gy + 0.5f, 1.1f, blend(0xFF7E6552.toInt(), 0xFF4A3B30.toInt(), night), blend(0xFF604C3D.toInt(), 0xFF35291F.toInt(), night))
        val base = pt(gx, gy, 1.1f)
        // Glow at night.
        if (night > 0.15f) {
            p.shader = RadialGradient(base[0], base[1] - unit * 0.9f, unit * 3.2f, (0x66FFE08A.toInt()), 0x00FFE08A, Shader.TileMode.CLAMP)
            canvas.drawCircle(base[0], base[1] - unit * 0.9f, unit * 3.2f, p); p.shader = null
        }
        // Stand + shade.
        p.color = blend(0xFF9A8D78.toInt(), 0xFF6A6052.toInt(), night); p.strokeWidth = unit * 0.12f; p.style = Paint.Style.STROKE
        canvas.drawLine(base[0], base[1], base[0], base[1] - unit * 0.95f, p); p.style = Paint.Style.FILL
        val shade = Path()
        shade.moveTo(base[0] - unit * 0.55f, base[1] - unit * 0.95f)
        shade.lineTo(base[0] + unit * 0.55f, base[1] - unit * 0.95f)
        shade.lineTo(base[0] + unit * 0.4f, base[1] - unit * 1.5f)
        shade.lineTo(base[0] - unit * 0.4f, base[1] - unit * 1.5f); shade.close()
        p.color = blend(0xFFEAD8B0.toInt(), 0xFFFFE9A6.toInt(), night); canvas.drawPath(shade, p)

        // A little mug of tea on the table, with rising steam.
        val mug = pt(gx - 0.45f, gy + 0.3f, 1.1f)
        p.color = blend(0xFFE7E1D4.toInt(), 0xFF9A9486.toInt(), night)
        val mr = unit * 0.2f
        canvas.drawRoundRect(RectF(mug[0] - mr, mug[1] - mr * 1.4f, mug[0] + mr, mug[1] + mr * 0.4f), mr * 0.3f, mr * 0.3f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.06f
        canvas.drawArc(RectF(mug[0] + mr * 0.4f, mug[1] - mr * 1.1f, mug[0] + mr * 1.3f, mug[1] - mr * 0.1f), -70f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = blend(0xFF7A4A33.toInt(), 0xFF5A3726.toInt(), night)
        canvas.drawOval(RectF(mug[0] - mr * 0.75f, mug[1] - mr * 1.5f, mug[0] + mr * 0.75f, mug[1] - mr * 1.1f), p)
        p.color = 0x33FFFFFF; p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.05f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 2) {
            val sp = Path(); val sx0 = mug[0] + (i - 0.5f) * mr * 0.7f
            val wob = sin(steam * 2.2f + i * 1.7f) * unit * 0.1f
            sp.moveTo(sx0, mug[1] - mr * 1.5f)
            sp.quadTo(sx0 + wob, mug[1] - mr * 2.4f, sx0 - wob * 0.5f, mug[1] - mr * 3.3f)
            canvas.drawPath(sp, p)
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    private fun drawPlant(canvas: Canvas, night: Float) {
        val gx = 0.7f; val gy = 5.2f
        block(canvas, gx - 0.35f, gy - 0.35f, gx + 0.35f, gy + 0.35f, 0.6f, blend(0xFFB9704E.toInt(), 0xFF6E4330.toInt(), night), blend(0xFF955A3D.toInt(), 0xFF533227.toInt(), night))
        val base = pt(gx, gy, 0.6f)
        p.color = blend(0xFF5FA463.toInt(), 0xFF3C6B42.toInt(), night)
        for (i in 0 until 6) {
            val ang = (-PI / 2 + (i - 2.5) * 0.4).toFloat() + sin((t + i) * 0.8f) * 0.05f
            val len = unit * (1.0f + (i % 2) * 0.35f)
            val leaf = Path()
            leaf.moveTo(base[0], base[1])
            leaf.quadTo(base[0] + cos(ang) * len * 0.5f - unit * 0.3f, base[1] + sin(ang) * len,
                base[0] + cos(ang) * len, base[1] + sin(ang) * len * 0.7f)
            leaf.quadTo(base[0] + cos(ang) * len * 0.5f + unit * 0.3f, base[1] + sin(ang) * len, base[0], base[1])
            canvas.drawPath(leaf, p)
        }
    }

    private fun drawWindow(canvas: Canvas, night: Float) {
        // On the left wall (y = 0).
        val a = pt(1.4f, 0f, 1.3f); val b = pt(3.4f, 0f, 1.3f); val c = pt(3.4f, 0f, 2.9f); val d = pt(1.4f, 0f, 2.9f)
        // Sky inside.
        val sky = Path().apply { moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close() }
        canvas.save(); canvas.clipPath(sky)
        p.shader = LinearGradient(a[0], c[1], a[0], a[1], skyTop(night), skyBottom(night), Shader.TileMode.CLAMP)
        canvas.drawPath(sky, p); p.shader = null
        // Sun or moon.
        val day = 1f - night
        val sx = a[0] + (b[0] - a[0]) * (0.3f + 0.4f * day)
        val sy = c[1] + (a[1] - c[1]) * (0.3f + 0.45f * (1f - kotlin.math.abs(day - 0.5f) * 2f))
        p.color = if (night > 0.5f) 0xFFE8EAF0.toInt() else 0xFFFFE08A.toInt()
        canvas.drawCircle(sx, sy, unit * 0.45f, p)
        if (night > 0.5f) { p.color = skyTop(night); canvas.drawCircle(sx + unit * 0.18f, sy - unit * 0.1f, unit * 0.4f, p) }  // moon crescent
        // A couple of stars at night.
        if (night > 0.55f) { p.color = 0xCCFFFFFF.toInt(); canvas.drawCircle(a[0] + unit * 0.4f, a[1] + unit * 0.3f, unit * 0.04f, p); canvas.drawCircle(b[0] - unit * 0.6f, a[1] + unit * 0.6f, unit * 0.05f, p) }
        canvas.restore()
        // Frame + cross bars.
        p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.12f; p.color = blend(0xFFEDE6D6.toInt(), 0xFF8A8372.toInt(), night)
        canvas.drawPath(sky, p)
        canvas.drawLine((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (d[0] + c[0]) / 2, (d[1] + c[1]) / 2, p)
        canvas.drawLine((a[0] + d[0]) / 2, (a[1] + d[1]) / 2, (b[0] + c[0]) / 2, (b[1] + c[1]) / 2, p)
        p.style = Paint.Style.FILL
    }

    private fun drawWallSpark(canvas: Canvas, night: Float) {
        // A framed Claude "spark" on the right wall (x = 0).
        val cx = pt(0f, 2.4f, 2.2f)
        p.color = blend(0xFFEDE6D6.toInt(), 0xFF8A8372.toInt(), night)
        val fr = RectF(cx[0] - unit * 0.7f, cx[1] - unit * 0.7f, cx[0] + unit * 0.7f, cx[1] + unit * 0.7f)
        canvas.drawRoundRect(fr, unit * 0.1f, unit * 0.1f, p)
        p.color = blend(0xFF201C2C.toInt(), 0xFF100E18.toInt(), night)
        fr.inset(unit * 0.12f, unit * 0.12f); canvas.drawRoundRect(fr, unit * 0.08f, unit * 0.08f, p)
        drawSpark(canvas, cx[0], cx[1], unit * 0.42f, blend(CLAUDE, 0xFFE89B7E.toInt(), night), 0f)
    }

    /** The Claude sunburst spark. */
    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, r: Float, colour: Int, spin: Float) {
        p.color = colour
        val rays = 8
        for (i in 0 until rays) {
            val a = spin + i * (2f * PI.toFloat() / rays)
            val path = Path()
            path.moveTo(cx + cos(a) * r, cy + sin(a) * r)
            val a1 = a + PI.toFloat() / rays
            path.lineTo(cx + cos(a1) * r * 0.32f, cy + sin(a1) * r * 0.32f)
            val a2 = a - PI.toFloat() / rays
            path.lineTo(cx + cos(a2) * r * 0.32f, cy + sin(a2) * r * 0.32f)
            path.close(); canvas.drawPath(path, p)
        }
        canvas.drawCircle(cx, cy, r * 0.34f, p)
    }

    // ---------- Claude ----------

    private fun drawClaude(canvas: Canvas, night: Float) {
        val base = pt(cx, cy, 0f)
        val bx = base[0]; val by = base[1]
        val bodyR = unit * 1.0f
        val sleeping = state == "sleep" && reached()
        val dancing = state == "dance"
        val walking = state == "walk"
        val breathe = sin(t * (if (sleeping) 0.9f else 1.6f)) * (if (sleeping) 0.03f else 0.02f)
        val walkBob = if (walking) abs(sin(walkPhase)) * unit * 0.14f else 0f
        val danceBob = if (dancing) abs(sin(t * 6f)) * unit * 0.18f else 0f
        val sway = if (dancing) sin(t * 6f) * unit * 0.18f else 0f
        val bob = sin(t * 1.6f) * unit * 0.04f + walkBob + danceBob
        val cyTop = by - bodyR - (if (sleeping) unit * 0.35f else bob)
        // 'chy' is the character's head height on screen; 'dx' shifts him when dancing.
        val chy = cyTop
        val dx = bx + sway

        // Soft shadow.
        p.color = 0x44000000
        canvas.drawOval(RectF(dx - bodyR * 0.9f, by - unit * 0.2f, dx + bodyR * 0.9f, by + unit * 0.2f), p)

        // Glow when selected.
        if (selected) {
            p.shader = RadialGradient(dx, chy, bodyR * 2.4f, 0x55D97757, 0x00D97757, Shader.TileMode.CLAMP)
            canvas.drawCircle(dx, chy, bodyR * 2.4f, p); p.shader = null
        }

        // Body: a rounded, friendly blob.
        p.color = CLAUDE
        val body = RectF(dx - bodyR, chy - bodyR * (1.1f + breathe), dx + bodyR, by)
        canvas.drawRoundRect(body, bodyR, bodyR, p)
        // Belly highlight.
        p.color = 0x22FFFFFF
        canvas.drawOval(RectF(dx - bodyR * 0.5f, chy - bodyR * 0.3f, dx + bodyR * 0.4f, by - unit * 0.2f), p)

        // Little antenna spark on top, slowly spinning.
        drawSpark(canvas, dx, chy - bodyR * (1.15f + breathe) - unit * 0.1f, unit * 0.3f, 0xFFFFC9A8.toInt(), t * 0.8f)

        // Eyes (blink) + smile.
        val eyeY = chy - bodyR * 0.15f
        val open = if (sleeping) 0.05f else if (blink > 0f || blink < -0.12f) 1f else (0.12f + blink) / 0.12f
        p.color = 0xFF2A1C14.toInt()
        val ew = unit * 0.12f; val eh = unit * 0.2f * open.coerceIn(0.1f, 1f)
        canvas.drawOval(RectF(dx - unit * 0.42f - ew, eyeY - eh, dx - unit * 0.42f + ew, eyeY + eh), p)
        canvas.drawOval(RectF(dx + unit * 0.42f - ew, eyeY - eh, dx + unit * 0.42f + ew, eyeY + eh), p)
        // Eye sparkles.
        if (open > 0.6f) { p.color = 0xCCFFFFFF.toInt(); canvas.drawCircle(dx - unit * 0.4f, eyeY - eh * 0.4f, unit * 0.04f, p); canvas.drawCircle(dx + unit * 0.44f, eyeY - eh * 0.4f, unit * 0.04f, p) }
        // Smile - bigger when selected.
        p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.07f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF2A1C14.toInt()
        val smile = Path(); val smY = chy + bodyR * 0.2f; val sw = unit * (if (selected) 0.4f else 0.3f)
        smile.moveTo(dx - sw, smY); smile.quadTo(dx, smY + unit * (if (selected) 0.4f else 0.26f), dx + sw, smY)
        canvas.drawPath(smile, p); p.style = Paint.Style.FILL

        // Cheeks.
        p.color = 0x33C45A3B
        canvas.drawCircle(dx - unit * 0.6f, smY, unit * 0.14f, p); canvas.drawCircle(dx + unit * 0.6f, smY, unit * 0.14f, p)

        // Arm wave when selected.
        if (waveT > 0f) {
            val swing = sin((1.3f - waveT) * 18f) * 0.5f
            p.color = CLAUDE_DARK; p.style = Paint.Style.STROKE; p.strokeWidth = unit * 0.22f; p.strokeCap = Paint.Cap.ROUND
            val sx = dx + bodyR * 0.9f; val sy = chy + bodyR * 0.1f
            canvas.drawLine(sx, sy, sx + unit * (0.5f + swing * 0.2f), sy - unit * (0.7f + swing), p)
            p.style = Paint.Style.FILL; canvas.drawCircle(sx + unit * (0.5f + swing * 0.2f), sy - unit * (0.7f + swing), unit * 0.18f, p)
        }
        // Sleepy Zzz float up at night.
        if (sleeping) {
            p.textAlign = Paint.Align.LEFT
            p.typeface = GlassUi.font(context, R.font.inter_semibold)
            for (i in 0 until 3) {
                val ph = (zzz * 0.6f + i * 0.4f) % 1.2f
                p.color = ((1f - ph / 1.2f) * 200).toInt().coerceIn(0, 255) shl 24 or 0xFFFFFF
                p.textSize = unit * (0.3f + i * 0.12f)
                canvas.drawText("z", dx + bodyR * 0.6f + ph * unit * 0.8f, chy - bodyR * (1.2f + breathe) - ph * unit * 1.4f, p)
            }
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Floating music notes while something plays. */
    private fun drawNotes(canvas: Canvas) {
        p.typeface = GlassUi.font(context, R.font.inter_semibold); p.textAlign = Paint.Align.CENTER
        for (n in notes) {
            val a = pt(n.gx, n.gy, 1.6f + (1.6f - n.life) * 0.9f)
            val al = (n.life.coerceIn(0f, 1f) * 220).toInt()
            p.color = (al shl 24) or 0xD97757
            p.textSize = unit * 0.5f
            canvas.drawText(if (n.kind == 0) "♪" else if (n.kind == 1) "♫" else "♩", a[0], a[1], p)
        }
    }

    // ---------- Blocks & time ----------

    /** A rectangular box from (x0,y0) to (x1,y1) on the floor, [height] tall. */
    private fun block(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, height: Float, top: Int, side: Int, base: Float = 0f) {
        face(canvas, pt(x0, y0, base), pt(x1, y0, base), pt(x1, y0, height), pt(x0, y0, height), side)
        face(canvas, pt(x1, y0, base), pt(x1, y1, base), pt(x1, y1, height), pt(x1, y0, height), darken(side))
        face(canvas, pt(x0, y0, height), pt(x1, y0, height), pt(x1, y1, height), pt(x0, y1, height), top)
    }

    private fun darken(c: Int) = Color.rgb((Color.red(c) * 0.8f).toInt(), (Color.green(c) * 0.8f).toInt(), (Color.blue(c) * 0.8f).toInt())

    /** 0 at midday, up to 1 deep in the night. */
    private fun timeTint(): Float {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        return when {
            h < 5 || h >= 22 -> 1f
            h in 5f..8f -> (8f - h) / 3f
            h in 19f..22f -> (h - 19f) / 3f
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    private fun skyTop(n: Float) = blend(0xFF8FC7F0.toInt(), 0xFF101A36.toInt(), n)
    private fun skyBottom(n: Float) = blend(0xFFFCE3B8.toInt(), 0xFF26304F.toInt(), n)

    private fun blend(day: Int, night: Int, n: Float): Int {
        val m = n.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(day) * (1 - m) + Color.red(night) * m).toInt(),
            (Color.green(day) * (1 - m) + Color.green(night) * m).toInt(),
            (Color.blue(day) * (1 - m) + Color.blue(night) * m).toInt())
    }
}
