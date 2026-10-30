package com.morgan.minimallauncher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * tvOS-style alert: dimmed screen, a frosted glass card with a bold
 * title, a message, an optional text field, and full-width stacked
 * buttons that turn white when focused with the remote.
 */
class GlassDialog(private val context: Context) {

    enum class Style { NORMAL, BOLD, DESTRUCTIVE }

    private class Choice(val label: String, val style: Style, val checked: Boolean?, val dismiss: Boolean, val action: () -> Unit)

    private var title: String? = null
    private var message: String? = null
    private var inputHint: String? = null
    private var inputValue: String = ""
    private var inputSecret = false
    private var input: EditText? = null
    private val choices = mutableListOf<Choice>()
    private var scrollChoices = false
    private var focusIndex = 0

    fun title(t: String) = apply { title = t }
    fun message(m: String) = apply { message = m }

    /** Adds a text field; read it in a button action with [inputText]. */
    fun input(hint: String, value: String = "", secret: Boolean = false) = apply {
        inputHint = hint; inputValue = value; inputSecret = secret
    }

    val inputText: String get() = input?.text?.toString()?.trim().orEmpty()

    fun button(label: String, style: Style = Style.NORMAL, action: () -> Unit = {}) = apply {
        choices += Choice(label, style, null, true, action)
    }

    /** A row with a checkmark that keeps the dialog open (for multi-select lists). */
    fun toggle(label: String, checked: Boolean, action: () -> Unit) = apply {
        choices += Choice(label, Style.NORMAL, checked, false, action)
    }

    /** Puts the buttons in a scrolling list (long lists). */
    fun scrolling() = apply { scrollChoices = true }

    fun focusButton(index: Int) = apply { focusIndex = index }

    fun show(): Dialog {
        val c = context
        val dialog = Dialog(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        val root = FrameLayout(c).apply { setBackgroundColor(0xA6000000.toInt()) }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUi.panel(c, 26f, 0x30).let {
                // Slightly darker base under the glass so text stays crisp.
                android.graphics.drawable.LayerDrawable(arrayOf(
                    GradientDrawable().apply { cornerRadius = dp(c, 26f).toFloat(); setColor(0xB31C1C1F.toInt()) }, it))
            }
            setPadding(dp(c, 28f), dp(c, 26f), dp(c, 28f), dp(c, 22f))
            elevation = dp(c, 30f).toFloat()
        }
        root.addView(card, FrameLayout.LayoutParams(dp(c, 440f), -2, Gravity.CENTER))

        title?.let {
            card.addView(GlassUi.text(c, it, 20f, R.font.inter_semibold).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2))
        }
        message?.let {
            val long = it.length > 280
            val text = GlassUi.text(c, it, if (long) 12f else 13.5f, R.font.inter_regular, 0xB3FFFFFF.toInt()).apply {
                gravity = if (long) Gravity.START else Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            }
            if (long) {
                // Long text scrolls; it can take remote focus so Up/Down scroll it.
                val scroll = ScrollView(c).apply {
                    isFocusable = true
                    isVerticalScrollBarEnabled = true
                    addView(text)
                }
                card.addView(scroll, LinearLayout.LayoutParams(-1, dp(c, 260f)).apply { topMargin = dp(c, 12f) })
            } else {
                card.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10f) })
            }
        }

        inputHint?.let { hint ->
            val field = EditText(c).apply {
                this.hint = hint
                setText(inputValue)
                setSelection(text.length)
                setTextColor(Color.WHITE)
                setHintTextColor(0x80FFFFFF.toInt())
                typeface = GlassUi.font(c, R.font.inter_regular)
                textSize = 15f
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = if (inputSecret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = dp(c, 12f).toFloat(); setColor(0x33FFFFFF); setStroke(dp(c, 2f), 0xE6FFFFFF.toInt())
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        cornerRadius = dp(c, 12f).toFloat(); setColor(0x26FFFFFF)
                    })
                }
            }
            input = field
            card.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })
        }

        val buttons = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val buttonViews = mutableListOf<View>()
        for (choice in choices) {
            val row = makeButton(choice, dialog)
            buttonViews += row
            buttons.addView(row, LinearLayout.LayoutParams(-1, dp(c, 44f)).apply { topMargin = dp(c, 8f) })
        }
        val buttonsContainer: View = if (scrollChoices) {
            ScrollView(c).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, 0, dp(c, 4f))
                addView(buttons)
            }
        } else buttons
        card.addView(buttonsContainer, LinearLayout.LayoutParams(-1,
            if (scrollChoices) minOf(dp(c, 300f), choices.size * dp(c, 52f)) else -2).apply { topMargin = dp(c, 14f) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.2f)
        }
        // Enter on the text field moves to the first button.
        input?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                buttonViews.firstOrNull()?.requestFocus(); true
            } else false
        }
        dialog.show()
        (input ?: buttonViews.getOrNull(focusIndex) ?: buttonViews.firstOrNull())?.requestFocus()
        return dialog
    }

    private fun makeButton(choice: Choice, dialog: Dialog): View {
        val c = context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 12f, 0x1FFFFFFF)
            setPadding(dp(c, 18f), 0, dp(c, 18f), 0)
            isFocusable = true
            isClickable = true
        }
        val idle = if (choice.style == Style.DESTRUCTIVE) GlassUi.RED else GlassUi.WHITE
        val onFocus = if (choice.style == Style.DESTRUCTIVE) GlassUi.RED else GlassUi.INK
        val font = if (choice.style == Style.BOLD) R.font.inter_semibold else R.font.inter_medium
        val label = GlassUi.rowText(c, choice.label, 15f, font, GlassUi.primaryText(onFocus, idle)).apply {
            gravity = if (choice.checked == null) Gravity.CENTER else Gravity.START
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        var check: TextView? = null
        if (choice.checked != null) {
            check = GlassUi.rowText(c, if (choice.checked) "✓" else "", 16f, R.font.inter_semibold, GlassUi.primaryText())
            row.addView(check, LinearLayout.LayoutParams(-2, -2))
        }
        GlassUi.focusLift(row, 1.02f)
        row.setOnClickListener {
            if (choice.dismiss) dialog.dismiss()
            choice.action()
            check?.let { it.text = if (it.text.isEmpty()) "✓" else "" }
        }
        return row
    }
}
