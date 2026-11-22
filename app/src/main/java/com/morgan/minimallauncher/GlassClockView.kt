package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.TextView

/**
 * The home screen clock in Liquid Glass, like the iOS / tvOS 26 lock screen: each numeral is a pane of frosted glass
 * that lets the photo behind glow through it (a blurred, brightened copy of the wallpaper fills the digits), with a
 * bright rim catching the light along its top edge and a soft shadow lifting it off the picture.
 *
 * Still a TextView, so text, typeface, font axes, colour and fades all work as before; only the drawing is custom.
 * Without a wallpaper yet (or in cinema mode) it draws as plain text in its own colour.
 */
class GlassClockView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : TextView(c, attrs) {

    private var glass: Bitmap? = null
    private var glassShader: BitmapShader? = null
    private var stageW = 0
    private var stageH = 0
    private var shadowAlpha = 70

    /** Plain text instead of glass (cinema mode's thin warm clock). */
    var plain = false
        set(v) { field = v; invalidate() }

    /** The wallpaper now showing, and the size of the stage it fills (the clock sits on the same stage). */
    fun setBackdrop(src: Bitmap?, stageWidth: Int, stageHeight: Int) {
        stageW = stageWidth; stageH = stageHeight
        glass = src?.let { frost(it) }
        glassShader = glass?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    /** How strong the shadow under the numerals is (stronger on bright skies). */
    fun setShadowStrength(alpha: Int) { shadowAlpha = alpha.coerceIn(0, 255); invalidate() }

    /** A small, very soft, brighter and richer copy of the photo: what you'd see through frosted glass. */
    private fun frost(src: Bitmap): Bitmap {
        val w = 160
        val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
        // Shrinking hard and growing back with filtering is a cheap, smooth blur.
        val tiny = Bitmap.createScaledBitmap(src, 24, (24 * h / w).coerceAtLeast(1), true)
        val soft = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.7f) }
        // Lift it: glass glows rather than darkens.
        cm.postConcat(ColorMatrix(floatArrayOf(
            1.15f, 0f, 0f, 0f, 70f,
            0f, 1.15f, 0f, 0f, 70f,
            0f, 0f, 1.15f, 0f, 74f,
            0f, 0f, 0f, 1f, 0f,
        )))
        Canvas(out).drawBitmap(soft, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        soft.recycle()
        return out
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout
        val text = text?.toString().orEmpty()
        if (l == null || text.isEmpty()) return super.onDraw(canvas)
        val p = paint
        val x = totalPaddingLeft + l.getLineLeft(0)
        val y = totalPaddingTop + l.getLineBaseline(0).toFloat()
        val keepColor = p.color; val keepShader = p.shader; val keepStyle = p.style
        val keepMask = p.maskFilter; val keepStroke = p.strokeWidth
        val d = resources.displayMetrics.density
        val textAlpha = Color.alpha(currentTextColor)

        // 1. Shadow, lifting the glass off the photo: a shadow layer on the first fill (follows the numerals' shapes -
        // a blur mask filter here was drawn by the TV as a faint box around the whole clock).
        p.shader = null; p.style = Paint.Style.FILL
        val shadow = Color.argb(shadowAlpha * textAlpha / 255, 0, 0, 0)

        val shader = glassShader
        if (plain || shader == null || stageW == 0) {
            p.color = currentTextColor
            p.setShadowLayer(16f * d, 0f, 5f * d, shadow)
            canvas.drawText(text, x, y, p)
            p.clearShadowLayer()
        } else {
            // 2. The frosted photo inside each numeral, lined up with the real photo behind it.
            val g = glass!!
            val scale = maxOf(stageW.toFloat() / g.width, stageH.toFloat() / g.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate((stageW - g.width * scale) / 2f - left - translationX, (stageH - g.height * scale) / 2f - top - translationY)
            }
            shader.setLocalMatrix(m)
            p.shader = shader
            p.color = Color.argb(textAlpha, 255, 255, 255)
            p.setShadowLayer(16f * d, 0f, 5f * d, shadow)
            canvas.drawText(text, x, y, p)
            p.clearShadowLayer()
            // A milky veil so the numerals always read clearly, whatever the photo.
            p.shader = null
            val tint = currentTextColor   // the profile's clock colour tints the glass
            // Strong enough to read over a bright sky (the glass still shows the photo through it).
            p.color = Color.argb(textAlpha * 135 / 255, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawText(text, x, y, p)

            // 3. The rim: bright along the top where the light catches it, fading down the sides, a little glow at the foot.
            val glyphTop = y + p.ascent() * 0.72f
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.shader = LinearGradient(0f, glyphTop, 0f, y, intArrayOf(
                Color.argb(textAlpha * 235 / 255, 255, 255, 255),
                Color.argb(textAlpha * 40 / 255, 255, 255, 255),
                Color.argb(textAlpha * 110 / 255, 255, 255, 255),
            ), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawText(text, x, y, p)
        }
        p.color = keepColor; p.shader = keepShader; p.style = keepStyle; p.maskFilter = keepMask; p.strokeWidth = keepStroke
    }
}
