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
 * A little pixel cavapoo who lives in a strip above the dock, with her own house,
 * bed and bone on the right. She wanders, sniffs the app you've selected (from above,
 * never covering it), sits and looks at you, fetches her bone and chews it, and
 * sleeps in her bed (all night, and for naps in the day).
 * The art is in DogSprites (one character per pixel), drawn with hard edges.
 */
class DogPet(context: Context) : View(context) {

    private val d = context.resources.displayMetrics.density
    private val k = 1.1f * d                                   // screen pixels per art pixel

    private val colours = mapOf(
        'o' to 0xFF54301B.toInt(), 'F' to 0xFFDE9A5A.toInt(), 'H' to 0xFFF1BF88.toInt(), 'D' to 0xFFB8733C.toInt(),
        'C' to 0xFFF8E8CE.toInt(), 'c' to 0xFFE2C8A0.toInt(), 'K' to 0xFF1E1511.toInt(), 'W' to 0xFFFFFFFF.toInt(),
        'R' to 0xFF8E241C.toInt(), 'r' to 0xFFB83C2E.toInt(), 'G' to 0xFFEEBE4C.toInt(), 'T' to 0xFFF08A99.toInt(),
        'N' to 0xFF2A1A18.toInt(), 'B' to 0xFFEFE4D0.toInt(), 'b' to 0xFFC9B89A.toInt())

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

    private val blit = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class S { IDLE, WALK, SNIFF, SLEEP, CHEW }

    // ---------- The world ----------
    private var minX = 0f
    private var tiles: List<Float> = emptyList()
    private var ready = false

    private fun houseX0() = width - 30f * d - houseBmp.width * k
    private fun bedX0() = houseX0() - 8f * d - bedBmp.width * k
    private fun bedCentre() = bedX0() + bedBmp.width * k / 2f
    private fun maxX() = bedX0() - 10f * d
    private fun chewX() = bedX0() - 46f * d

    // ---------- Life ----------
    private var state = S.IDLE
    private var x = -1f
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

    /** Where she may walk from, and where each app is (she stays above the dock, never on it). */
    fun setWorld(min: Float, appCentres: List<Float>) {
        minX = min; tiles = appCentres; ready = true
    }

    /** You've moved onto an app: she trots over and sniffs it from above. */
    fun attend(cx: Float) {
        if (!ready || x < 0f) return
        if (carrying) { carrying = false; boneX = x; boneOnGround = true }
        if (state == S.CHEW) { boneX = x + chewBoneOffset(); boneOnGround = true }
        goTo(cx.coerceIn(minX, maxX()), fast = true) { sniff(2.5f) }
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
        val base = if (tiles.isEmpty()) (minX + maxX()) / 2f else tiles.random()
        return (base + (Random.nextFloat() - 0.5f) * 14f * d).coerceIn(minX, maxX())
    }

    private fun toBed(sleepFor: Float) {
        goTo(bedCentre()) { state = S.SLEEP; timer = sleepFor; zzz = 0f; dir = 1f }
    }

    /** Fetch the bone, carry it over and chew it. */
    private fun fetch() {
        if (!boneOnGround) { sitFor(2f); return }
        goTo(boneX.coerceIn(minX, maxX())) {
            carrying = true; boneOnGround = false
            goTo(chewX()) { carrying = false; dir = 1f; state = S.CHEW; timer = 7f + Random.nextFloat() * 5f }
        }
    }

    private fun decide() {
        if (isNight()) { toBed(120f + Random.nextFloat() * 60f); return }
        val r = Random.nextFloat()
        when {
            r < 0.30f -> goTo(randomApp()) { sniff(2.5f + Random.nextFloat() * 2f) }
            r < 0.48f -> sitFor(2.5f + Random.nextFloat() * 3f)
            r < 0.70f -> fetch()
            r < 0.84f -> toBed(10f + Random.nextFloat() * 12f)
            else -> goTo(randomApp()) { sitFor(1.5f) }
        }
    }

    private fun step(dt: Float) {
        if (width == 0 || !ready) return
        t += dt
        blink -= dt
        if (blink < -0.12f) blink = 2f + Random.nextFloat() * 4f
        if (x < 0f) { x = (minX + maxX()) / 2f; boneX = (maxX() - 60f * d).coerceAtLeast(minX); timer = 1f }

        when (state) {
            S.WALK -> {
                val dx = targetX - x
                val speed = (if (carrying) 24f else 30f) * d * (if (hurry) 1.5f else 1f)
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

    override fun onDraw(canvas: Canvas) {
        if (x < 0f) return
        val ground = height - 2f * d

        // A faint floor, so the house and bed aren't floating.
        p.color = 0x2EFFFFFF
        canvas.drawRoundRect(RectF(minX - 8f * d, ground, width - 22f * d, ground + 2f * d), d, d, p)

        draw(canvas, houseBmp, houseX0() + houseBmp.width * k / 2f, ground)
        draw(canvas, bedBmp, bedCentre(), ground)
        if (boneOnGround && !carrying && state != S.CHEW) draw(canvas, boneBmp, boneX, ground)

        val flip = dir < 0f
        val anchor = DogSprites.SIDE_ANCHOR.toFloat()
        when (state) {
            S.SLEEP -> draw(canvas, sleepBmp, bedCentre(), ground - 2.5f * k)
            S.CHEW -> draw(canvas, chewBmp, x, ground - (if (sin(t * 6f) > 0f) 0.6f * k else 0f))
            S.SNIFF -> draw(canvas, if (sin(t * 12f) > 0f) sniffA else sniffB, x, ground, flip, anchor)
            S.WALK -> {
                val a = ((phase.toInt()) % 2 == 0)
                val bmp = if (carrying) (if (a) carryA else carryB) else (if (a) walkA else walkB)
                draw(canvas, bmp, x, ground, flip, anchor)
            }
            S.IDLE -> draw(canvas, if (blink < 0f && blink > -0.12f) sitBlink else sit, x, ground)
        }

        if (state == S.SLEEP) {
            text.textAlign = Paint.Align.LEFT
            for (i in 0 until 3) {
                val ph = (zzz * 0.5f + i * 0.5f) % 1.5f
                text.color = (((1f - ph / 1.5f) * 230).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                text.textSize = (7f + i * 2.5f) * d
                canvas.drawText("z", bedCentre() + 9f * d + ph * 6f * d, ground - 16f * d - ph * 14f * d, text)
            }
        }
    }
}
