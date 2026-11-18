package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Shared look for the Apple-TV-style screens: a blurred copy of the
 * current wallpaper behind everything, "liquid glass" panels (translucent
 * fill, bright top edge), and tvOS-style rows that turn white with dark
 * text when focused with the remote.
 */
object GlassUi {

    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF1C1C1E.toInt()           // text on a focused (white) row
    const val SECONDARY = 0x99FFFFFF.toInt()      // values, footnotes
    const val SECONDARY_ON_WHITE = 0xFF6E6E73.toInt()
    const val RED = 0xFFFF453A.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun font(c: Context, res: Int): Typeface? = ResourcesCompat.getFont(c, res)

    private var backdropFor: Any? = null
    private var backdropBmp: android.graphics.Bitmap? = null

    /** The small copy of the current wallpaper used behind glass screens, decoded once per photo. */
    private fun backdropBitmap(c: Context): android.graphics.Bitmap? {
        val p = WallpaperRotator.current
        if (p == backdropFor) return backdropBmp
        // Decoding small and scaling up already softens it; RenderEffect (Android 12+) adds a proper blur on top.
        val small = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = try {
            when (p) {
                is WallpaperLibrary.Photo.Bundled -> BitmapFactory.decodeResource(c.resources, p.res, small)
                is WallpaperLibrary.Photo.Local -> WallpaperLibrary.decodeFile(p.file, 240)
            }
        } catch (e: Exception) { null }
        backdropFor = p; backdropBmp = bmp
        return bmp
    }

