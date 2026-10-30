package com.morgan.minimallauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.morgan.minimallauncher.GlassUi.dp
import java.util.concurrent.Executors

/**
 * Claude on the TV: a glass chat screen. Type with the TV keyboard or
 * press the mic to speak; answers appear as large, easy-to-read text.
 * Up/Down scroll through the conversation.
 */
class ClaudeActivity : AppCompatActivity() {

    private lateinit var chat: ClaudeChat
    private lateinit var scroll: ScrollView
    private lateinit var thread: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: View
    private var thinkingBubble: View? = null
    private var busy = false

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) ask(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chat = ClaudeChat(this)
        val c = this
        val root = FrameLayout(c)
        GlassUi.installBackdrop(root)

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 64f), dp(c, 34f), dp(c, 64f), dp(c, 30f))
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // Header
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(c).apply { setImageResource(R.drawable.ic_lucide_sparkle) },
            LinearLayout.LayoutParams(dp(c, 26f), dp(c, 26f)))
        header.addView(GlassUi.text(c, "Claude", 28f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(c, 12f)
        })
        header.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(GlassUi.text(c, "Opus 5.5", 13f, R.font.inter_medium, GlassUi.SECONDARY))
        column.addView(header)

        // Conversation
        thread = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(c, 12f), 0, dp(c, 12f))
        }
        scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            addView(thread)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(c, 14f) })

        // Input bar
        val bar = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.panel(c, 28f, 0x22)
            setPadding(dp(c, 8f), dp(c, 6f), dp(c, 8f), dp(c, 6f))
        }
        input = EditText(c).apply {
            hint = "Ask Claude anything…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(GlassUi.primaryText())
            typeface = GlassUi.font(c, R.font.inter_regular)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            background = GlassUi.rowBackground(c, 22f)
            setPadding(dp(c, 18f), dp(c, 12f), dp(c, 18f), dp(c, 12f))
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    submitTyped(); true
                } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = roundButton(R.drawable.ic_lucide_mic, "Speak") { listen() }
        bar.addView(mic, LinearLayout.LayoutParams(dp(c, 44f), dp(c, 44f)).apply { marginStart = dp(c, 8f) })
        sendButton = roundButton(R.drawable.ic_lucide_arrow_up, "Send") { submitTyped() }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(c, 44f), dp(c, 44f)).apply { marginStart = dp(c, 8f) })
        val clear = roundButton(R.drawable.ic_lucide_trash, "New conversation") { newConversation() }
        bar.addView(clear, LinearLayout.LayoutParams(dp(c, 44f), dp(c, 44f)).apply { marginStart = dp(c, 8f) })
        column.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12f) })

        setContentView(root)
        UiSounds.attach(this)
        showWelcome()
        mic.requestFocus()
        if (ClaudeChat.getApiKey(this) == null) root.post { askForKey() }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        Thread { chat.close() }.start()
        super.onDestroy()
    }

    // ---------- Actions ----------

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            input.requestFocus()
            return
        }
        input.setText("")
        ask(text)
    }

    private fun ask(text: String) {
        if (busy) return
        if (ClaudeChat.getApiKey(this) == null) {
            askForKey()
            return
        }
        busy = true
        addBubble(text, fromUser = true)
        thinkingBubble = addBubble("Thinking…", fromUser = false, dim = true)
        worker.execute {
            val reply = chat.send(text)
            main.post {
                if (isDestroyed) return@post
                thinkingBubble?.let { thread.removeView(it) }
                thinkingBubble = null
                busy = false
                when (reply) {
                    is ClaudeChat.Reply.Text -> {
                        val answer = addBubble(reply.text + if (reply.truncated) "\n\n(Answer cut short.)" else "", fromUser = false)
                        scrollToTopOf(answer)
                    }
                    is ClaudeChat.Reply.Failed -> addBubble(reply.message, fromUser = false, error = true)
                }
            }
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude")
        try {
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            GlassUi.toast(this, "Voice input isn’t available. Type instead.")
            input.requestFocus()
        }
    }

    private fun newConversation() {
        if (busy) return
        chat.clear()
        thread.removeAllViews()
        showWelcome()
    }

    private fun askForKey() {
        GlassDialog(this)
            .title("Connect Claude")
            .message("Enter your Anthropic API key. Create one at console.anthropic.com › API Keys. " +
                "It’s stored only on this TV, and questions are billed to your Anthropic account.")
            .input("sk-ant-…", ClaudeChat.getApiKey(this).orEmpty(), secret = true)
            .also { d ->
                d.button("Save", GlassDialog.Style.BOLD) {
                    val key = d.inputText
                    if (key.isNotEmpty()) {
                        ClaudeChat.setApiKey(this, key)
                        GlassUi.toast(this, "Claude is ready")
                    }
                }
                d.button("Not Now") {}
            }
            .show()
    }

    // ---------- Conversation views ----------

    private fun showWelcome() {
        addBubble("Hi! Ask me anything: what to watch tonight, a quick recipe, a fact you’re trying to remember, " +
            "or help settling a debate. Press the mic to talk, or type below.", fromUser = false)
    }

    private fun addBubble(text: String, fromUser: Boolean, dim: Boolean = false, error: Boolean = false): View {
        val c = this
        val bubble = TextView(c).apply {
            this.text = text
            typeface = GlassUi.font(c, if (fromUser) R.font.inter_medium else R.font.inter_regular)
            textSize = if (fromUser) 16f else 18f
            setLineSpacing(0f, 1.32f)
            setTextColor(when {
                error -> 0xFFFF9F97.toInt()
                dim -> 0x99FFFFFF.toInt()
                else -> 0xF2FFFFFF.toInt()
            })
            setPadding(dp(c, 20f), dp(c, 14f), dp(c, 20f), dp(c, 14f))
            background = StateListDrawable().apply {
                val r = dp(c, 22f).toFloat()
                val idle = if (fromUser) 0x2EFFFFFF else 0x14FFFFFF
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = r; setColor(idle + 0x0C000000); setStroke(dp(c, 1f), 0x66FFFFFF)
                })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = r; setColor(idle) })
            }
            // Focusable so the remote can move up through the conversation.
            isFocusable = !dim
            setOnKeyListener { v, keyCode, event -> scrollWithin(v, keyCode, event) }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(c, 10f)
            gravity = if (fromUser) Gravity.END else Gravity.START
            if (fromUser) marginStart = dp(c, 160f) else marginEnd = dp(c, 120f)
        }
        thread.addView(bubble, lp)
        scroll.post { scroll.smoothScrollTo(0, thread.height) }
        return bubble
    }

    /** Long answers: Up/Down scroll inside the focused answer before moving on. */
    private fun scrollWithin(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val page = scroll.height * 2 / 3
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (v.bottom > scroll.scrollY + scroll.height) {
                scroll.smoothScrollBy(0, page); true
            } else false
            KeyEvent.KEYCODE_DPAD_UP -> if (v.top < scroll.scrollY) {
                scroll.smoothScrollBy(0, -page); true
            } else false
            else -> false
        }
    }

    private fun scrollToTopOf(v: View) {
        scroll.post {
            if (v.height > scroll.height) {
                scroll.smoothScrollTo(0, v.top - dp(this, 10f))
                v.requestFocus()
            } else {
                scroll.smoothScrollTo(0, thread.height)
            }
        }
    }

    private fun roundButton(icon: Int, label: String, onClick: () -> Unit): View {
        val c = this
        return FrameLayout(c).apply {
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xF5FFFFFF.toInt()) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) })
            }
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(c).apply {
                setImageResource(icon)
                imageTintList = GlassUi.primaryText()
                isDuplicateParentStateEnabled = true
            }, FrameLayout.LayoutParams(dp(c, 18f), dp(c, 18f), Gravity.CENTER))
            GlassUi.focusLift(this, 1.08f)
        }
    }
}
