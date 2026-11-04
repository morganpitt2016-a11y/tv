package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Learning Mode: a guided tour of the launcher. Each step spotlights one thing and explains it; "Try it" steps
 * let you press the real button, and the tour follows you into Watch and Settings. Press OK for the next step,
 * Left to go back, Menu to stop. Switch it on or off in Settings, and it starts by itself the first time.
 */
object Coach {
    private const val INFO = 0
    private const val TRY = 1

    private class Step(val screen: String, val target: String?, val title: String, val text: String, val mode: Int = INFO)

    private val steps = listOf(
        Step("home", null, "Welcome to Learning Mode",
            "I'll show you around, one feature at a time. Press OK to go to the next step, Left to go back, or Menu to stop whenever you like."),
        Step("home", "dock", "Your dock",
            "Your favourite apps live here. Move left and right to choose one and press OK to open it. The app grows out of its tile to fill the screen, and shrinks back into place when you return."),
        Step("home", "firstTile", "Hold OK for more",
            "Hold OK on any app to open its menu: add it to your favourites, move it along the dock, or take it off. Everyone who watches gets their own dock."),
        Step("home", "rails", "Every app",
            "Press Down from the dock to see all your apps, and apps you can download. The clock fades away so the apps get the whole screen."),
        Step("home", "watchTile", "Try it: open Watch",
            "Watch finds films and shows across all your apps and your Dodgy Box library. Press OK on Watch to open it.", TRY),
        Step("watch", "nav", "Your tabs",
            "Home, Films, Shows, Live TV, My List and Search. Move along them and the page changes by itself."),
        Step("watch", "rows", "Rows to browse",
            "Move down through the rows. Continue Watching sits at the top and picks up exactly where you stopped, just for you."),
        Step("watch", "nav", "Live TV",
            "Open the Live TV tab, then press OK on Open the TV Guide: categories on the left, a live picture, and a full programme grid. Press Menu on a channel to favourite or record it."),
        Step("watch", "nav", "Search and My List",
            "Search looks across your apps and Dodgy Box. My List keeps what you save, plus your recordings."),
        Step("watch", "nav", "Try it: go back",
            "Press Back on your remote to return to the home screen.", TRY),
        Step("home", "settingsTile", "Try it: open Settings",
            "Settings is where you choose your profile, wallpaper, widgets, screen savers and your Dodgy Box login. Press OK to open it.", TRY),
        Step("settings", "learnRow", "Learning Mode",
            "This switch turns the tour on or off. Turn it back on any time to see it again."),
        Step("settings", "list", "That's the tour",
            "Almost everything can be changed here. Press OK to finish, and have fun."),
    )

    private const val ON = "learning_on"
    private const val STEP = "learning_step"

    fun isOn(c: Context) = Prefs.bool(c, ON, false)

    fun start(c: Context) { Prefs.setBool(c, ON, true); Prefs.setInt(c, STEP, 0) }
    fun stop(c: Context) { Prefs.setBool(c, ON, false); Prefs.setInt(c, STEP, 0); hide() }

    /** The tour begins by itself the first time the launcher is used. */
    fun firstRun(c: Context) {
        if (Prefs.bool(c, "learning_seen", false)) return
        Prefs.setBool(c, "learning_seen", true)
        start(c)
    }

    private var view: TourView? = null
    private var owner: Activity? = null

    private fun index(c: Context) = Prefs.int(c, STEP, 0).coerceIn(0, steps.size - 1)

    /** Call from an Activity's onResume (after its screen is built). [find] returns the view for a target name. */
    fun resume(activity: Activity, screen: String, find: (String) -> View?) {
        hide()
        if (!isOn(activity)) return
        var i = index(activity)
        // Following the person: a "Try it" step leads to the next step's screen.
        if (steps[i].screen != screen) {
            val next = steps.getOrNull(i + 1)
            if (steps[i].mode == TRY && next?.screen == screen) { i++; Prefs.setInt(activity, STEP, i) }
            else return
        }
        val step = steps[i]
        activity.window.decorView.postDelayed({
            if (activity.isDestroyed || !isOn(activity) || index(activity) != i) return@postDelayed
            show(activity, i, step, step.target?.let(find), find)
        }, 650)
    }

