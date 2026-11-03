package com.morgan.minimallauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The TV guide: channels down the left, time across the top, a block for each programme.
 * Drawn in one go (so it stays smooth with hundreds of programmes) and steered with the remote:
 * left/right between programmes, up/down between channels.
 */
class EpgView(context: Context) : View(context) {

    class Row(val ch: Xtream.Live, val guide: LiveGuide.Channel?)

    var accent: Int = 0xFFFF453A.toInt()
    var onSelect: ((Row, LiveGuide.Programme?) -> Unit)? = null
    var onPlay: ((Row) -> Unit)? = null
    var onProgramme: ((Row, LiveGuide.Programme) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var visibleRows = 7
    private val rowH = 47 * d
    private val headH = 30 * d
    private val chW = 200 * d
    private val pxPerMin = 4.4f * d
    private val gap = 3 * d

    private var rows: List<Row> = emptyList()
    private var selRow = 0
    private var selTime = System.currentTimeMillis()
    private var scrollX = 0f             // px from winStart at the left edge of the grid
    private var firstRow = 0f            // which channel is at the top (animated)
    private var winStart = floorHalfHour(System.currentTimeMillis())

    private val font = ResourcesCompat.getFont(context, R.font.inter_medium) ?: Typeface.DEFAULT
    private val fontBold = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("h.mma", Locale.UK)

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        background = null
    }

    private fun floorHalfHour(t: Long) = t - t % 1_800_000L

    fun setRows(list: List<Row>) {
        rows = list
        selRow = 0; firstRow = 0f
        winStart = floorHalfHour(System.currentTimeMillis())
        selTime = System.currentTimeMillis()
        scrollX = 0f
        invalidate()
        notifySelect()
    }

