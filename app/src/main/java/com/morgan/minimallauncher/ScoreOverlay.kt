package com.morgan.minimallauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import kotlin.random.Random

/**
 * The corner scoreboard, drawn over everything: one card per live match, styled like a broadcast score bug -
 * the competition's colour, each team's kit colour and crest, the scores in boxes and the clock. When something
 * happens the number rolls, that side flashes and a banner slides out underneath ("Try – Ireland (Lowe)",
 * "TMO – No try", "Yellow card", "FT · Ireland win 27–22"). Scores can set off confetti in the team's colours.
 */
class ScoreOverlay(private val c: Context) {
    companion object {
        @Volatile private var current: ScoreOverlay? = null

        /**
         * The launcher's top-right controls (the home screen's round buttons, Watch's last tabs) sit where the score
         * box does: while you're on them it slides out to the right, and comes back when you move away.
         */
        fun stepAside(on: Boolean) {
            val o = current ?: return
            o.aside = on
            o.slide(!on && o.peeking)
        }
    }

    // Like an Apple TV Live Activity: it slides in when something happens, then gets out of the way.
    @Volatile private var aside = false
    @Volatile private var peeking = false
    private var lastPeek = 0L
    private var lastSig = ""
    private val hide = Runnable { peeking = false; slide(false) }

    /** Show for [ms], then slide away. */
    private fun peek(ms: Long) {
        peeking = true
        lastPeek = System.currentTimeMillis()
        main.removeCallbacks(hide); main.postDelayed(hide, ms)
        slide(!aside)
    }

    private fun slide(show: Boolean) {
        val col = column ?: return
        col.post {
            col.animate().cancel()
            col.animate().translationX(if (show) 0f else col.width.toFloat() * 1.15f).alpha(if (show) 1f else 0f)
                .setDuration(if (show) 420L else 360L).setInterpolator(Motion.standard).start()
        }
    }

    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var column: LinearLayout? = null
    private val rows = LinkedHashMap<String, Row>()

    private class Row(val root: LinearLayout, val card: LinearLayout, val home: TextView, val away: TextView, val clock: TextView,
                      val homeBar: View, val awayBar: View, val banner: TextView)

    fun render(cards: List<LiveScores.Card>, events: List<LiveScores.Event>) {
        if (cards.isEmpty() || Prefs.bool(c, "dnd", false)) { removeAll(); return }   // Do Not Disturb (Control Centre): no pop-ups
        val col = column ?: makeColumn() ?: return
        // Gone: fade away.
        for (id in rows.keys.toList()) if (cards.none { it.id == id }) rows.remove(id)?.let { r ->
            r.root.animate().alpha(0f).translationX(dp(c, 30f).toFloat()).setDuration(350).withEndAction { col.removeView(r.root) }.start()
        }
        // When to show: a match appearing, a score changing, half/full time - and a short glance every 15 minutes.
        val sig = cards.joinToString("|") { "${it.id}:${it.homeScore}-${it.awayScore}:${it.hideScore}:${it.clock.takeIf { c -> c.any { ch -> ch.isLetter() } }.orEmpty()}" }
        val changed = sig != lastSig
        lastSig = sig
        for (card in cards) {
            val r = rows[card.id] ?: makeRow(card).also { rows[card.id] = it; col.addView(it.root); slideIn(it.root) }
            setScore(r.home, if (card.hideScore) "–" else card.homeScore)
            setScore(r.away, if (card.hideScore) "–" else card.awayScore)
            r.clock.text = if (card.hideScore) "LIVE" else card.clock
        }
        for (e in events) {
            val r = rows[e.id] ?: continue
            when (e.kind) {
                "score" -> { pulse(r.card); flash(if (e.side == 0) r.homeBar else r.awayBar); banner(r, "🎉  ${e.banner}", e.colors.firstOrNull() ?: 0xFF2BB673.toInt())
                    if (e.celebrate && LiveScores.confetti(c)) confetti(e.colors.filter { it != 0 }.ifEmpty { listOf(0xFFFFD60A.toInt(), 0xFFFFFFFF.toInt()) }) }
                "disallowed" -> banner(r, "✋  ${e.banner}", 0xFF8A8F98.toInt())
                "yellow" -> banner(r, "🟨  ${e.banner}", 0xFFE6B800.toInt())
                "red" -> banner(r, "🟥  ${e.banner}", 0xFFC62828.toInt())
                "ft" -> { pulse(r.card); banner(r, "🏁  ${e.banner}", 0xFF1F1F24.toInt(), hold = 20_000L) }
            }
        }
        when {
            events.any { it.kind == "ft" } -> peek(22_000L)
            events.isNotEmpty() || changed -> peek(10_000L)
            !peeking && System.currentTimeMillis() - lastPeek > 15 * 60_000L -> peek(6_000L)
        }
    }

