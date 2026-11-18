package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import org.json.JSONObject

/**
 * The picture and sound a title comes in, shown as small outlined badges after the details line, as on Apple TV:
 * [4K] [HDR10] [Dolby Atmos] [5.1]. Known from the server's own file info before you play, and from what the player
 * actually got once you have (that one wins - it's exact).
 */
object AvBadges {
    data class Av(val height: Int, val hdr: String?, val audio: String?, val channels: Int, val atmos: Boolean)

    private fun key(name: String) = "av_" + TiviTuner.vodKey(name)

    fun get(c: Context, name: String): Av? = Prefs.string(c, key(name))?.split("|")?.takeIf { it.size >= 5 }?.let {
        Av(it[0].toIntOrNull() ?: 0, it[1].ifEmpty { null }, it[2].ifEmpty { null }, it[3].toIntOrNull() ?: 0, it[4] == "1")
    }

    fun save(c: Context, name: String, av: Av) {
        if (name.isBlank()) return
        Prefs.setString(c, key(name), listOf(av.height, av.hdr.orEmpty(), av.audio.orEmpty(), av.channels, if (av.atmos) 1 else 0).joinToString("|"))
    }

    /** From the server's file info: {"video": {"height", "color_transfer", "codec_name"}, "audio": {"codec_name", "channels", "profile"}}. */
    fun parse(info: JSONObject?): Av? {
        val v = info?.optJSONObject("video"); val a = info?.optJSONObject("audio")
        if (v == null && a == null) return null
        val transfer = v?.optString("color_transfer").orEmpty()
        val vcodec = v?.optString("codec_name").orEmpty()
        val hdr = when {
            vcodec.contains("dvh", true) || v?.optJSONArray("side_data_list")?.toString()?.contains("DOVI", true) == true -> "Dolby Vision"
            transfer.contains("2084") -> "HDR10"
            transfer.contains("b67", true) -> "HLG"
            else -> null
        }
        val profile = a?.optString("profile").orEmpty()
        return Av(v?.optInt("height") ?: 0, hdr, a?.optString("codec_name")?.lowercase()?.ifEmpty { null }, a?.optInt("channels") ?: 0, profile.contains("atmos", true) || profile.contains("joc", true))
    }

    /** From what the player chose: the video and audio formats. */
    fun fromPlayer(video: androidx.media3.common.Format?, audio: androidx.media3.common.Format?): Av? {
        if (video == null && audio == null) return null
        val mime = audio?.sampleMimeType.orEmpty()
        val hdr = when {
            video?.sampleMimeType?.contains("dolby-vision") == true -> "Dolby Vision"
            video?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
            video?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val codec = when {
            mime.contains("eac3") -> "eac3"; mime.contains("ac3") -> "ac3"; mime.contains("dts") -> "dts"
            mime.contains("truehd") -> "truehd"; mime.contains("mp4a") || mime.contains("aac") -> "aac"; else -> mime.substringAfter("/").ifEmpty { null }
        }
        return Av(video?.height?.takeIf { it > 0 } ?: 0, hdr, codec, audio?.channelCount?.takeIf { it > 0 } ?: 0, mime.contains("joc"))
    }

    fun labels(av: Av): List<String> = listOfNotNull(
        when { av.height >= 1800 -> "4K"; av.height >= 700 -> "HD"; av.height > 0 -> "SD"; else -> null },
        av.hdr,
        when {
            av.atmos -> "Dolby Atmos"
            av.audio == "eac3" -> "Dolby Digital+"
            av.audio == "ac3" -> "Dolby Digital"
            av.audio == "truehd" -> "Dolby TrueHD"
            av.audio?.startsWith("dts") == true || av.audio?.startsWith("dca") == true -> "DTS"
            else -> null
        },
        when { av.channels >= 8 -> "7.1"; av.channels >= 6 -> "5.1"; av.channels in 1..2 -> "Stereo"; else -> null },
    )

    /** The details line with the badges after it. */
    fun withBadges(c: Context, line: CharSequence, name: String?): CharSequence {
        val av = name?.let { get(c, it) } ?: return line
        val ls = labels(av)
        if (ls.isEmpty()) return line
        val b = SpannableStringBuilder(line)
        if (b.isNotEmpty()) b.append("   ")
        val d = c.resources.displayMetrics.density
        ls.forEachIndexed { i, l ->
            if (i > 0) b.append(" ")
            val start = b.length
            b.append(l)
            b.setSpan(Box(d), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    /** Looks the title up on the server once (then not again for a week) and calls back if badges arrived. */
    fun fetch(a: Activity, name: String, year: Int, show: Boolean, then: () -> Unit) {
        val c = a.applicationContext
        if (get(c, name) != null || !Xtream.isSetUp(c)) return
        val tried = "avtry_" + TiviTuner.vodKey(name)
        if (System.currentTimeMillis() - Prefs.string(c, tried).orEmpty().toLongOrNull().let { it ?: 0L } < 7 * 86_400_000L) return
        Prefs.setString(c, tried, System.currentTimeMillis().toString())
        Thread {
            val av = try { Xtream.avInfo(c, name, year, show) } catch (_: Throwable) { null }
            if (av != null && labels(av).isNotEmpty()) { save(c, name, av); a.runOnUiThread { if (!a.isFinishing) then() } }
        }.apply { isDaemon = true; start() }
    }

    /** A small rounded outline round the text, like Apple TV's 4K / Dolby Atmos badges. */
    private class Box(private val d: Float) : ReplacementSpan() {
        private val padX = 5f * d
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val old = paint.textSize
            paint.textSize = old * 0.78f
            val w = paint.measureText(text, start, end)
            paint.textSize = old
            return (w + padX * 2).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val old = paint.textSize; val style = paint.style; val sw = paint.strokeWidth; val col = paint.color
            paint.textSize = old * 0.78f
            val w = paint.measureText(text, start, end)
            val fm = paint.fontMetrics
            val h = (fm.descent - fm.ascent) + 3f * d
            val cy = y + (paint.fontMetrics.ascent / 0.78f + paint.fontMetrics.descent / 0.78f) / 2f
            val r = RectF(x + 0.5f * d, cy - h / 2f, x + w + padX * 2 - 0.5f * d, cy + h / 2f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * d
            paint.color = (col and 0x00FFFFFF) or 0xB3000000.toInt()
            canvas.drawRoundRect(r, 3.5f * d, 3.5f * d, paint)
            paint.style = Paint.Style.FILL; paint.color = col
            canvas.drawText(text, start, end, x + padX, cy - (fm.ascent + fm.descent) / 2f, paint)
            paint.textSize = old; paint.style = style; paint.strokeWidth = sw; paint.color = col
        }
    }
}
