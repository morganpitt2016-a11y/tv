package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A little pixel cavapoo who lives beside the dock. Her house, bed and a patch of
 * grass are at the right, level with the bottoms of the apps; a small staircase
 * leads up to the top of the dock, where she walks along and sniffs the app you've
 * selected (from above, so she never covers it). She also sits and looks at you,
 * fetches her bone and chews it, and goes down to her bed to nap and sleep at night.
 * The art is in DogSprites (one character per pixel), drawn with hard edges.
 */
class DogPet(context: Context) : View(context) {

    private val d = context.resources.displayMetrics.density
    private val k = 1.0f * d                                   // screen pixels per art pixel

    private val colours = mapOf(
        'o' to 0xFF54301B.toInt(), 'F' to 0xFFDE9A5A.toInt(), 'H' to 0xFFF1BF88.toInt(), 'D' to 0xFFB8733C.toInt(),
        'C' to 0xFFF8E8CE.toInt(), 'c' to 0xFFE2C8A0.toInt(), 'K' to 0xFF1E1511.toInt(), 'W' to 0xFFFFFFFF.toInt(),
        'R' to 0xFF8E241C.toInt(), 'r' to 0xFFB83C2E.toInt(), 'G' to 0xFFEEBE4C.toInt(), 'T' to 0xFFF08A99.toInt(),
        'N' to 0xFF2A1A18.toInt(), 'B' to 0xFFEFE4D0.toInt(), 'b' to 0xFFC9B89A.toInt(),
        '1' to 0xFF4FA345.toInt(), '2' to 0xFF6CC255.toInt(), '3' to 0xFF387E36.toInt(),
        '4' to 0xFFFFD84A.toInt(), '5' to 0xFFFFFFFF.toInt(), '6' to 0xFFF27AA4.toInt())

