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

    private var ring: android.graphics.drawable.Drawable? = null
    private var on: View? = null

    /** Follows focus in this window (activities and pop-ups). Safe to call more than once. */
    fun install(c: Context, window: Window) {
        val decor = window.decorView
        if (decor.getTag(R.id.focus_ring) == true) return
        decor.setTag(R.id.focus_ring, true)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, new -> show(c, new) })
    }

    /**
     * The tvOS "Increase Focus State" look: a crisp white rim that follows the item's own shape (its exact corner
     * rounding), with a fine dark line just inside it so it shows on white tiles as well as dark ones.
     */
    private class Rim(private val d: Float) : android.graphics.drawable.Drawable() {
        var radius = 0f
        var shape: android.graphics.Rect? = null
        private val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xF2FFFFFF.toInt()
        }
        private val dark = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0x73000000
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = shape ?: bounds
            val w = white.strokeWidth
            val o = android.graphics.RectF(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2)
            val r = (radius - w / 2).coerceAtLeast(0f)
            c.drawRoundRect(o, r, r, white)
            val k = w + dark.strokeWidth / 2
            val i = android.graphics.RectF(b.left + k, b.top + k, b.right - k, b.bottom - k)
            val ri = (radius - k).coerceAtLeast(0f)
            c.drawRoundRect(i, ri, ri, dark)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun tooBig(v: View): Boolean {
        val dm = v.resources.displayMetrics
        return v.width > dm.widthPixels * 0.7f && v.height > dm.heightPixels * 0.5f
    }

    private fun selectable(v: View): Boolean {
        if (v is androidx.media3.ui.PlayerView || v is android.view.SurfaceView || v is android.view.TextureView) return false
        if (v is androidx.recyclerview.widget.RecyclerView || v is android.widget.ScrollView || v is android.widget.HorizontalScrollView) return false
        if (v.javaClass.simpleName == "EpgView") return false
        // The video's own view (and its progress bar) inside the player.
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p is androidx.media3.ui.PlayerView && v.id != androidx.media3.ui.R.id.exo_progress && !v.isClickable) return false
            p = p.getParent()
        }
        return true
    }

    private fun show(c: Context, focused: View?) {
        on?.let { old -> ring?.let { old.overlay.remove(it) } }
        on = null
        if (mode(c) == "off" || focused == null) return
        // Only things you pick (tiles, posters, buttons, rows) - never the video, the TV guide grid (it marks its own
        // programme) or a whole scrolling list.
        if (!selectable(focused)) return
        // A card can name the part the rim belongs on (an episode card: its picture, so the rim isn't drawn
        // over the words underneath it).
        val v: View = (focused.getTag(R.id.focus_ring_target) as? View) ?: focused
        val dm = v.resources.displayMetrics
        if (tooBig(v)) return
        val rim = Rim(dm.density)
        fun place() {
            rim.setBounds(0, 0, v.width, v.height)
            // The item's own outline (its real corner rounding); a rounded shape of the app's usual size otherwise.
            val out = android.graphics.Outline()
            try { v.outlineProvider?.getOutline(v, out) } catch (_: Throwable) {}
            val r = android.graphics.Rect()
            val has = android.os.Build.VERSION.SDK_INT >= 24 && out.getRect(r) && !r.isEmpty
            rim.shape = if (has) r else null
            rim.radius = if (android.os.Build.VERSION.SDK_INT >= 24 && has) out.radius else (v.background as? GradientDrawable)?.cornerRadius ?: dp(c, 14f).toFloat()
        }
        place()
        v.overlay.add(rim)
        ring = rim; on = v
        // Cards grow and open out when focused: keep the rim matching their size.
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(x: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (on !== x) { x.removeOnLayoutChangeListener(this); return }
                // Focus can arrive before the first layout (size 0 then) - if it turns out to be the whole screen, no rim.
                if (tooBig(x)) { x.overlay.remove(rim); on = null; ring = null; x.removeOnLayoutChangeListener(this); return }
                place(); x.invalidate()
            }
        })
    }
}
