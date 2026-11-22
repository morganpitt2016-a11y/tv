package com.morgan.minimallauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.media.FaceDetector
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talk to the Characters: pause, and the people on screen talk to you.
 *
 * The paused frame becomes a still that slowly pushes in on a face (found with Android's face detector) while
 * the rest dims - a film close-up. Pick who to talk to (or say their name), speak, and they answer out loud in a
 * voice tuned to them, word by word in captions. Claude (Haiku 5.5 - fast, and a fraction of a penny a reply)
 * plays them knowing exactly where you are: the episode, the minute, and every line said so far (from the
 * subtitles), and never gives away what happens next.
 *
 * Voices are the TV's own Google voices shaped per character (pitch, pace) - never a copy of the actor's voice.
 */
class CharacterTalk(
    private val a: Activity,
    private val root: FrameLayout,
    private val surface: SurfaceView?,
    private val show: String,
    private val season: Int,
    private val episode: Int,
    private val episodeTitle: String?,
    private val plot: String?,
    private val positionMs: Long,
    private val transcript: List<String>,
    private val onClose: () -> Unit,
) {
    companion object {
        const val LISTEN = 7301
        private val main = Handler(Looper.getMainLooper())
    }

    class Person(val name: String, val gender: String, val age: String, val energy: String)

    private val c = a
    private val overlay = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; isClickable = true }
    private val still = ImageView(c).apply { scaleType = ImageView.ScaleType.MATRIX }
    private val vignette = View(c).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x00000000, 0x66000000, 0xE6000000.toInt())
            gradientRadius = 900f
        }
        alpha = 0f
    }
    private val caption = GlassUi.text(c, "", 26f, R.font.inter_semibold).apply {
        gravity = Gravity.CENTER; setShadowLayer(18f, 0f, 3f, 0xCC000000.toInt())
    }
    private val speaker = GlassUi.text(c, "", 13f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.18f; gravity = Gravity.CENTER }
    private val hint = GlassUi.text(c, "", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }
    private val chips = LinearLayout(c).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }

    private var frame: Bitmap? = null
    private var faces: List<PointF> = emptyList()
    private var cast: List<Person> = emptyList()
    private var talkingTo: Person? = null
    private val chats = HashMap<String, ClaudeChat>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()
    private var open = true

    fun start() {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        box.addView(speaker, LinearLayout.LayoutParams(-2, -2))
        box.addView(caption, LinearLayout.LayoutParams(dp(c, 760f), -2).apply { topMargin = dp(c, 6f) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(chips) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 22f) })
        box.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 12f) })
        overlay.addView(still, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(vignette, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x00000000)) },
            FrameLayout.LayoutParams(-1, dp(c, 300f), Gravity.BOTTOM))
        overlay.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(c, 40f) })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.animate().alpha(1f).setDuration(500).start()
        speaker.text = "TALK TO THE CHARACTERS"
        caption.text = "Who do you want to talk to?"
        hint.text = "Getting the cast…"

        tts = TextToSpeech(c.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) voices = tts?.voices.orEmpty().filter { it.locale.language == "en" && !it.features.contains("notInstalled") }
        }
        grabFrame()
        loadCast()
    }

    // ---------- The paused picture ----------

    private fun grabFrame() {
        val sv = surface ?: return
        if (sv.width <= 0 || sv.holder.surface?.isValid != true) return
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(sv, bmp, { r ->
                if (r != PixelCopy.SUCCESS) return@request
                frame = bmp
                still.setImageBitmap(bmp)
                fit(1f, null)
                Bg.run {
                    val found = findFaces(bmp)
                    main.post { faces = found }
                }
            }, main)
        } catch (_: Exception) {}
    }

    /** Where the faces are (frame pixels), biggest first. */
    private fun findFaces(bmp: Bitmap): List<PointF> = try {
        val small = Bitmap.createScaledBitmap(bmp, 640, 360, true).copy(Bitmap.Config.RGB_565, false)
        val out = arrayOfNulls<FaceDetector.Face>(6)
        val n = FaceDetector(640, 360, 6).findFaces(small, out)
        (0 until n).mapNotNull { out[it] }.sortedByDescending { it.eyesDistance() }.map { f ->
            val p = PointF(); f.getMidPoint(p); PointF(p.x * 2f, p.y * 2f + f.eyesDistance() * 0.6f)
        }
    } catch (_: Throwable) { emptyList() }

    /** Show the still at [zoom], centred on [on] (frame pixels) - or the whole frame. */
    private fun fit(zoom: Float, on: PointF?) {
        val bmp = frame ?: return
        val vw = overlay.width.takeIf { it > 0 } ?: root.width
        val vh = overlay.height.takeIf { it > 0 } ?: root.height
        val base = maxOf(vw / bmp.width.toFloat(), vh / bmp.height.toFloat())
        val s = base * zoom
        val cx = on?.x ?: (bmp.width / 2f); val cy = on?.y ?: (bmp.height / 2f)
        val tx = (vw / 2f - cx * s).coerceIn(vw - bmp.width * s, 0f)
        val ty = (vh / 2f - cy * s).coerceIn(vh - bmp.height * s, 0f)
        still.imageMatrix = Matrix().apply { setScale(s, s); postTranslate(tx, ty) }
    }

    /** A slow push-in on the face while they talk (and back out after). */
    private fun pushIn(on: Boolean) {
        val face = faces.firstOrNull()
        val from = if (on) 1f else 1.35f; val to = if (on) 1.35f else 1f
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (on) 2600 else 900
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { fit(it.animatedValue as Float, face) }
            start()
        }
        vignette.animate().alpha(if (on && face != null) 1f else 0f).setDuration(900).start()
    }

    // ---------- Who's in it ----------

    private fun castKey() = "cast_" + TextMatch.vodKey(show)

    private fun loadCast() {
        Prefs.string(c, castKey())?.let { parseCast(it)?.let { list -> showCast(list); return } }
        Bg.run {
            val chat = ClaudeChat(c, "You list the characters of TV shows and films as JSON. Reply with JSON only.", quick = true)
            val ask = "List the main characters (up to 8) of \"$show\"" +
                (if (season > 0) " that a viewer would know by season $season episode $episode, with no spoilers beyond that point" else "") +
                ". JSON array only: [{\"name\":\"first name or what they're called\",\"gender\":\"male|female\",\"age\":\"child|young|adult|older\",\"energy\":\"calm|normal|lively\"}]"
            val text = (chat.send(ask) as? ClaudeChat.Reply.Text)?.text.orEmpty()
            val json = text.substringAfter('[', "").let { if (it.isEmpty()) "" else "[" + it.substringBeforeLast(']') + "]" }
            val list = parseCast(json)
            main.post {
                if (!open) return@post
                if (list.isNullOrEmpty()) {
                    caption.text = if (ClaudeChat.getApiKey(c) == null) "Add your Anthropic API key in Settings › Claude first." else "Couldn't get the cast just now."
                    hint.text = "Back to return to the episode"
                } else { Prefs.setString(c, castKey(), json); showCast(list) }
            }
        }
    }

    private fun parseCast(json: String): List<Person>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Person(o.optString("name"), o.optString("gender", "male"), o.optString("age", "adult"), o.optString("energy", "normal"))
        }.filter { it.name.isNotBlank() }
    } catch (_: Exception) { null }

    private fun showCast(list: List<Person>) {
        cast = list
        chips.removeAllViews()
        for (p in list) {
            val chip = GlassUi.text(c, p.name, 16f, R.font.inter_semibold).apply {
                setPadding(dp(c, 22f), dp(c, 10f), dp(c, 22f), dp(c, 10f))
                isFocusable = true; isClickable = true
                fun paint(f: Boolean) {
                    background = GradientDrawable().apply { cornerRadius = dp(c, 22f).toFloat(); setColor(if (f) 0xFFFFFFFF.toInt() else 0x33FFFFFF) }
                    setTextColor(if (f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
                paint(false)
                setOnFocusChangeListener { v, f -> paint(f); Motion.spring(v, if (f) 1.08f else 1f) }
                setOnClickListener { listenTo(p) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 12f) })
        }
        hint.text = "Choose someone, then speak  ·  Back to return to the episode"
        chips.getChildAt(0)?.requestFocus()
    }

    // ---------- Talking ----------

    private fun listenTo(p: Person) {
        talkingTo = p
        speaker.text = p.name.uppercase()
        caption.text = "…"
        try {
            a.startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something to ${p.name}"), LISTEN)
        } catch (e: Exception) { caption.text = "Voice input isn't available on this TV." }
    }

    /** What you said (from the player's onActivityResult). */
    fun heard(said: String?) {
        val p = talkingTo ?: return
        if (said.isNullOrBlank()) { caption.text = "Didn't catch that - choose ${p.name} to try again."; return }
        hint.text = "You: “$said”"
        caption.text = "…"
        val chat = chats.getOrPut(p.name) { ClaudeChat(c, persona(p), quick = true) }
        Bg.run {
            val reply = chat.send(said)
            main.post {
                if (!open) return@post
                when (reply) {
                    is ClaudeChat.Reply.Text -> say(p, reply.text)
                    is ClaudeChat.Reply.Failed -> { caption.text = reply.message; hint.text = "Back to return to the episode" }
                }
            }
        }
    }

    private fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    private fun persona(p: Person): String {
        val where = if (season > 0) "season $season, episode $episode" + (episodeTitle?.let { " (\"$it\")" } ?: "") else "the film"
        val lines = transcript.takeLast(160).joinToString("\n")
        return buildString {
            append("You are ${p.name} from \"$show\". The viewer has paused $where at ${mmss(positionMs)}, and you - frozen on their screen - ")
            append("are talking to them directly, through the TV. Stay completely in character: ${p.name}'s voice, personality, opinions, ")
            append("catchphrases, relationships and knowledge, exactly as they are at this moment in the story. React to what just happened. ")
            append("You may playfully notice that they've paused you. NEVER reveal or hint at anything that happens after this moment - ")
            append("if asked about the future, deflect the way ${p.name} would. ")
            append("Speak as if out loud: one to three short sentences, natural spoken English, no stage directions, no asterisks, no emojis, ")
            append("no quotation marks around your words, no lists.\n\n")
            plot?.takeIf { it.isNotBlank() }?.let { append("What this episode is about (no spoilers past the paused moment): $it\n\n") }
            if (lines.isNotBlank()) append("Everything said on screen so far this episode, most recent last:\n$lines\n")
            else append("(No subtitles were available - rely on your knowledge of the show up to this point.)\n")
        }
    }

    /** Out loud, in a voice shaped to them, with the words appearing as they're said. */
    private fun say(p: Person, text: String) {
        speaker.text = p.name.uppercase()
        caption.text = ""
        pushIn(true)
        val t = tts
        if (t == null || !ttsReady) { caption.text = text; main.postDelayed({ pushIn(false) }, 4000); return }
        pickVoice(p)?.let { t.voice = it }
        t.setPitch(when { p.age == "child" -> 1.35f; p.gender == "female" -> 1.12f; p.age == "older" -> 0.82f; else -> 0.88f })
        t.setSpeechRate(when (p.energy) { "calm" -> 0.92f; "lively" -> 1.12f; else -> 1.0f })
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                main.post { caption.text = text.substring(0, end.coerceAtMost(text.length)) }
            }
            override fun onDone(id: String?) {
                main.post {
                    caption.text = text
                    pushIn(false)
                    hint.text = "Choose ${p.name} to reply  ·  Back to return to the episode"
                }
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { caption.text = text; pushIn(false) } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "line" + System.currentTimeMillis())
    }

    /** The same voice for the same character every time, chosen from the TV's English voices. */
    private fun pickVoice(p: Person): Voice? {
        if (voices.isEmpty()) return null
        // Prefer the higher-quality (network) voices, in the show's accent family when there's a choice.
        val good = voices.filter { it.quality >= Voice.QUALITY_HIGH }.ifEmpty { voices }
        return good[(p.name.lowercase().hashCode() and 0x7fffffff) % good.size]
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { close(); return true }
        return false
    }

    fun close() {
        if (!open) return
        open = false
        tts?.stop(); tts?.shutdown(); tts = null
        chats.values.forEach { it.close() }
        overlay.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(overlay); frame?.recycle() }.start()
        onClose()
    }
}
