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

    /** Full-screen blurred, darkened wallpaper behind a screen's content. */
    fun installBackdrop(root: ViewGroup) {
        val c = root.context
        val image = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Decoding small and scaling up already softens it; RenderEffect
            // (Android 12+) adds a proper blur on top.
            val bmp = BitmapFactory.decodeResource(
                c.resources, WallpaperRotator.currentRes,
                BitmapFactory.Options().apply { inSampleSize = 8 }
            )
            setImageBitmap(bmp)
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

    /** Translucent glass panel with a brighter top edge, like tvOS "Liquid Glass". */
    fun panel(c: Context, radiusDp: Float, fillAlpha: Int = 0x24): Drawable {
        val r = dp(c, radiusDp).toFloat()
        val body = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf((fillAlpha + 0x10 shl 24) or 0xFFFFFF, (fillAlpha shl 24) or 0xFFFFFF)
        ).apply {
            cornerRadius = r
            setStroke(dp(c, 1f), 0x33FFFFFF)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x24FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(body, sheen)).apply {
            setLayerInset(1, 0, 0, 0, 0)
            setLayerHeight(1, dp(c, 28f))
        }
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

    fun text(c: Context, value: CharSequence, sizeDp: Float, fontRes: Int, color: Int = WHITE) = TextView(c).apply {
        text = value
        typeface = font(c, fontRes)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
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
            v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .setDuration(200).start()
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
}