    private fun make(rows: Array<String>): Bitmap {
        val w = rows.maxOf { it.length }
        val bmp = Bitmap.createBitmap(w, rows.size, Bitmap.Config.ARGB_8888)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> colours[ch]?.let { bmp.setPixel(x, y, it) } } }
        return bmp
    }

    private val walkA = make(DogSprites.WALK_A)
    private val walkB = make(DogSprites.WALK_B)
    private val carryA = make(DogSprites.CARRY_A)
    private val carryB = make(DogSprites.CARRY_B)
    private val sniffA = make(DogSprites.SNIFF_A)
    private val sniffB = make(DogSprites.SNIFF_B)
    private val sit = make(DogSprites.SIT)
    private val sitBlink = make(DogSprites.SIT_BLINK)
    private val sleepBmp = make(DogSprites.SLEEP)
    private val chewBmp = make(DogSprites.CHEW)
    private val boneBmp = make(DogSprites.BONE)
    private val houseBmp = make(DogSprites.HOUSE)
    private val bedBmp = make(DogSprites.BED)
    private val grassBmp = make(DogSprites.GRASS)

    private val blit = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val px = Paint().apply { isAntiAlias = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class S { IDLE, WALK, SNIFF, SLEEP, CHEW }

    // ---------- The world (set by the home screen) ----------
    private var minX = 0f                 // left end of the top walkway
    private var dockRight = 0f            // where the dock (and the walkway) ends
    private var walkY = 0f                // the top of the dock: where she walks
    private var groundY = 0f              // the bottoms of the apps: grass, house, bed
    private var tiles: List<Float> = emptyList()
    private var ready = false

    private fun treadW() = 9f * d
    private fun stairsX0() = dockRight + 2f * d
    private fun stairsX1() = stairsX0() + 3f * treadW()
    private fun rise() = (groundY - walkY) / 4f
    private fun bedX0() = stairsX1() + 2f * d
    private fun bedCentre() = bedX0() + bedBmp.width * k / 2f
    private fun houseX0() = width - 3f * d - houseBmp.width * k
    private fun walkMax() = dockRight - 8f * d
    private fun chewX() = walkMax() - 70f * d

    /** The height of whatever she'd be standing on at [x]: the walkway, a stair, or the grass. */
    private fun surfaceY(x: Float): Float = when {
        x < stairsX0() -> walkY
        x >= stairsX1() -> groundY
        else -> walkY + rise() * (1 + ((x - stairsX0()) / treadW()).toInt().coerceIn(0, 2))
    }

    // ---------- Life ----------
    private var state = S.IDLE
    private var x = -1f
    private var y = 0f
    private var dir = 1f
    private var timer = 1.5f
    private var targetX = 0f
    private var then: (() -> Unit)? = null
    private var hurry = false
    private var carrying = false
    private var boneX = 0f
    private var boneOnGround = true
    private var t = 0f
    private var phase = 0f
    private var blink = 3f
    private var zzz = 0f

    init {
        isFocusable = false
        isClickable = false
        text.typeface = GlassUi.font(context, R.font.inter_semibold)
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

    /** Where the dock is (all in this view's pixels) and where each app sits along it. */
    fun setWorld(min: Float, dockEnd: Float, walk: Float, ground: Float, appCentres: List<Float>) {
        minX = min; dockRight = dockEnd; walkY = walk; groundY = ground; tiles = appCentres; ready = true
        if (x >= 0f) y = surfaceY(x)
    }

    /** You've moved onto an app: she trots over and sniffs it from above. */
    fun attend(cx: Float) {
        if (!ready || x < 0f) return
        if (carrying) { carrying = false; boneX = x; boneOnGround = true }
        if (state == S.CHEW) { boneX = x + chewBoneOffset(); boneOnGround = true }
        goTo(cx.coerceIn(minX, walkMax()), fast = true) { sniff(2.5f) }
    }

    // ---------- Behaviour ----------

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    private fun chewBoneOffset() = (chewBmp.width / 2f - 4f) * k

    private fun goTo(px: Float, fast: Boolean = false, cb: (() -> Unit)? = null) {
        targetX = px; then = cb; hurry = fast; state = S.WALK
    }

    private fun sniff(sec: Float) { state = S.SNIFF; timer = sec }
    private fun sitFor(sec: Float) { state = S.IDLE; timer = sec }

    private fun randomApp(): Float {
        val base = if (tiles.isEmpty()) (minX + walkMax()) / 2f else tiles.random()
        return (base + (Random.nextFloat() - 0.5f) * 14f * d).coerceIn(minX, walkMax())
    }

    private fun randomGrass(): Float = stairsX1() + 6f * d + Random.nextFloat() * 12f * d

    /** Down the steps to her bed. */
    private fun toBed(sleepFor: Float) {
        goTo(bedCentre()) { state = S.SLEEP; timer = sleepFor; zzz = 0f; dir = 1f }
    }

    /** Fetch the bone, carry it along the walkway and chew it. */
    private fun fetch() {
        if (!boneOnGround) { sitFor(2f); return }
        goTo(boneX.coerceIn(minX, walkMax())) {
            carrying = true; boneOnGround = false
            goTo(chewX()) { carrying = false; dir = 1f; state = S.CHEW; timer = 7f + Random.nextFloat() * 5f }
        }
    }

    private fun decide() {
        if (isNight()) { toBed(120f + Random.nextFloat() * 60f); return }
        val r = Random.nextFloat()
        when {
            r < 0.28f -> goTo(randomApp()) { sniff(2.5f + Random.nextFloat() * 2f) }
            r < 0.44f -> sitFor(2.5f + Random.nextFloat() * 3f)
            r < 0.62f -> fetch()
            r < 0.74f -> toBed(10f + Random.nextFloat() * 12f)
            r < 0.88f -> goTo(randomGrass()) { sitFor(2.5f + Random.nextFloat() * 2f) }
            else -> goTo(randomApp()) { sitFor(1.5f) }
        }
    }

    private fun step(dt: Float) {
        if (width == 0 || !ready) return
        t += dt
        blink -= dt
        if (blink < -0.12f) blink = 2f + Random.nextFloat() * 4f
        if (x < 0f) {
            x = (minX + walkMax()) / 2f; y = walkY
            boneX = (walkMax() - 150f * d).coerceAtLeast(minX)
            timer = 1f
        }

        // She follows the stairs up and down (a quick hop from step to step).
        val surf = if (state == S.SLEEP) groundY - 2.5f * k else surfaceY(x)
        y += (surf - y) * minOf(1f, 14f * dt)

        when (state) {
            S.WALK -> {
                val dx = targetX - x
                val onStairs = x >= stairsX0() && x < stairsX1()
                val speed = (if (carrying) 24f else 30f) * d * (if (hurry) 1.5f else 1f) * (if (onStairs) 0.75f else 1f)
                if (abs(dx) <= speed * dt) {
                    x = targetX
                    val cb = then; then = null
                    state = S.IDLE; timer = 1.2f
                    cb?.invoke()
                } else {
                    dir = if (dx > 0f) 1f else -1f
                    x += dir * speed * dt
                    phase += dt * 7f
                }
            }
            S.SNIFF -> { timer -= dt; if (timer <= 0f) { state = S.IDLE; timer = 2f } }
            S.CHEW -> {
                timer -= dt
                if (timer <= 0f) { boneX = x + chewBoneOffset(); boneOnGround = true; state = S.IDLE; timer = 1.5f }
            }
            S.SLEEP -> {
                zzz += dt; timer -= dt
                if (timer <= 0f) { if (isNight()) timer = 60f else { state = S.IDLE; timer = 1.5f; zzz = 0f } }
            }
            S.IDLE -> { timer -= dt; if (timer <= 0f) decide() }
        }
    }

    // ---------- Drawing ----------

    /** Draw [bmp] with its bottom on [bottom] and its [anchor] column at [cx]. */
    private fun draw(canvas: Canvas, bmp: Bitmap, cx: Float, bottom: Float, flip: Boolean = false, anchor: Float = bmp.width / 2f) {
        canvas.save()
        canvas.translate(cx, bottom)
        canvas.scale(if (flip) -k else k, k)
        canvas.drawBitmap(bmp, -anchor, -bmp.height.toFloat(), blit)
        canvas.restore()
    }

    private fun drawStairs(canvas: Canvas) {
        val w = treadW()
        for (i in 0 until 3) {
            val left = stairsX0() + i * w
            val top = walkY + rise() * (i + 1)
            px.color = 0xFF54301B.toInt(); canvas.drawRect(left, top - d, left + w + d, groundY + d, px)     // outline
            px.color = 0xFF9A6A3C.toInt(); canvas.drawRect(left, top, left + w, groundY, px)                    // wood
            px.color = 0xFF7C5230.toInt(); canvas.drawRect(left, top + (groundY - top) * 0.55f, left + w, top + (groundY - top) * 0.55f + d, px)
            px.color = 0xFFC08A52.toInt(); canvas.drawRect(left, top, left + w, top + 3f * d, px)               // lit tread
            px.color = 0xFFE0AE74.toInt(); canvas.drawRect(left, top, left + w, top + d, px)
        }
    }

    private fun drawGrass(canvas: Canvas) {
        val from = stairsX1() - 2f * d
        val to = width - 1f * d
        val tw = grassBmp.width * k
        val top = groundY - 3f * k
        canvas.save()
        canvas.clipRect(from, top, to, top + grassBmp.height * k)
        var gx = from
        while (gx < to) {
            canvas.drawBitmap(grassBmp, null, RectF(gx, top, gx + tw, top + grassBmp.height * k), blit)
            gx += tw
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        if (x < 0f) return

        drawStairs(canvas)
        drawGrass(canvas)
        draw(canvas, houseBmp, houseX0() + houseBmp.width * k / 2f, groundY)
        draw(canvas, bedBmp, bedCentre(), groundY)
        if (boneOnGround && !carrying && state != S.CHEW) draw(canvas, boneBmp, boneX, surfaceY(boneX))

        val flip = dir < 0f
        val anchor = DogSprites.SIDE_ANCHOR.toFloat()
        when (state) {
            S.SLEEP -> draw(canvas, sleepBmp, bedCentre(), y)
            S.CHEW -> draw(canvas, chewBmp, x, y - (if (sin(t * 6f) > 0f) 0.6f * k else 0f))
            S.SNIFF -> draw(canvas, if (sin(t * 12f) > 0f) sniffA else sniffB, x, y, flip, anchor)
            S.WALK -> {
                val a = ((phase.toInt()) % 2 == 0)
                val bmp = if (carrying) (if (a) carryA else carryB) else (if (a) walkA else walkB)
                draw(canvas, bmp, x, y, flip, anchor)
            }
            S.IDLE -> draw(canvas, if (blink < 0f && blink > -0.12f) sitBlink else sit, x, y)
        }

        if (state == S.SLEEP) {
            text.textAlign = Paint.Align.LEFT
            for (i in 0 until 3) {
                val ph = (zzz * 0.5f + i * 0.5f) % 1.5f
                text.color = (((1f - ph / 1.5f) * 230).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                text.textSize = (7f + i * 2.5f) * d
                canvas.drawText("z", bedCentre() + 8f * d + ph * 6f * d, groundY - 18f * d - ph * 14f * d, text)
            }
        }
    }
}
