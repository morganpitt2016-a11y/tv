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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
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
    private lateinit var optionsPill: android.widget.TextView
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
        view.resizeMode = Prefs.int(this, "player_resize", AspectRatioFrameLayout.RESIZE_MODE_FIT)

        // "Subtitles · Audio · Picture": shown with the playback controls (and by the Menu key).
        optionsPill = GlassUi.text(this, "Subtitles · Audio · Picture", 13f, R.font.inter_medium).apply {
            background = GlassUi.panel(context, 18f, 0x40)
            setPadding(dp(context, 18f), dp(context, 10f), dp(context, 18f), dp(context, 10f))
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { options() }
            setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.06f else 1f) }
        }
        root.addView(optionsPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(this@PlayerActivity, 36f), dp(this@PlayerActivity, 48f), 0) })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            optionsPill.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
        })

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
        start(url, resume = !live && !intent.getBooleanExtra(EXTRA_FROM_START, false))
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
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO)) {
            options(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && live) {
            banner.animate().cancel(); banner.alpha = 1f
            optionsPill.visibility = View.VISIBLE; optionsPill.requestFocus()
            handler.postDelayed({ if (!optionsPill.isFocused) optionsPill.visibility = View.GONE }, 6000)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 3500)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Subtitles, audio, picture, speed ----------

    private class Choice(val group: Tracks.Group, val index: Int, val label: String)

    private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
        val base = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track $n"
        val extra = if (f.channelCount > 2) " · ${if (f.channelCount == 6) "5.1" else "${f.channelCount}ch"}" else ""
        return base + extra
    }

    private fun choices(type: Int): List<Choice> {
        val out = mutableListOf<Choice>()
        player?.currentTracks?.groups?.filter { it.type == type }?.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) out += Choice(g, i, trackLabel(g.getTrackFormat(i), out.size + 1))
        }
        return out
    }

    private fun selectedLabel(type: Int, off: String?): String {
        val p = player ?: return off ?: "-"
        if (type == C.TRACK_TYPE_TEXT && p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return off ?: "Off"
        val c = choices(type)
        return c.firstOrNull { it.group.isTrackSelected(it.index) }?.label ?: if (type == C.TRACK_TYPE_TEXT) (off ?: "Off") else (c.firstOrNull()?.label ?: "-")
    }

    private fun resizeName(m: Int) = when (m) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch to fill"
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (crop edges)"
        else -> "Original shape"
    }

    private fun options() {
        val p = player ?: return
        GlassDialog(this).title("Playback")
            .button("Subtitles · ${selectedLabel(C.TRACK_TYPE_TEXT, "Off")}") { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles") }
            .button("Audio · ${selectedLabel(C.TRACK_TYPE_AUDIO, null)}") { pickTrack(C.TRACK_TYPE_AUDIO, "Audio") }
            .button("Picture · ${resizeName(view.resizeMode)}") { pickPicture() }
            .button("Speed · ${p.playbackParameters.speed}x") { pickSpeed() }
            .button("Close").show()
    }

    private fun pickTrack(type: Int, title: String) {
        val p = player ?: return
        val list = choices(type)
        val d = GlassDialog(this).title(title).scrolling()
        if (list.isEmpty()) d.message(if (type == C.TRACK_TYPE_TEXT) "This film has no subtitles in the file." else "Only one audio track.")
        if (type == C.TRACK_TYPE_TEXT) d.button("Off") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        for (c in list) d.button(c.label + if (c.group.isTrackSelected(c.index)) "  ✓" else "") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index)).build()
        }
        d.button("Back") { options() }
        d.show()
    }

    private fun pickPicture() {
        val d = GlassDialog(this).title("Picture")
        for (m in listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)) {
            d.button(resizeName(m) + if (view.resizeMode == m) "  ✓" else "") {
                view.resizeMode = m
                Prefs.setInt(this, "player_resize", m)
            }
        }
        d.button("Back") { options() }
        d.show()
    }

    private fun pickSpeed() {
        val p = player ?: return
        val d = GlassDialog(this).title("Speed")
        for (sp in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) d.button("${sp}x" + if (p.playbackParameters.speed == sp) "  ✓" else "") { p.setPlaybackSpeed(sp) }
        d.button("Back") { options() }
        d.show()
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
        const val EXTRA_FROM_START = "from_start"

        fun play(c: Context, url: String, title: String, live: Boolean, subtitle: String? = null, eyebrow: String? = null, alt: String? = null, fromStart: Boolean = false) {
            c.startActivity(Intent(c, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_ALT_URL, alt).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SUBTITLE, subtitle).putExtra(EXTRA_EYEBROW, eyebrow).putExtra(EXTRA_FROM_START, fromStart)
                .addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