    override fun onMeasure(w: Int, h: Int) {
        if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) {
            visibleRows = ((MeasureSpec.getSize(h) - headH) / rowH).toInt().coerceAtLeast(3)
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        } else setMeasuredDimension(MeasureSpec.getSize(w), (headH + rowH * visibleRows).toInt())
    }

    /** Move the highlight to a given channel (used when the category changes or the guide opens on a channel). */
    fun selectChannel(id: Int) { rows.indexOfFirst { it.ch.id == id }.takeIf { it >= 0 }?.let { selRow = it; reveal(); notifySelect(); invalidate() } }

    var onLeftEdge: (() -> Unit)? = null
    var onRecord: ((Row, LiveGuide.Programme?) -> Unit)? = null

    // ---- selection ----

    private fun progAt(r: Row, t: Long) = r.guide?.programmes?.firstOrNull { t >= it.start && t < it.stop }

    private fun notifySelect() {
        val r = rows.getOrNull(selRow) ?: return
        onSelect?.invoke(r, progAt(r, selTime))
    }

    private fun animateTo(x: Float, y: Float) {
        val sx = scrollX; val sy = firstRow
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { f -> val v = f.animatedValue as Float; scrollX = sx + (x - sx) * v; firstRow = sy + (y - sy) * v; invalidate() }
            start()
        }
    }

    private fun reveal() {
        val r = rows.getOrNull(selRow) ?: return
        val pr = progAt(r, selTime)
        val left = ((pr?.start ?: selTime) - winStart) / 60_000f * pxPerMin
        val right = ((pr?.stop ?: (selTime + 1_800_000L)) - winStart) / 60_000f * pxPerMin
        val gridW = width - chW
        var tx = scrollX
        if (left < tx) tx = left.coerceAtLeast(0f) else if (right > tx + gridW) tx = right - gridW
        var ty = firstRow
        if (selRow < ty) ty = selRow.toFloat() else if (selRow > ty + visibleRows - 1) ty = (selRow - visibleRows + 1).toFloat()
        animateTo(tx.coerceAtLeast(0f), ty.coerceIn(0f, (rows.size - visibleRows).coerceAtLeast(0).toFloat()))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val r = rows.getOrNull(selRow) ?: return super.onKeyDown(keyCode, event)
        val progs = r.guide?.programmes.orEmpty()
        val cur = progAt(r, selTime)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = progs.filter { it.start > (cur?.start ?: selTime) }.minByOrNull { it.start } ?: return true
                selTime = next.start
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val prev = progs.filter { it.start < (cur?.start ?: selTime) && it.stop > System.currentTimeMillis() - 1_800_000L }.maxByOrNull { it.start }
                if (prev == null) { onLeftEdge?.invoke(); return true }
                selTime = prev.start.coerceAtLeast(winStart)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (selRow >= rows.size - 1) return false; selRow++ }
            KeyEvent.KEYCODE_DPAD_UP -> { if (selRow <= 0) return false; selRow-- }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { selRow = minOf(rows.size - 1, selRow + visibleRows - 1) }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { selRow = maxOf(0, selRow - (visibleRows - 1)) }
            KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { onRecord?.invoke(r, cur); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val now = System.currentTimeMillis()
                if (cur == null || cur.isOn(now)) onPlay?.invoke(r) else onProgramme?.invoke(r, cur)
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        // Keep the same moment in time when moving between channels.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) selTime = selTime.coerceAtLeast(winStart)
        reveal(); notifySelect(); invalidate()
        return true
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        invalidate()
    }

    // ---- drawing ----

    private fun timeX(t: Long) = chW + (t - winStart) / 60_000f * pxPerMin - scrollX

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val now = System.currentTimeMillis()
        val focused = isFocused
        // Panel
        p.style = Paint.Style.FILL; p.color = 0x14FFFFFF
        c.drawRoundRect(0f, 0f, w, height.toFloat(), 16 * d, 16 * d, p)

        // Programmes (clipped to the grid)
        c.save()
        c.clipRect(chW, headH, w, height.toFloat())
        val top = firstRow.toInt().coerceAtLeast(0)
        for (i in top until minOf(rows.size, top + visibleRows + 2)) {
            val y = headH + (i - firstRow) * rowH
            val row = rows[i]
            val progs = row.guide?.programmes.orEmpty().filter { it.stop > winStart }
            if (progs.isEmpty()) drawCell(c, chW + gap, y, w, rowH, "${row.ch.name} · Live", i == selRow, true, focused, null)
            for (pr in progs) {
                val x1 = timeX(maxOf(pr.start, winStart)); val x2 = timeX(pr.stop)
                if (x2 < chW || x1 > w) continue
                val selected = i == selRow && selTime >= pr.start && selTime < pr.stop
                drawCell(c, maxOf(x1, chW) + gap, y, x2, rowH, pr.title, selected, pr.isOn(now), focused, pr.takeIf { it.isOn(now) }?.progress())
            }
        }
        c.restore()

        // Channel column (over the programmes): name on the left, number at the right edge.
        c.save()
        c.clipRect(0f, headH, chW, height.toFloat())
        p.style = Paint.Style.FILL; p.color = 0x22000000
        c.drawRect(0f, headH, chW, height.toFloat(), p)
        for (i in top until minOf(rows.size, top + visibleRows + 2)) {
            val y = headH + (i - firstRow) * rowH
            val sel = i == selRow
            if (sel) {
                rect.set(6 * d, y + 3 * d, chW - 4 * d, y + rowH - 3 * d)
                p.color = if (focused) 0xF5FFFFFF.toInt() else 0x59FFFFFF
                c.drawRoundRect(rect, 9 * d, 9 * d, p)
            }
            val ink = if (sel && focused) GlassUi.INK else if (sel) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            text.typeface = if (sel) fontBold else font; text.textSize = 13 * d; text.color = ink
            val num = rows[i].ch.num.toString()
            val numW = text.measureText(num)
            c.drawText(ellipsize(rows[i].ch.name, chW - numW - 34 * d, text), 14 * d, y + rowH / 2 + 4.5f * d, text)
            text.color = if (sel && focused) 0x99000000.toInt() else 0x99FFFFFF.toInt()
            c.drawText(num, chW - numW - 12 * d, y + rowH / 2 + 4.5f * d, text)
        }
        c.restore()

        // Time header
        text.typeface = fontBold; text.textSize = 12 * d; text.color = 0xB3FFFFFF.toInt()
        c.drawText("Today", 14 * d, headH * 0.68f, text)
        c.save(); c.clipRect(chW, 0f, w, headH)
        var t = winStart
        while (timeX(t) < w) {
            val x = timeX(t)
            if (x >= chW - 4 * d) {
                text.typeface = font; text.color = 0xB3FFFFFF.toInt()
                c.drawText(clock.format(Date(t)).lowercase(), x + gap + 4 * d, headH * 0.68f, text)
                p.color = 0x24FFFFFF; c.drawRect(x, headH * 0.2f, x + 1 * d, headH, p)
            }
            t += 1_800_000L
        }
        c.restore()
        // "Now" marker
        val nx = timeX(now)
        if (nx > chW && nx < w) {
            p.color = 0x40FFFFFF; c.drawRect(nx, headH, nx + 1f * d, height.toFloat(), p)          // a faint line down the grid
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(nx + 0.5f * d, headH - 3 * d, 3 * d, p)      // and a marker on the time bar
        }
    }

    private val rect = RectF()

    private fun drawCell(c: Canvas, x1: Float, y: Float, x2: Float, h: Float, title: String, selected: Boolean, live: Boolean, focused: Boolean, progress: Float?) {
        val left = x1; val right = x2 - gap
        if (right - left < 6 * d) return
        rect.set(left, y + 3 * d, right, y + h - 3 * d)
        p.style = Paint.Style.FILL
        if (selected) {
            p.color = if (focused) 0xF5FFFFFF.toInt() else 0x59FFFFFF
            c.drawRoundRect(rect, 9 * d, 9 * d, p)
        } else {
            // A thin divider between programmes, rather than a box round each one.
            p.color = 0x30FFFFFF
            c.drawRect(left - gap, y + 6 * d, left - gap + 1 * d, y + h - 6 * d, p)
        }
        text.typeface = if (selected || live) fontBold else font
        text.textSize = 12.5f * d
        text.color = if (selected && focused) GlassUi.INK else if (live || selected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        c.drawText(ellipsize(title, rect.width() - 20 * d, text), rect.left + 10 * d, rect.centerY() + 4.5f * d, text)
        if (progress != null) {
            p.color = if (selected && focused) 0x66000000 else 0xA6FFFFFF.toInt()
            c.drawRoundRect(rect.left + 8 * d, rect.bottom - 5 * d, rect.left + 8 * d + (rect.width() - 16 * d) * progress, rect.bottom - 3 * d, 2 * d, 2 * d, p)
        }
    }

    private fun ellipsize(s: String, maxW: Float, paint: Paint): String {
        if (maxW <= 0) return ""
        if (paint.measureText(s) <= maxW) return s
        val n = paint.breakText(s, true, maxW - paint.measureText("…"), null)
        return if (n <= 0) "" else s.substring(0, n).trimEnd() + "…"
    }
}
