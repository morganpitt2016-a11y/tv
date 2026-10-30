package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Plays your provider's channels, films and episodes directly - no TiviMate,
 * no searching. Back returns to wherever you came from.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var banner: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var url = ""
    private var altUrl: String? = null
    private var live = false
    private var triedAlt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        altUrl = intent.getStringExtra(EXTRA_ALT_URL)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        view = PlayerView(this).apply {
            useController = !live
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = true
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        // Title card, like tvOS: fades away after a few seconds.
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUi.panel(context, 18f, 0x40)
            setPadding(dp(context, 22f), dp(context, 14f), dp(context, 26f), dp(context, 16f))
        }
        intent.getStringExtra(EXTRA_EYEBROW)?.let { banner.addView(GlassUi.text(this, it, 11f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f }) }
        banner.addView(GlassUi.text(this, intent.getStringExtra(EXTRA_TITLE).orEmpty(), 22f, R.font.inter_semibold))
        intent.getStringExtra(EXTRA_SUBTITLE)?.let { banner.addView(GlassUi.text(this, it, 13f, R.font.inter_medium, 0xCCFFFFFF.toInt())) }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(this@PlayerActivity, 48f), dp(this@PlayerActivity, 36f), 0, 0) })
        setContentView(root)
        handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 4500)
    }

    override fun onStart() {
        super.onStart()
        start(url, resume = !live)
    }

    private fun start(u: String, resume: Boolean) {
        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        p.setMediaItem(MediaItem.fromUri(u))
        if (resume) Prefs.int(this, posKey(), 0).takeIf { it > 30 }?.let { p.seekTo(it * 1000L) }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Some providers only serve live TV one way: try the other before giving up.
                val alt = altUrl
                if (!triedAlt && alt != null) { triedAlt = true; url = alt; start(alt, resume = false); return }
                GlassUi.toast(this@PlayerActivity, "This won’t play right now (${error.errorCodeName})")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !live) { Prefs.setInt(this@PlayerActivity, posKey(), 0); finish() }
            }
        })
        p.playWhenReady = true
        p.prepare()
    }

    private fun posKey() = "pos_" + (url.hashCode().toLong() and 0xffffffffL)

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            // Remember where a film or episode was, to carry on next time.
            if (!live && p.duration > 0) {
                val left = p.duration - p.currentPosition
                Prefs.setInt(this, posKey(), if (left < 120_000) 0 else (p.currentPosition / 1000).toInt())
            }
            p.release()
        }
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && live) {
            banner.animate().cancel(); banner.alpha = 1f
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 3500)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ALT_URL = "alt"
        const val EXTRA_LIVE = "live"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "sub"
        const val EXTRA_EYEBROW = "eyebrow"

        fun play(c: Context, url: String, title: String, live: Boolean, subtitle: String? = null, eyebrow: String? = null, alt: String? = null) {
            c.startActivity(Intent(c, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_ALT_URL, alt).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SUBTITLE, subtitle).putExtra(EXTRA_EYEBROW, eyebrow)
                .addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
