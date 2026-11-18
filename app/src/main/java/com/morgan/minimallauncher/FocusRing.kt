package com.morgan.minimallauncher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Focus Highlight (Settings, per profile): a thick bright outline round whatever is selected - poster, button, row,
 * menu item - for anyone who finds the usual soft glow hard to see. Drawn in the view's overlay, so it sits on top
 * of everything in it and never replaces its own look.
 */
object FocusRing {
    private const val KEY = "focus_ring"

    /** "off", "white" or "yellow" for the profile in use. */
    fun mode(c: Context): String = Prefs.string(c, ProfileManager.scope(c, KEY)) ?: "off"
    fun setMode(c: Context, m: String) = Prefs.setString(c, ProfileManager.scope(c, KEY), m)

    private var ring: GradientDrawable? = null
    private var on: View? = null

    /** Follows focus in this window (activities and pop-ups). Safe to call more than once. */
    fun install(c: Context, window: Window) {
        val decor = window.decorView
        if (decor.getTag(R.id.focus_ring) == true) return
        decor.setTag(R.id.focus_ring, true)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, new -> show(c, new) })
    }

    private fun show(c: Context, v: View?) {
        on?.let { old -> ring?.let { old.overlay.remove(it) } }
        on = null
        val m = mode(c)
        if (m == "off" || v == null) return
        // Not round whole screens or the TV guide grid (that marks its own programme in white).
        val dm = v.resources.displayMetrics
        if (v.width > dm.widthPixels * 0.7f && v.height > dm.heightPixels * 0.5f) return
        val d = GradientDrawable().apply {
            cornerRadius = dp(c, 12f).toFloat()
            setColor(0)
            setStroke(dp(c, 4f), if (m == "yellow") 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt())
        }
        fun place() { d.setBounds(0, 0, v.width, v.height) }
        place()
        v.overlay.add(d)
        ring = d; on = v
        // Cards grow and open out when focused: keep the outline matching their size.
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(x: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (on !== x) { x.removeOnLayoutChangeListener(this); return }
                place(); x.invalidate()
            }
        })
    }
}
