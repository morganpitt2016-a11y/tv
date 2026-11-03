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
    private lateinit var bannerTitle: android.widget.TextView
    private lateinit var bannerSub: android.widget.TextView
    private lateinit var numberView: android.widget.TextView
    private lateinit var nextView: android.widget.TextView
    private var titleText = ""
    private var seriesTitle: String? = null
    private var liveId = -1
    private var zapList: List<Xtream.Live> = emptyList()
    private var zapIndex = 0
    private var numberBuf = ""
    private class Queued(val url: String, val season: Int, val episode: Int)
    private val queue = ArrayDeque<Queued>()
    private var counting = false
    private var image: String? = null
    private var curSeason = 0
    private var curEpisode = 0
    private var ticks = 0
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
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        bannerTitle = GlassUi.text(this, titleText, 22f, R.font.inter_semibold)
        bannerSub = GlassUi.text(this, intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(), 13f, R.font.inter_medium, 0xCCFFFFFF.toInt())
        banner.addView(bannerTitle)
        banner.addView(bannerSub)
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(this@PlayerActivity, 48f), dp(this@PlayerActivity, 36f), 0, 0) })
        // Channel number being typed (live TV).
        numberView = GlassUi.text(this, "", 44f, R.font.inter_semibold).apply {
            background = GlassUi.panel(context, 18f, 0x40)
            setPadding(dp(context, 26f), dp(context, 10f), dp(context, 26f), dp(context, 10f))
            visibility = View.GONE
        }
        root.addView(numberView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(this@PlayerActivity, 36f), dp(this@PlayerActivity, 48f), 0) })
        // "Next episode" countdown.
        nextView = GlassUi.text(this, "", 15f, R.font.inter_medium).apply {
            background = GlassUi.panel(context, 18f, 0x50)
            setPadding(dp(context, 24f), dp(context, 16f), dp(context, 24f), dp(context, 16f))
            visibility = View.GONE
        }
        root.addView(nextView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(this@PlayerActivity, 48f), dp(this@PlayerActivity, 56f)) })
        setContentView(root)
        handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 4500)

        seriesTitle = intent.getStringExtra(EXTRA_SERIES)
        image = intent.getStringExtra(EXTRA_IMAGE)
        curSeason = intent.getIntExtra(EXTRA_SEASON, 0); curEpisode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val qu = intent.getStringArrayExtra(EXTRA_Q_URLS); val qs = intent.getIntArrayExtra(EXTRA_Q_S); val qe = intent.getIntArrayExtra(EXTRA_Q_E)
        if (qu != null && qs != null && qe != null) for (i in qu.indices) queue.addLast(Queued(qu[i], qs[i], qe[i]))
        liveId = intent.getIntExtra(EXTRA_LIVE_ID, -1)
        if (live && liveId >= 0) rememberLive()
        if (live && liveId >= 0) {
            val app = applicationContext
            Thread {
                val all = try { Xtream.live(app) } catch (e: Exception) { emptyList() }
                val cur = all.firstOrNull { it.id == liveId }
                val group = if (cur == null) emptyList() else all.filter { it.category == cur.category }.sortedBy { it.num }
                runOnUiThread { zapList = group; zapIndex = group.indexOfFirst { it.id == liveId }.coerceAtLeast(0) }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        start(url, resume = !live && !intent.getBooleanExtra(EXTRA_FROM_START, false))
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
    }

    private fun newPlayer(): ExoPlayer {
        // Start sooner than the defaults: a small buffer to begin with, then it fills while you watch.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (live) 2_000 else 15_000, if (live) 20_000 else 60_000, if (live) 500 else 900, if (live) 1_500 else 2_000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Some providers only serve live TV one way: try the other before giving up.
                val alt = altUrl
                if (!triedAlt && alt != null) { triedAlt = true; url = alt; start(alt, resume = false); return }
                GlassUi.toast(this@PlayerActivity, "This won’t play right now (${error.errorCodeName})")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !live) {
                    Prefs.setInt(this@PlayerActivity, posKey(), 0)
                    if (queue.isNotEmpty()) startCountdown() else finish()
                }
            }
        })
        return p
    }

    private fun start(u: String, resume: Boolean) {
        val p = player ?: newPlayer().also { player = it; view.player = it }   // changing channel reuses the player
        p.stop()
        p.setMediaItem(MediaItem.fromUri(u))
        // Pick up the subtitle and audio language you chose last time for this title.
        val key = trackKey()
        val tl = Prefs.string(this, "trk_t_$key"); val al = Prefs.string(this, "trk_a_$key")
        if (tl != null || al != null) {
            val b = p.trackSelectionParameters.buildUpon()
            al?.let { b.setPreferredAudioLanguage(it) }
            if (tl == "off") b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) else if (tl != null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(tl)
            p.trackSelectionParameters = b.build()
        }
        if (resume) Prefs.int(this, posKey(), 0).takeIf { it > 30 }?.let { p.seekTo(it * 1000L) }
        p.playWhenReady = true
        p.prepare()
    }

    // Near the end of an episode, offer the next one (rather than only when the picture stops).
    private val progressTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !live && queue.isNotEmpty() && !counting && p.duration > 0) {
                val left = p.duration - p.currentPosition
                val next = queue.first()
                if (left in 1..25_000) {
                    nextView.text = "Next episode · S${next.season} E${next.episode}\nPress OK to play it now"
                    nextView.visibility = View.VISIBLE
                } else if (nextView.visibility == View.VISIBLE) nextView.visibility = View.GONE
            }
            if (++ticks % 15 == 0) saveProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private fun posKey() = "pos_" + (url.hashCode().toLong() and 0xffffffffL)

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressTick)
        saveProgress()
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

    private fun rememberLive() {
        ResumeStore.upsert(applicationContext, ResumeStore.Item(ResumeStore.keyOf("live", titleText), "live", titleText, null, 0, 0, url, null, 0, 0, System.currentTimeMillis(), liveId))
    }

    /** Remember where this film or episode is up to, for the Continue Watching rows. */
    private fun saveProgress() {
        val p = player ?: return
        if (live || p.duration <= 0) return
        val c = applicationContext
        val pos = (p.currentPosition / 1000).toInt(); val dur = (p.duration / 1000).toInt()
        val name = seriesTitle ?: titleText
        val kind = if (seriesTitle != null) "episode" else "film"
        val key = ResumeStore.keyOf(kind, name)
        val now = System.currentTimeMillis()
        if (dur - pos < 120) {
            val n = queue.firstOrNull()
            if (n != null && seriesTitle != null) ResumeStore.upsert(c, ResumeStore.Item(key, kind, name, seriesTitle, n.season, n.episode, n.url, image, 0, 0, now))
            else ResumeStore.remove(c, key)
        } else if (pos > 30) ResumeStore.upsert(c, ResumeStore.Item(key, kind, name, seriesTitle, curSeason, curEpisode, url, image, pos, dur, now))
    }

    private fun trackKey() = (seriesTitle ?: titleText).lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun showBanner(ms: Long = 3500) {
        banner.animate().cancel(); banner.alpha = 1f
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, ms)
    }
    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(600).start() }

    // ---------- Live: channel up / down / number ----------

    private fun tuneTo(ch: Xtream.Live) {
        val app = applicationContext
        liveId = ch.id
        url = Xtream.liveUrl(app, ch.id); altUrl = Xtream.liveUrl(app, ch.id, hls = true); triedAlt = false
        titleText = ch.name
        bannerTitle.text = ch.name
        rememberLive()
        bannerSub.text = LiveGuide.nowNext(app, ch.name).orEmpty()
        showBanner()
        start(url, resume = false)
    }

    private fun zap(delta: Int) {
        if (zapList.isEmpty()) return
        zapIndex = (zapIndex + delta + zapList.size) % zapList.size
        tuneTo(zapList[zapIndex])
    }

    private fun typeDigit(d: Int) {
        numberBuf = (numberBuf + d).takeLast(4)
        numberView.text = numberBuf; numberView.visibility = View.VISIBLE
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 1600)
    }
    private val commitNumber = Runnable { commitNumberNow() }
    private fun commitNumberNow() {
        handler.removeCallbacks(commitNumber)
        val n = numberBuf.toIntOrNull(); numberBuf = ""; numberView.visibility = View.GONE
        if (n == null) return
        val i = zapList.indexOfFirst { it.num == n }.takeIf { it >= 0 } ?: zapList.getOrNull(n - 1)?.let { n - 1 } ?: return GlassUi.toast(this, "No channel $n")
        zapIndex = i; tuneTo(zapList[i])
    }

    // ---------- Series: next episode ----------

    private fun startCountdown() {
        val next = queue.first()
        counting = true
        var left = 8
        fun tick() {
            if (!counting) return
            nextView.text = "Next episode · S${next.season} E${next.episode}\nPlaying in ${left}s   ·   OK to play now   ·   Back to stop"
            nextView.visibility = View.VISIBLE
            if (left-- <= 0) { playNext(); return }
            handler.postDelayed({ tick() }, 1000)
        }
        tick()
    }

    private fun playNext() {
        counting = false
        nextView.visibility = View.GONE
        val n = queue.removeFirstOrNull() ?: return finish()
        url = n.url
        bannerSub.text = "Season ${n.season}, Episode ${n.episode}"
        seriesTitle?.let { WatchHistory.record(applicationContext, it, n.season, n.episode) }
        curSeason = n.season; curEpisode = n.episode
        WatchHistory.forget(applicationContext, n.url)
        showBanner(4500)
        start(n.url, resume = false)
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (counting && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { handler.removeCallbacksAndMessages(null); playNext(); return true }
                KeyEvent.KEYCODE_BACK -> { counting = false; finish(); return true }
            }
            return true
        }
        if (!live && !counting && queue.isNotEmpty() && nextView.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) { playNext(); return true }
        if (live && event.action == KeyEvent.ACTION_DOWN && !optionsPill.isFocused) {
            val k = event.keyCode
            when {
                k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(k - KeyEvent.KEYCODE_0); return true }
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && numberBuf.isNotEmpty() -> { commitNumberNow(); return true }
            }
        }
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
            rememberTrack(C.TRACK_TYPE_TEXT, "off")
        }
        for (c in list) d.button(c.label + if (c.group.isTrackSelected(c.index)) "  ✓" else "") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index)).build()
            rememberTrack(type, c.group.getTrackFormat(c.index).language?.takeIf { it != "und" })
        }
        d.button("Back") { options() }
        d.show()
    }

    private fun rememberTrack(type: Int, language: String?) {
        val k = trackKey()
        if (type == C.TRACK_TYPE_TEXT) Prefs.setString(this, "trk_t_$k", language)
        else if (language != null) Prefs.setString(this, "trk_a_$k", language)
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
        const val EXTRA_LIVE_ID = "live_id"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SERIES = "series"
        const val EXTRA_Q_URLS = "q_urls"
        const val EXTRA_Q_S = "q_s"
        const val EXTRA_Q_E = "q_e"

        fun play(
            c: Context, url: String, title: String, live: Boolean, subtitle: String? = null, eyebrow: String? = null, alt: String? = null,
            fromStart: Boolean = false, liveId: Int = -1, series: String? = null, queue: List<Triple<String, Int, Int>> = emptyList(),
            image: String? = null, season: Int = 0, episode: Int = 0,
        ) {
            c.startActivity(Intent(c, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_ALT_URL, alt).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SUBTITLE, subtitle).putExtra(EXTRA_EYEBROW, eyebrow).putExtra(EXTRA_FROM_START, fromStart)
                .putExtra(EXTRA_LIVE_ID, liveId).putExtra(EXTRA_SERIES, series).putExtra(EXTRA_IMAGE, image)
                .putExtra(EXTRA_SEASON, season).putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_Q_URLS, queue.map { it.first }.toTypedArray())
                .putExtra(EXTRA_Q_S, queue.map { it.second }.toIntArray())
                .putExtra(EXTRA_Q_E, queue.map { it.third }.toIntArray())
                .addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
