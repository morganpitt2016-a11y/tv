package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * The tvOS-style start screen:
 *  - Welcome: first launch - set up this TV's account (name, optional passcode) and first person.
 *  - Who's Watching?: the account's people as big round avatars; pick one to go home.
 *  - Accounts: switch to another master account on this TV (passcode if it has one).
 */
class WhoActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var mode = WHO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        GlassUi.installBackdrop(root)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        UiSounds.attach(this)
        show(when {
            Accounts.needsSetup(this) -> WELCOME
            intent.getStringExtra(EXTRA_MODE) == ACCOUNTS -> ACCOUNTS
            else -> WHO
        })
    }

    @Deprecated("TV back handling")
    override fun onBackPressed() {
        when {
            mode == WELCOME -> Unit                                   // set up first
            mode == ACCOUNTS && Accounts.active(this) != null -> show(WHO)
            else -> close()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            @Suppress("DEPRECATION") onBackPressed(); return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------- Screens ----------

    private fun show(m: String) {
        mode = m
        content.removeAllViews()
        content.alpha = 0f
        when (m) {
            WELCOME -> buildWelcome()
            ACCOUNTS -> buildAccounts()
            else -> buildWho()
        }
        content.animate().alpha(1f).setDuration(Motion.PANEL).setInterpolator(Motion.standard).start()
    }

    private fun title(text: String, size: Float = 34f) =
        GlassUi.text(this, text, size, R.font.inter_semibold).apply { gravity = Gravity.CENTER; letterSpacing = -0.01f }

    private fun subtitle(text: String) =
        GlassUi.text(this, text, 15f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER }

    /** White capsule button (the focused one is solid white, like tvOS). */
    private fun capsule(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = GlassUi.font(context, R.font.inter_semibold)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(context, 30f), 0, dp(context, 30f), 0)
        setTextColor(GlassUi.primaryText())
        background = GlassUi.rowBackground(context, 22f, 0x33FFFFFF)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.06f, 10f) }
    }

    // Welcome / set-up

    private fun buildWelcome() {
        content.addView(title("Welcome", 44f))
        content.addView(subtitle("Set up this TV for your household.\nEach person gets their own apps, wallpaper and watch list."),
            lp(top = 14f))
        val go = capsule("Get Started") { setUp() }
        content.addView(go, LinearLayout.LayoutParams(-2, dp(this, 44f)).apply { topMargin = dp(this@WhoActivity, 40f) })
        go.requestFocus()
    }

    private fun setUp(first: Boolean = Accounts.all(this).isEmpty()) {
        val nameDialog = GlassDialog(this).title(if (first) "Name This Home" else "New Account")
            .message("For example “Mum’s Home”. Each account has its own people.")
            .input("Account name")
        nameDialog.button("Continue", GlassDialog.Style.BOLD) {
            val home = nameDialog.inputText.ifBlank { "Home" }
            val person = GlassDialog(this).title("Who Are You?").message("The first person in $home. You can add more next.")
                .input("Your name", "")
            person.button("Continue", GlassDialog.Style.BOLD) {
                val who = person.inputText.ifBlank { "Person 1" }.replace(",", "")
                val pass = GlassDialog(this).title("Passcode").message("Optional: a passcode to switch to $home on this TV.")
                    .input("Passcode", secret = true)
                pass.button("Set Passcode", GlassDialog.Style.BOLD) { finishSetUp(home, who, pass.inputText) }
                pass.button("No Passcode") { finishSetUp(home, who, null) }
                pass.show()
            }
            person.show()
        }
        if (!first) nameDialog.button("Cancel")
        nameDialog.show()
    }

    private fun finishSetUp(home: String, person: String, passcode: String?) {
        Accounts.create(this, home, passcode, person)
        ProfileManager.setActiveProfile(this, person)
        show(WHO)
    }

    // Who's watching?

    private fun buildWho() {
        val account = Accounts.active(this) ?: return show(WELCOME)
        content.addView(title("Who’s Watching?"))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        val people = ProfileManager.getProfiles(this)
        val active = ProfileManager.getActiveProfile(this)
        var focus: View? = null
        people.forEachIndexed { i, name ->
            val cell = avatarCell(name, Accounts.GRADIENTS[(i + account.colour) % Accounts.GRADIENTS.size]) {
                ProfileManager.setActiveProfile(this, name)
                close()
            }
            cell.setOnLongClickListener { personMenu(name); true }
            row.addView(cell, cellLp(i > 0))
            if (name == active) focus = cell
        }
        if (people.size < 6) row.addView(avatarCell("Add Person", null) { addPerson() }, cellLp(true))
        content.addView(row, lp(top = 44f))
        // The account, and switching to another one.
        val switch = capsule("${account.name}  ·  Switch Account") { show(ACCOUNTS) }
        switch.textSize = 13f
        content.addView(switch, LinearLayout.LayoutParams(-2, dp(this, 36f)).apply { topMargin = dp(this@WhoActivity, 48f) })
        (focus ?: row.getChildAt(0))?.requestFocus()
    }

    private fun cellLp(gap: Boolean) = LinearLayout.LayoutParams(dp(this, 132f), -2).apply { if (gap) marginStart = dp(this@WhoActivity, 18f) }

    /** A round avatar (initial on a soft gradient) with the name under it. */
    private fun avatarCell(name: String, gradient: IntArray?, onClick: () -> Unit): View {
        val size = dp(this, 108f)
        val circle = TextView(this).apply {
            text = if (gradient == null) "+" else name.trim().take(1).uppercase()
            typeface = GlassUi.font(context, R.font.inter_semibold)
            textSize = if (gradient == null) 44f else 46f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient ?: intArrayOf(0x33FFFFFF, 0x1FFFFFFF)).apply {
                shape = GradientDrawable.OVAL
            }
        }
        val label = GlassUi.text(this, name, 15f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = name
            addView(circle, LinearLayout.LayoutParams(size, size))
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(this@WhoActivity, 14f) })
            setOnClickListener { onClick() }
        }
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(this@WhoActivity, 3f), 0xFFFFFFFF.toInt()) }
        cell.setOnFocusChangeListener { _, has ->
            Motion.focus(circle, has, 1.12f, 16f)
            circle.foreground = if (has) ring else null
            label.setTextColor(if (has) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
        }
        return cell
    }

    private fun addPerson() {
        val d = GlassDialog(this).title("Add Person").message("They get their own apps, wallpaper and watch list.").input("Name")
        d.button("Add", GlassDialog.Style.BOLD) {
            val name = d.inputText.replace(",", "").trim()
            if (name.isNotEmpty() && name !in ProfileManager.getProfiles(this)) {
                // A new account's first save of its list includes the default person.
                ProfileManager.addProfile(this, name)
            }
            show(WHO)
        }
        d.button("Cancel")
        d.show()
    }

    private fun personMenu(name: String) {
        val d = GlassDialog(this).title(name)
        d.button("Rename") {
            val r = GlassDialog(this).title("Rename $name").input("Name", name)
            r.button("Save", GlassDialog.Style.BOLD) { ProfileManager.renameProfile(this, name, r.inputText); show(WHO) }
            r.button("Cancel")
            r.show()
        }
        if (ProfileManager.getProfiles(this).size > 1) d.button("Remove $name", GlassDialog.Style.DESTRUCTIVE) {
            ProfileManager.removeProfile(this, name); show(WHO)
        }
        d.button("Cancel")
        d.show()
    }

    // Accounts

    private fun buildAccounts() {
        content.addView(title("Accounts on This TV"))
        content.addView(subtitle("Each account has its own people."), lp(top = 10f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        val active = Accounts.active(this)
        var focus: View? = null
        Accounts.all(this).forEachIndexed { i, a ->
            val card = accountCard(a.name + if (a.passcodeHash != null) "  🔒" else "", Accounts.GRADIENTS[a.colour % Accounts.GRADIENTS.size]) { open(a) }
            card.setOnLongClickListener { accountMenu(a); true }
            row.addView(card, cardLp(i > 0))
            if (a.id == active?.id) focus = card
        }
        row.addView(accountCard("Add Account", null) { setUp(first = false) }, cardLp(row.childCount > 0))
        content.addView(row, lp(top = 40f))
        content.addView(subtitle("Hold OK on an account to rename it, change its passcode or remove it.").apply { textSize = 12f; alpha = 0.7f },
            lp(top = 36f))
        (focus ?: row.getChildAt(0))?.requestFocus()
    }

    private fun cardLp(gap: Boolean) = LinearLayout.LayoutParams(dp(this, 190f), dp(this, 112f)).apply { if (gap) marginStart = dp(this@WhoActivity, 22f) }

    private fun accountCard(name: String, gradient: IntArray?, onClick: () -> Unit) = TextView(this).apply {
        text = if (gradient == null) "+  $name" else name
        typeface = GlassUi.font(context, R.font.inter_semibold)
        textSize = 17f
        gravity = Gravity.CENTER
        setPadding(dp(context, 12f), 0, dp(context, 12f), 0)
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient ?: intArrayOf(0x33FFFFFF, 0x1FFFFFFF)).apply {
            cornerRadius = dp(context, 22f).toFloat()
        }
        isFocusable = true
        isClickable = true
        isLongClickable = true
        setOnClickListener { onClick() }
        val ring = GradientDrawable().apply { cornerRadius = dp(context, 22f).toFloat(); setStroke(dp(context, 3f), 0xFFFFFFFF.toInt()) }
        setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.08f, 16f); foreground = if (has) ring else null }
    }

    private fun open(a: Accounts.Account) {
        if (a.passcodeHash == null || a.id == Accounts.active(this)?.id) { Accounts.setActive(this, a.id); show(WHO); return }
        val d = GlassDialog(this).title(a.name).message("Enter the passcode for ${a.name}.").input("Passcode", secret = true)
        d.button("Continue", GlassDialog.Style.BOLD) {
            if (Accounts.checkPasscode(a, d.inputText)) { Accounts.setActive(this, a.id); show(WHO) }
            else GlassUi.toast(this, "Incorrect passcode")
        }
        d.button("Cancel")
        d.show()
    }

    private fun accountMenu(a: Accounts.Account) {
        val unlock = { action: () -> Unit ->
            if (a.passcodeHash == null) action() else {
                val p = GlassDialog(this).title(a.name).message("Enter the passcode for ${a.name}.").input("Passcode", secret = true)
                p.button("Continue", GlassDialog.Style.BOLD) { if (Accounts.checkPasscode(a, p.inputText)) action() else GlassUi.toast(this, "Incorrect passcode") }
                p.button("Cancel")
                p.show()
            }
        }
        val d = GlassDialog(this).title(a.name)
        d.button("Rename") { unlock {
            val r = GlassDialog(this).title("Rename").input("Account name", a.name)
            r.button("Save", GlassDialog.Style.BOLD) { if (r.inputText.isNotBlank()) Accounts.rename(this, a.id, r.inputText); show(ACCOUNTS) }
            r.button("Cancel")
            r.show()
        } }
        d.button(if (a.passcodeHash == null) "Set Passcode" else "Change Passcode") { unlock {
            val p = GlassDialog(this).title("Passcode").message("Leave it empty to remove the passcode.").input("New passcode", secret = true)
            p.button("Save", GlassDialog.Style.BOLD) { Accounts.setPasscode(this, a.id, p.inputText); show(ACCOUNTS) }
            p.button("Cancel")
            p.show()
        } }
        if (Accounts.all(this).size > 1) d.button("Remove Account", GlassDialog.Style.DESTRUCTIVE) { unlock {
            GlassDialog(this).title("Remove ${a.name}?").message("Its people, docks and watch lists are deleted from this TV.")
                .button("Remove", GlassDialog.Style.DESTRUCTIVE) { Accounts.remove(this, a.id); show(ACCOUNTS) }
                .button("Cancel").show()
        } }
        d.button("Cancel")
        d.show()
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WhoActivity, top); gravity = Gravity.CENTER_HORIZONTAL }

    companion object {
        const val EXTRA_MODE = "mode"
        const val WHO = "who"
        const val ACCOUNTS = "accounts"
        const val WELCOME = "welcome"
    }
}
