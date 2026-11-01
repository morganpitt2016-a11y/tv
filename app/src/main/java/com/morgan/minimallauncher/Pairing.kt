package com.morgan.minimallauncher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.morgan.minimallauncher.GlassUi.dp
import java.security.SecureRandom

/**
 * Pairing an iPhone like Apple TV does: the phone asks to connect, the TV shows
 * a four-digit code, and you type that code on the phone. Only someone in front
 * of the TV can see the code, and it expires in two minutes (or after 5 wrong tries).
 */
object Pairing {
    private const val TTL_MS = 120_000L
    private const val MAX_TRIES = 5

    @Volatile var code: String? = null
        private set
    @Volatile var expiresAt = 0L
        private set
    /** When the last pairing succeeded (so the pop-up can say "Paired"). */
    @Volatile var pairedAt = 0L
        private set
    private var tries = 0
    private var requestedAt = 0L
    private val random = SecureRandom()

    fun active() = code != null && System.currentTimeMillis() < expiresAt

    /** A phone wants to pair: make (or keep, if it just asked) a code. */
    @Synchronized fun request(): String {
        val now = System.currentTimeMillis()
        if (active() && now - requestedAt < 5_000L) return code!!
        code = "%04d".format(random.nextInt(10_000))
        expiresAt = now + TTL_MS
        tries = 0
        requestedAt = now
        return code!!
    }

    /** Returns the number of tries left on a wrong code, 0 if it's locked or expired, or -1 if the code was right. */
    @Synchronized fun confirm(entered: String): Int {
        if (!active()) return 0
        if (entered.trim() == code) { code = null; pairedAt = System.currentTimeMillis(); return -1 }
        tries++
        if (tries >= MAX_TRIES) { code = null; return 0 }
        return MAX_TRIES - tries
    }

    @Synchronized fun cancel() { code = null }
}

/** The code pop-up on the TV: four big glass digits, Apple style. */
class PairActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var digits: List<TextView>
    private lateinit var caption: TextView
    private val openedAt = System.currentTimeMillis()
    private var shown: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        GlassUi.installBackdrop(root)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(GlassUi.text(this, "Pair Your iPhone", 34f, R.font.inter_semibold).apply { gravity = Gravity.CENTER })
        val address = RemoteServer.ip(this)?.let { "$it:${RemoteServer.PORT}" } ?: "this TV’s address"
        col.addView(GlassUi.text(this, "On your iPhone, open  $address  in Safari\nand enter this code.", 16f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PairActivity, 12f) })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        digits = (0 until 4).map { i ->
            val box = TextView(this).apply {
                gravity = Gravity.CENTER
                typeface = GlassUi.font(context, R.font.inter_semibold)
                textSize = 52f
                setTextColor(0xFFFFFFFF.toInt())
                includeFontPadding = false
                background = GlassUi.panel(context, 18f, 0x2E)
            }
            row.addView(box, LinearLayout.LayoutParams(dp(this, 78f), dp(this, 98f)).apply { if (i > 0) marginStart = dp(this@PairActivity, 14f) })
            box
        }
        col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PairActivity, 34f) })

        caption = GlassUi.text(this, "", 14f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        col.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PairActivity, 26f) })
        col.addView(GlassUi.text(this, "Press Back to cancel", 12f, R.font.inter_regular, 0x66FFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PairActivity, 8f) })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        UiSounds.attach(this)
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (Pairing.pairedAt >= openedAt) {
                caption.text = "✓  Paired"
                caption.setTextColor(0xFF30D158.toInt())
                handler.postDelayed({ finish() }, 1200)
                return
            }
            val code = Pairing.code
            if (code == null || !Pairing.active()) { finish(); return }
            if (code != shown) { shown = code; code.forEachIndexed { i, ch -> digits[i].text = ch.toString() } }
            val left = ((Pairing.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            caption.text = "Code expires in %d:%02d".format(left / 60, left % 60)
            handler.postDelayed(this, 400)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { Pairing.cancel(); finish(); return true }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