    private fun makeColumn(): LinearLayout? {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; clipChildren = false; clipToPadding = false
            setPadding(dp(c, 12f), dp(c, 6f), dp(c, 4f), dp(c, 6f)) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(c, 28f); y = dp(c, 22f) }
        return try { wm.addView(col, params); column = col; current = this; col } catch (e: Exception) { null }
    }

    private fun removeAll() {
        val col = column ?: return
        column = null; rows.clear()
        col.animate().alpha(0f).setDuration(400).withEndAction { try { wm.removeView(col) } catch (_: Exception) {} }.start()
    }

    private fun bold() = Typeface.create(GlassUi.font(c, R.font.inter_semibold), Typeface.BOLD)

    private fun makeRow(card: LiveScores.Card): Row {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; clipChildren = false; setPadding(0, 0, 0, dp(c, 8f)) }
        val box = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(c, 12f).toFloat(); setColor(0xF0121216.toInt()); setStroke(dp(c, 1f), 0x33FFFFFF) }
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dp(c, 6f).toFloat()
        }
        // Competition colour down the left edge.
        box.addView(View(c).apply { setBackgroundColor(card.accent) }, LinearLayout.LayoutParams(dp(c, 4f), dp(c, 40f)))
        fun side(abbr: String, logo: String?, colour: Int, crestFirst: Boolean): Pair<LinearLayout, View> {
            val l = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 8f), 0, dp(c, 8f), 0) }
            val bar = View(c).apply { background = GradientDrawable().apply { cornerRadius = dp(c, 2f).toFloat(); setColor(colour) } }
            val crest = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; logo?.let { ImageLoader.load(this, it, ImageLoader.ICON) } }
            val name = TextView(c).apply { text = abbr.uppercase(); setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; typeface = bold(); letterSpacing = 0.04f }
            val views = if (crestFirst) listOf<View>(bar, crest, name) else listOf(name, crest, bar)
            for (v in views) l.addView(v, when (v) {
                bar -> LinearLayout.LayoutParams(dp(c, 4f), dp(c, 22f)).apply { if (crestFirst) marginEnd = dp(c, 7f) else marginStart = dp(c, 7f) }
                crest -> LinearLayout.LayoutParams(dp(c, 20f), dp(c, 20f)).apply { if (crestFirst) marginEnd = dp(c, 6f) else { marginStart = dp(c, 6f) } }
                else -> LinearLayout.LayoutParams(-2, -2)
            })
            return l to bar
        }
        fun scoreBox() = TextView(c).apply {
            gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); textSize = 17f; typeface = bold(); fontFeatureSettings = "tnum"
            background = GradientDrawable().apply { cornerRadius = dp(c, 6f).toFloat(); setColor(0x26FFFFFF) }
            setPadding(dp(c, 8f), dp(c, 2f), dp(c, 8f), dp(c, 2f)); minWidth = dp(c, 34f)
        }
        val (home, homeBar) = side(card.homeAbbr, card.homeLogo, card.homeColor, crestFirst = true)
        val (away, awayBar) = side(card.awayAbbr, card.awayLogo, card.awayColor, crestFirst = false)
        val hs = scoreBox(); val aws = scoreBox()
        box.addView(home)
        box.addView(FrameLayout(c).apply { clipChildren = true; addView(hs) })
        box.addView(TextView(c).apply { text = "–"; setTextColor(0x99FFFFFF.toInt()); textSize = 15f; setPadding(dp(c, 5f), 0, dp(c, 5f), 0) })
        box.addView(FrameLayout(c).apply { clipChildren = true; addView(aws) })
        box.addView(away)
        val clock = TextView(c).apply {
            setTextColor(0xFF000000.toInt()); textSize = 12f; typeface = bold(); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(c, 6f).toFloat(); setColor(card.accent) }
            setPadding(dp(c, 7f), dp(c, 2f), dp(c, 7f), dp(c, 2f))
        }
        box.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 10f); marginStart = dp(c, 2f) })
        root.addView(box)
        val banner = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = bold()
            setPadding(dp(c, 12f), dp(c, 6f), dp(c, 12f), dp(c, 6f)); visibility = View.GONE
        }
        root.addView(banner, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
        hs.text = card.homeScore; aws.text = card.awayScore
        return Row(root, box, hs, aws, clock, homeBar, awayBar, banner)
    }

    // ---------- Motion ----------

    private fun slideIn(v: View) {
        v.alpha = 0f; v.translationX = dp(c, 40f).toFloat()
        v.animate().alpha(1f).translationX(0f).setDuration(450).setInterpolator(Motion.enter).start()
    }

    /** The number rolls up (or down) to the new score. */
    private fun setScore(t: TextView, value: String) {
        if (t.text.toString() == value) return
        if (t.text.isNullOrEmpty()) { t.text = value; return }
        val up = (value.toIntOrNull() ?: 0) >= (t.text.toString().toIntOrNull() ?: 0)
        val h = t.height.takeIf { it > 0 } ?: dp(c, 24f)
        t.animate().translationY(if (up) -h * 0.35f else h * 0.35f).setDuration(140).withEndAction {
            t.text = value
            t.translationY = if (up) h * 0.35f else -h * 0.35f
            t.animate().translationY(0f).setDuration(220).setInterpolator(Motion.enter).start()
        }.start()
    }

    private fun pulse(v: View) {
        v.pivotX = v.width.toFloat()          // grows from the right edge, so nothing slides off screen
        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(Motion.enter).start() }.start()
    }

    private fun flash(bar: View) {
        ValueAnimator.ofFloat(1f, 0.2f, 1f, 0.2f, 1f, 0.2f, 1f).apply { duration = 1400; addUpdateListener { bar.alpha = it.animatedValue as Float }; start() }
        bar.animate().scaleY(1.3f).setDuration(300).withEndAction { bar.animate().scaleY(1f).setDuration(600).start() }.start()
    }

    private fun banner(r: Row, text: String, colour: Int, hold: Long = 9_000L) {
        val b = r.banner
        b.text = text
        val light = (android.graphics.Color.red(colour) * 299 + android.graphics.Color.green(colour) * 587 + android.graphics.Color.blue(colour) * 114) / 1000 > 170
        b.setTextColor(if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        b.background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(colour) }
        b.visibility = View.VISIBLE; b.alpha = 0f; b.translationY = -dp(c, 10f).toFloat()
        b.animate().alpha(1f).translationY(0f).setDuration(350).setInterpolator(Motion.enter).start()
        main.removeCallbacksAndMessages(b)
        main.postAtTime({ b.animate().alpha(0f).setDuration(400).withEndAction { b.visibility = View.GONE }.start() }, b, android.os.SystemClock.uptimeMillis() + hold)
    }

    /** A short burst of confetti in the scoring team's colours, over the whole screen. */
    private fun confetti(colours: List<Int>) {
        val view = Confetti(c, colours)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(view, params) } catch (e: Exception) { return }
        view.start { try { wm.removeView(view) } catch (_: Exception) {} }
    }

    private class Confetti(c: Context, val colours: List<Int>) : View(c) {
        private class P(var x: Float, var y: Float, val vx: Float, var vy: Float, var rot: Float, val vr: Float, val w: Float, val h: Float, val colour: Int)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var parts: List<P> = emptyList()
        private var t = 0f

        fun start(done: () -> Unit) {
            post {
                val w = width.toFloat().coerceAtLeast(1f); val d = resources.displayMetrics.density
                // Bursts from the top-right corner, where the score is.
                parts = List(36) {
                    P(w - 170 * d + Random.nextFloat() * 130 * d, 30 * d, -Random.nextFloat() * 3.5f * d + 0.8f * d, -Random.nextFloat() * 2.5f * d,
                        Random.nextFloat() * 360f, Random.nextFloat() * 8f - 4f, (3 + Random.nextFloat() * 2.5f) * d, (5 + Random.nextFloat() * 3) * d, colours[it % colours.size])
                }
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1700
                    addUpdateListener { a ->
                        t = a.animatedValue as Float
                        for (p in parts) { p.x += p.vx; p.vy += 0.16f * d; p.y += p.vy; p.rot += p.vr }
                        invalidate()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(an: android.animation.Animator) { done() } })
                    start()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val fade = ((1f - (t - 0.5f).coerceAtLeast(0f) / 0.5f) * 200).toInt()
            for (p in parts) {
                paint.color = p.colour; paint.alpha = fade
                canvas.save(); canvas.rotate(p.rot, p.x, p.y)
                canvas.drawRect(p.x - p.w / 2, p.y - p.h / 2, p.x + p.w / 2, p.y + p.h / 2, paint)
                canvas.restore()
            }
        }
    }
}