    private fun show(activity: Activity, i: Int, step: Step, target: View?, find: (String) -> View?) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        hide()
        val c = activity
        val tv = TourView(c)
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 22f).toFloat(); setColor(0xE61C1C1F.toInt()) }, GlassUi.panel(c, 22f, 0x24)))
            setPadding(dp(c, 24f), dp(c, 20f), dp(c, 24f), dp(c, 18f))
        }
        card.addView(GlassUi.text(c, "LEARNING MODE  ·  ${i + 1} OF ${steps.size}", 10.5f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.1f })
        card.addView(GlassUi.text(c, step.title, 21f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
        card.addView(GlassUi.text(c, step.text, 14f, R.font.inter_regular, 0xE6FFFFFF.toInt()).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 8f) })
        val hint = if (step.mode == TRY) "Press the button on your remote as asked  ·  Menu to stop" else if (i == steps.size - 1) "OK to finish" else "OK next  ·  Left back  ·  Menu to stop"
        card.addView(GlassUi.text(c, hint, 11.5f, R.font.inter_medium, 0x80FFFFFF.toInt()), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 14f) })
        tv.addView(card, FrameLayout.LayoutParams(dp(c, 430f), -2))
        content.addView(tv, ViewGroup.LayoutParams(-1, -1))
        tv.elevation = 200f
        view = tv; owner = activity
        tv.post {
            val cl = IntArray(2); content.getLocationOnScreen(cl)
            val r = android.graphics.Rect()
            val rect = if (target != null && target.isShown && target.getGlobalVisibleRect(r)) RectF(r).apply { offset(-cl[0].toFloat(), -cl[1].toFloat()); inset(-dp(c, 10f).toFloat(), -dp(c, 10f).toFloat()) } else null
            tv.hole = rect
            val w = content.width; val h = content.height
            val cw = card.width; val ch = card.height
            val lp = card.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            if (rect == null) { lp.leftMargin = (w - cw) / 2; lp.topMargin = (h - ch) / 2 }
            else {
                lp.leftMargin = (rect.centerX() - cw / 2).toInt().coerceIn(dp(c, 24f), w - cw - dp(c, 24f))
                lp.topMargin = if (rect.bottom + dp(c, 16f) + ch < h - dp(c, 16f)) (rect.bottom + dp(c, 16f)).toInt() else maxOf(dp(c, 16f), (rect.top - ch - dp(c, 16f)).toInt())
            }
            card.layoutParams = lp
            card.alpha = 0f; card.animate().alpha(1f).setDuration(260).start()
            tv.invalidate()
        }
    }

    fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null; owner = null
    }

    private fun go(activity: Activity, to: Int, find: (String) -> View?) {
        if (to >= steps.size) { stop(activity); return }
        val t = to.coerceAtLeast(0)
        Prefs.setInt(activity, STEP, t)
        val screen = activity.javaClass.simpleName
        hide()
        // Same screen: show it now. Otherwise the next screen picks it up when it opens.
        val next = steps[t]
        val here = when (activity) { is MainActivity -> "home"; is WatchActivity -> "watch"; is SettingsActivity -> "settings"; else -> screen }
        if (next.screen == here) resume(activity, here, find)
    }

    /** Key presses while a tour step is showing. Returns true if the tour used the key. */
    fun onKey(activity: Activity, event: KeyEvent, find: (String) -> View?): Boolean {
        if (view == null || owner !== activity || !isOn(activity)) return false
        val i = index(activity)
        val step = steps[i]
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_DOWN) { stop(activity); GlassUi.toast(activity, "Learning Mode is off. Turn it on again in Settings.") }; return true }
        if (step.mode == TRY) return false            // let the real button work
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT -> go(activity, i + 1, find)
            KeyEvent.KEYCODE_DPAD_LEFT -> go(activity, i - 1, find)
            KeyEvent.KEYCODE_BACK -> { stop(activity); GlassUi.toast(activity, "Learning Mode is off. Turn it on again in Settings.") }
            else -> return true                         // up / down are ignored while a step is showing
        }
        return true
    }

    private class TourView(c: Context) : FrameLayout(c) {
        var hole: RectF? = null
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = dp(c, 2f).toFloat() }
        private val radius = dp(c, 20f).toFloat()
        private var pulse = 0f
        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_HARDWARE, null)
            isClickable = false; isFocusable = false
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawColor(0xB8000000.toInt())
            hole?.let { canvas.drawRoundRect(it, radius, radius, clear) }
            canvas.restoreToCount(sc)
            hole?.let { ring.alpha = (110 + 145 * pulse).toInt(); canvas.drawRoundRect(it, radius, radius, ring) }
        }
    }
}
