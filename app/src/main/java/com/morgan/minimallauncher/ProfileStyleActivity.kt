package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Customise one person: their picture, the colour behind it, their highlight
 * colour, and whether the clock and icons wear it.
 */
class ProfileStyleActivity : Activity() {

    private lateinit var name: String
    private lateinit var preview: ImageView
    private lateinit var accentCaption: TextView
    private val rings = mutableListOf<Pair<GradientDrawable, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_NAME) ?: ProfileManager.getActiveProfile(this)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        GlassUi.installBackdrop(root)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(this@ProfileStyleActivity, 80f), dp(this@ProfileStyleActivity, 30f), dp(this@ProfileStyleActivity, 80f), dp(this@ProfileStyleActivity, 40f))
        }
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        UiSounds.attach(this)

        // Live preview.
        preview = ImageView(this)
        col.addView(preview, LinearLayout.LayoutParams(dp(this, 104f), dp(this, 104f)))
        col.addView(GlassUi.text(this, name, 20f, R.font.inter_semibold).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@ProfileStyleActivity, 10f) })

        // Character.
        label(col, "CHARACTER")
        val chars = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f), dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f)) }
        chars.addView(item(textCircle(name.trim().take(1).uppercase(), 18f), { ProfileStyle.of(this, name).avatar == "" }) {
            ProfileStyle.setAvatar(this, name, "")
        })
        for (c in Avatars.CHARACTERS) chars.addView(item(textCircle(c, 22f), { ProfileStyle.of(this, name).avatar == "e:$c" }) {
            ProfileStyle.setAvatar(this, name, "e:$c")
        })
        chars.addView(item(textCircle("📷", 22f), { ProfileStyle.of(this, name).avatar == "photo" }) {
            if (Avatars.hasPhoto(this, name)) ProfileStyle.setAvatar(this, name, "photo")
            else GlassDialog(this).title("Use a Picture")
                .message("On your iPhone, open the TV remote, go to Photos › Profile Picture, choose $name and pick a picture. Then come back here and choose the camera.")
                .button("OK", GlassDialog.Style.BOLD).show()
        })
        col.addView(row(chars))

        // Picture colour.
        label(col, "PICTURE COLOUR")
        val bgs = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f), dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f)) }
        Accounts.GRADIENTS.forEachIndexed { i, g ->
            val swatch = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, g).apply { shape = GradientDrawable.OVAL }
            }
            bgs.addView(item(swatch, { ProfileStyle.of(this, name).colour == i || (ProfileStyle.of(this, name).colour < 0 && Avatars.gradient(this, name) === g) }) {
                ProfileStyle.setColour(this, name, i)
            })
        }
        col.addView(row(bgs))

        // Highlight colour.
        label(col, "HIGHLIGHT COLOUR")
        val accents = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f), dp(this@ProfileStyleActivity, 6f), dp(this@ProfileStyleActivity, 8f)) }
        accentCaption = GlassUi.text(this, "", 13f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        for ((nm, colour) in ProfileStyle.ACCENTS) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (colour == 0) Color.WHITE else colour) }
            }
            accents.addView(item(swatch, { ProfileStyle.of(this, name).accent == colour }, onFocus = { accentCaption.text = nm }) {
                ProfileStyle.setAccent(this, name, colour)
            })
        }
        col.addView(row(accents))
        col.addView(accentCaption, LinearLayout.LayoutParams(-1, -2))
        col.addView(GlassUi.text(this, "Shows as the glow and ring around the app you’ve selected.", 12f, R.font.inter_regular, 0x80FFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@ProfileStyleActivity, 4f) })

        // Where else the colour shows.
        label(col, "ALSO USE THE COLOUR FOR")
        val toggles = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        toggles.addView(toggle("The clock", { ProfileStyle.of(this, name).clockAccent }) { ProfileStyle.setClock(this, name, it) })
        toggles.addView(toggle("The top icons", { ProfileStyle.of(this, name).iconAccent }) { ProfileStyle.setIcons(this, name, it) },
            LinearLayout.LayoutParams(-2, dp(this, 40f)).apply { marginStart = dp(this@ProfileStyleActivity, 14f) })
        col.addView(toggles, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@ProfileStyleActivity, 6f) })

        val done = pill("Done") { finish() }
        col.addView(done, LinearLayout.LayoutParams(dp(this, 200f), dp(this, 42f)).apply { topMargin = dp(this@ProfileStyleActivity, 26f) })

        refresh()
        // Start on the first character so the page opens ready to use.
        chars.getChildAt(0)?.requestFocus()
    }

    // ---------- Pieces ----------

    private fun label(col: LinearLayout, text: String) {
        col.addView(GlassUi.text(this, text, 11f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@ProfileStyleActivity, 22f) })
    }

    private fun row(inner: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        addView(inner)
    }

    private fun textCircle(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = GlassUi.font(context, R.font.inter_semibold)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2EFFFFFF) }
    }

    /** A round, selectable choice; a ring shows what's chosen. */
    private fun item(content: View, selected: () -> Boolean, onFocus: (() -> Unit)? = null, onPick: () -> Unit): View {
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(this@ProfileStyleActivity, 3f), Color.WHITE); alpha = 0 }
        rings += ring to selected
        val size = dp(this, 46f)
        return FrameLayout(this).apply {
            foreground = ring
            isFocusable = true; isClickable = true
            addView(content, FrameLayout.LayoutParams(-1, -1).apply { val m = dp(this@ProfileStyleActivity, 3f); setMargins(m, m, m, m) })
            setOnClickListener { onPick(); refresh() }
            setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.14f, 8f); if (has) onFocus?.invoke() }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(this@ProfileStyleActivity, 10f) }
        }
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = GlassUi.font(context, R.font.inter_semibold)
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(GlassUi.primaryText())
        background = GlassUi.rowBackground(context, 21f, 0x33FFFFFF)
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.06f, 8f) }
    }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        val t = pill("") {}
        fun text() { t.text = "$title: ${if (get()) "On" else "Off"}" }
        text()
        t.setPadding(dp(this, 20f), 0, dp(this, 20f), 0)
        t.setOnClickListener { set(!get()); text() }
        t.layoutParams = LinearLayout.LayoutParams(-2, dp(this, 40f))
        return t
    }

    private fun refresh() {
        rings.forEach { (d, sel) -> d.alpha = if (sel()) 255 else 0 }
        preview.setImageBitmap(Avatars.render(this, name, dp(this, 104f)))
    }

    companion object { const val EXTRA_NAME = "name" }
}