    /** Full-screen blurred, darkened wallpaper behind a screen's content. */
    fun installBackdrop(root: ViewGroup) {
        val c = root.context
        val image = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(backdropBitmap(c))
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(dp(c, 18f).toFloat(), dp(c, 18f).toFloat(), Shader.TileMode.CLAMP))
            }
            scaleX = 1.1f
            scaleY = 1.1f
        }
        val dim = View(c).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C000000.toInt(), 0x66000000, 0x99000000.toInt())
            )
        }
        root.addView(image, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(dim, 1, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * tvOS 26 "Liquid Glass": a frosted fill, a specular rim that catches the light along the top and fades
     * down the sides, and a soft sheen across the top - drawn in one pass, so it stays smooth on TV hardware.
     */
    fun panel(c: Context, radiusDp: Float, fillAlpha: Int = 0x24): Drawable =
        // Reduce Transparency: solid dark panels instead of see-through glass (easier to read over busy pictures).
        if (solidPanels(c)) GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp).toFloat(); setColor(0xF2242428.toInt()); setStroke(dp(c, 1f), 0x40FFFFFF)
        } else LiquidGlass(dp(c, radiusDp).toFloat(), fillAlpha, dp(c, 1f).toFloat() * 1.2f)

    class LiquidGlass(private val r: Float, private val fillAlpha: Int, private val rim: Float) : Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val sheen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val box = android.graphics.RectF()
        private var alphaMul = 255

        override fun onBoundsChange(b: android.graphics.Rect) {
            val h = b.height().toFloat().coerceAtLeast(1f)
            val top = (minOf(255, fillAlpha + 0x14) shl 24) or 0xFFFFFF
            val bottom = (fillAlpha shl 24) or 0xFFFFFF
            fill.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            edge.strokeWidth = rim
            edge.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * 0.35f, b.bottom.toFloat(),
                intArrayOf(0x8CFFFFFF.toInt(), 0x33FFFFFF, 0x14FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            val sh = minOf(h * 0.5f, maxOf(r * 1.6f, h * 0.18f))
            sheen.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.top + sh, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            box.set(b.left + rim / 2, b.top + rim / 2, b.right - rim / 2, b.bottom - rim / 2)
            listOf(fill, sheen, edge).forEach { it.alpha = alphaMul }
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawRoundRect(box, r, r, sheen)
            canvas.drawRoundRect(box, r, r, edge)
        }

        override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, r) }
        override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Row background: invisible normally, solid white when focused (tvOS style). */
    fun rowBackground(c: Context, radiusDp: Float = 14f, idleColor: Int = 0x00FFFFFF): Drawable {
        val r = dp(c, radiusDp).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply { cornerRadius = r; setColor(0xF7FFFFFF.toInt()) })
            addState(intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply { cornerRadius = r; setColor(0xE6FFFFFF.toInt()) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = r; setColor(idleColor) })
        }
    }

    fun primaryText(onFocus: Int = INK, idle: Int = WHITE) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(onFocus, onFocus, idle)
    )

    fun secondaryText() = primaryText(SECONDARY_ON_WHITE, SECONDARY)

    // ---------- Accessibility (Settings, per profile - like Apple TV's) ----------

    fun boldText(c: Context) = Prefs.bool(c, ProfileManager.scope(c, "a11y_bold"), false)
    fun largerText(c: Context) = Prefs.bool(c, ProfileManager.scope(c, "a11y_large"), false)
    fun solidPanels(c: Context) = Prefs.bool(c, ProfileManager.scope(c, "a11y_solid"), false)

    fun text(c: Context, value: CharSequence, sizeDp: Float, fontRes: Int, color: Int = WHITE) = TextView(c).apply {
        text = value
        // Bold Text: each weight one step heavier (the heaviest gets a touch more on top).
        val bold = boldText(c)
        val res = if (!bold) fontRes else when (fontRes) {
            R.font.inter_regular -> R.font.inter_medium
            R.font.inter_medium -> R.font.inter_semibold
            else -> fontRes
        }
        typeface = font(c, res)
        if (bold && fontRes == R.font.inter_semibold) paint.isFakeBoldText = true
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * if (largerText(c)) 1.15f else 1f)
        setTextColor(color)
        includeFontPadding = false
    }

    /** Text that follows its parent row's focus (white normally, dark on the white row). */
    fun rowText(c: Context, value: CharSequence, sizeDp: Float, fontRes: Int, colors: ColorStateList) =
        text(c, value, sizeDp, fontRes).apply {
            setTextColor(colors)
            isDuplicateParentStateEnabled = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * Gentle grow on focus. No elevation: a shadow under a white row on
     * glass shows up as a dark band, especially while the list scrolls.
     */
    fun focusLift(view: View, scale: Float = 1.03f) {
        view.setOnFocusChangeListener { v, has ->
            // Rows on glass: grow only (a shadow under a white row reads as a dark band).
            Motion.spring(v, if (has) scale else 1f)
        }
    }

    /** A short glass banner at the top of the screen, instead of a grey toast. */
    fun toast(activity: Activity, message: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val c = activity
        val banner = text(c, message, 14f, R.font.inter_medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 22f), dp(c, 12f), dp(c, 22f), dp(c, 12f))
            background = LayerDrawable(arrayOf(ColorDrawable(0), panel(c, 22f, 0x40)))
            alpha = 0f
            translationY = -dp(c, 20f).toFloat()
            elevation = dp(c, 24f).toFloat()
        }
        content.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(c, 28f)
        })
        banner.animate().alpha(1f).translationY(0f).setDuration(220).withEndAction {
            banner.animate().alpha(0f).setStartDelay(2600).setDuration(300).withEndAction {
                content.removeView(banner)
            }.start()
        }.start()
    }

    /**
     * Glass transition: the screen behind a panel or dialog softly blurs
     * and dims as it opens, and clears again when it closes (Android 12+;
     * just dims on older versions).
     */
    fun blurBehind(activity: Activity, on: Boolean) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        // Applied once (re-blurring the whole screen every animation frame drops frames
        // on TV hardware); the dialog's own fade in/out provides the transition.
        if (Build.VERSION.SDK_INT >= 31) {
            val r = dp(activity, 14f).toFloat()
            content.setRenderEffect(if (on) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
    }

    /**
     * Focus shadow: a soft, neutral drop shadow under the lifted item
     * (tvOS style - matte, no glow).
     */
    fun spotlight(view: View, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 28) {
            // Shadows are off everywhere: they bleed through translucent glass as a pale offset shape.
            view.outlineSpotShadowColor = 0x00000000
            view.outlineAmbientShadowColor = 0x00000000
        }
    }

    /** Roboto Flex with custom weight/width - SF-like display numerals. */
    fun flex(view: TextView, weight: Int, width: Int = 90) {
        view.typeface = font(view.context, R.font.roboto_flex)
        view.setFontVariationSettings("'wght' $weight, 'wdth' $width, 'opsz' 72")
    }
}
