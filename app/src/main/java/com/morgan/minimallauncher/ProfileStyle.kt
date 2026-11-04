package com.morgan.minimallauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.min

/**
 * How each person likes their TV: picture, highlight colour, and where that
 * colour shows up. Stored per person (and per account, so "Person 1" in two
 * homes never clash).
 */
object ProfileStyle {

    data class Style(
        /** "" = initial letter, "e:🦊" = a character, "photo" = a picture from the iPhone. */
        val avatar: String,
        /** Index into Accounts.GRADIENTS for the picture's background, or -1 for automatic. */
        val colour: Int,
        /** Highlight colour, or 0 for the standard white. */
        val accent: Int,
        val clockAccent: Boolean,
        val iconAccent: Boolean,
        /** The pixel cavapoo who lives on the home screen. */
        val dog: Boolean,
    )

    /** Apple-style system colours. 0 is the standard (white) look. */
    val ACCENTS: List<Pair<String, Int>> = listOf(
        "White" to 0,
        "Blue" to 0xFF0A84FF.toInt(), "Purple" to 0xFFBF5AF2.toInt(), "Pink" to 0xFFFF375F.toInt(),
        "Red" to 0xFFFF453A.toInt(), "Orange" to 0xFFFF9F0A.toInt(), "Yellow" to 0xFFFFD60A.toInt(),
        "Green" to 0xFF30D158.toInt(), "Mint" to 0xFF63E6E2.toInt(), "Teal" to 0xFF40C8E0.toInt(),
    )

    private fun k(c: Context, name: String, field: String) = "ps_${Accounts.scoped(c, name)}_$field"

    fun of(c: Context, name: String = ProfileManager.getActiveProfile(c)) = Style(
        Prefs.string(c, k(c, name, "av")).orEmpty(),
        Prefs.int(c, k(c, name, "col"), -1),
        Prefs.int(c, k(c, name, "acc"), 0),
        Prefs.bool(c, k(c, name, "clk"), false),
        Prefs.bool(c, k(c, name, "ico"), false),
        Prefs.bool(c, k(c, name, "dog"), true),
    )

    fun setAvatar(c: Context, name: String, v: String) = Prefs.setString(c, k(c, name, "av"), v)
    fun setColour(c: Context, name: String, v: Int) = Prefs.setInt(c, k(c, name, "col"), v)
    fun setAccent(c: Context, name: String, v: Int) = Prefs.setInt(c, k(c, name, "acc"), v)
    fun setClock(c: Context, name: String, v: Boolean) = Prefs.setBool(c, k(c, name, "clk"), v)
    fun setIcons(c: Context, name: String, v: Boolean) = Prefs.setBool(c, k(c, name, "ico"), v)
    fun setDog(c: Context, name: String, v: Boolean) = Prefs.setBool(c, k(c, name, "dog"), v)

    /** Keep a person's look when they're renamed. */
    fun rename(c: Context, old: String, new: String) {
        val s = of(c, old)
        setAvatar(c, new, s.avatar); setColour(c, new, s.colour); setAccent(c, new, s.accent)
        setClock(c, new, s.clockAccent); setIcons(c, new, s.iconAccent); setDog(c, new, s.dog)
        setAvatar(c, old, ""); setColour(c, old, -1); setAccent(c, old, 0); setClock(c, old, false); setIcons(c, old, false); setDog(c, old, true)
        try { Avatars.file(c, old).renameTo(Avatars.file(c, new)) } catch (e: Exception) {}
    }

    /** The highlight colour for whoever is watching (0 = standard). */
    fun accent(c: Context) = of(c).accent

    /** A coloured glow under the focused tile (the grow-and-lift itself is Motion.focus). */
    fun glow(v: View, focused: Boolean) {
        val acc = accent(v.context)
        if (acc == 0 || Build.VERSION.SDK_INT < 28) return
        // No shadows: on translucent glass they show through as a ghost edge.
        v.outlineSpotShadowColor = 0x00000000
        v.outlineAmbientShadowColor = 0x00000000
    }

    /** The tile highlight overlay plus a thin ring in the highlight colour when focused. */
    class Ring(c: Context, radiusDp: Float) {
        private val d = c.resources.displayMetrics.density
        private val ring = GradientDrawable().apply { cornerRadius = radiusDp * d; setColor(Color.TRANSPARENT) }
        private val light: Drawable = ContextCompat.getDrawable(c, R.drawable.tile_light)!!
        val drawable: Drawable = LayerDrawable(arrayOf(light, ring))
        private val width = (2.5f * d).toInt().coerceAtLeast(2)

        fun focus(v: View, focused: Boolean) {
            val acc = accent(v.context)
            ring.setStroke(width, if (focused && acc != 0) acc else Color.TRANSPARENT)
            glow(v, focused)
        }
    }
}

/** Profile pictures: a letter, a character, or a photo from the iPhone, drawn as a circle. */
object Avatars {

    val CHARACTERS = listOf("🦊", "🐘", "🐼", "🐸", "🐙", "🦁", "🐵", "🐶", "🐱", "🦄", "🐲",
        "🤖", "👽", "👻", "🐧", "🐨", "🐯", "🦉", "🐰", "🐻", "🦋")

    fun file(c: Context, name: String): File =
        File(File(c.filesDir, "avatars").apply { mkdirs() }, Accounts.scoped(c, name).replace(Regex("[^A-Za-z0-9]"), "_") + ".jpg")

    fun hasPhoto(c: Context, name: String) = file(c, name).let { it.exists() && it.length() > 0 }

    fun gradient(c: Context, name: String): IntArray {
        val st = ProfileStyle.of(c, name)
        val idx = if (st.colour >= 0) st.colour
        else ProfileManager.getProfiles(c).indexOf(name).coerceAtLeast(0) + (Accounts.active(c)?.colour ?: 0)
        return Accounts.GRADIENTS[idx % Accounts.GRADIENTS.size]
    }

    fun render(c: Context, name: String, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val st = ProfileStyle.of(c, name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = px / 2f

        if (st.avatar == "photo" && hasPhoto(c, name)) {
            val src = BitmapFactory.decodeFile(file(c, name).path)
            if (src != null) {
                val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val m = Matrix(); m.setScale(px / src.width.toFloat(), px / src.height.toFloat())
                shader.setLocalMatrix(m)
                paint.shader = shader
                cv.drawCircle(half, half, half, paint)
                return bmp
            }
        }

        val g = gradient(c, name)
        paint.shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), g[0], g[1], Shader.TileMode.CLAMP)
        cv.drawCircle(half, half, half, paint)
        paint.shader = null

        val emoji = st.avatar.startsWith("e:")
        val label = if (emoji) st.avatar.removePrefix("e:") else name.trim().take(1).uppercase()
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        if (!emoji) paint.typeface = GlassUi.font(c, R.font.inter_semibold)
        paint.textSize = px * (if (emoji) 0.52f else 0.46f)
        cv.drawText(label, half, half - (paint.descent() + paint.ascent()) / 2f, paint)
        return bmp
    }

    /** Saves a picture sent from the iPhone: cropped square, 512px. */
    fun savePhoto(c: Context, name: String, bytes: ByteArray): Boolean {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return false
            var sample = 1
            while (min(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val side = min(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(square, 512, 512, true)
            file(c, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ProfileStyle.setAvatar(c, name, "photo")
            return true
        } catch (e: Exception) { return false }
    }
}
