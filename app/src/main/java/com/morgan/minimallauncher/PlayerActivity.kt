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
    private lateinit var skipView: android.widget.TextView
    /** Live TV pause/rewind: the saved stream, and where in it the player started reading. */
    private var ts: Timeshift? = null
    private var tsStart = 0L
    private var tsOffFor: String? = null
    private lateinit var tsView: android.widget.TextView
    private var okLong = false
    private var skipDismissed = false
    /** When "Skip Intro" appeared (it goes away by itself after a few seconds, like Netflix). */
    private var skipShownAt = 0L
    private var hideControlsOnStart = true
    /** A press we've already acted on: its key-up mustn't also pop up the controls. */
    private var swallowOkUp = false
    /** Sleep timer: when to stop (0 = off), or stop at the end of this episode. */
    private var sleepAt = 0L
    private var sleepAfterEpisode = false
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
    /** Netflix-style "You're watching" card shown while paused. */
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseEyebrow: android.widget.TextView
    private lateinit var pauseTitle: android.widget.TextView
    private lateinit var pauseEp: android.widget.TextView
    private lateinit var pausePlot: android.widget.TextView
    private var episodeList: List<Xtream.Episode>? = null
    private var episodeListLoading = false
    private val showPause = Runnable { showPauseInfo() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Activation.guard(this)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        altUrl = intent.getStringExtra(EXTRA_ALT_URL)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        view = PlayerView(this).apply {
            useController = !live
            controllerShowTimeoutMs = 3500
            controllerAutoShow = false          // the play/pause controls only appear when you ask for them
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
            // Controls gone while still paused: bring back the "You're watching" card.
            if (v != View.VISIBLE && player?.playWhenReady == false && player?.playbackState == Player.STATE_READY) {
                handler.removeCallbacks(showPause); handler.postDelayed(showPause, 300)
            }
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
        // Live TV: paused / behind live.
        tsView = GlassUi.text(this, "", 14f, R.font.inter_semibold).apply {
            background = GlassUi.panel(context, 16f, 0x50)
            setPadding(dp(context, 20f), dp(context, 11f), dp(context, 20f), dp(context, 11f))
            visibility = View.GONE
        }
        root.addView(tsView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(this@PlayerActivity, 56f) })
        // "Skip Intro" (episodes): bottom-left, OK skips.
        skipView = GlassUi.text(this, "Skip Intro  ›", 15f, R.font.inter_semibold).apply {
            background = GlassUi.panel(context, 18f, 0x60)
            setPadding(dp(context, 24f), dp(context, 14f), dp(context, 24f), dp(context, 14f))
            visibility = View.GONE
        }
        root.addView(skipView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(this@PlayerActivity, 48f), 0, 0, dp(this@PlayerActivity, 56f)) })
        // "You're watching": dark fade from the left with the title, episode and its story (only while paused).
        pauseInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
            setPadding(dp(context, 72f), 0, dp(context, 220f), 0)
            alpha = 0f; visibility = View.GONE
        }
        pauseEyebrow = GlassUi.text(this, "You’re watching", 15f, R.font.inter_medium, 0xB3FFFFFF.toInt())
        pauseTitle = GlassUi.text(this, "", 40f, R.font.inter_semibold).apply { maxLines = 2 }
        pauseEp = GlassUi.text(this, "", 19f, R.font.inter_semibold, 0xF2FFFFFF.toInt())
        pausePlot = GlassUi.text(this, "", 15f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { maxLines = 4; setLineSpacing(0f, 1.15f) }
        pauseInfo.addView(pauseEyebrow)
        pauseInfo.addView(pauseTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PlayerActivity, 6f) })
        pauseInfo.addView(pauseEp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@PlayerActivity, 12f) })
        pauseInfo.addView(pausePlot, LinearLayout.LayoutParams(dp(this@PlayerActivity, 520f), -2).apply { topMargin = dp(this@PlayerActivity, 10f) })
        root.addView(pauseInfo, 1, FrameLayout.LayoutParams(dp(this, 900f), -1, Gravity.START))
        setContentView(root)
        handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 4500)

        seriesTitle = intent.getStringExtra(EXTRA_SERIES)
        image = intent.getStringExtra(EXTRA_IMAGE)
        curSeason = intent.getIntExtra(EXTRA_SEASON, 0); curEpisode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val qu = intent.getStringArrayExtra(EXTRA_Q_URLS); val qs = intent.getIntArrayExtra(EXTRA_Q_S); val qe = intent.getIntArrayExtra(EXTRA_Q_E)
        if (qu != null && qs != null && qe != null) for (i in qu.indices) queue.addLast(Queued(qu[i], qs[i], qe[i]))
        Diag.send(applicationContext, "player", "open: \"$titleText\" series=$seriesTitle S$curSeason E$curEpisode queue=${queue.size} live=$live")
        // Opened without the list of following episodes (e.g. from a saved "up next" card)? Work it out.
        if (!live && seriesTitle != null && queue.isEmpty() && curSeason > 0) {
            val app0 = applicationContext; val name = seriesTitle!!; val s0 = curSeason; val e0 = curEpisode
            Thread {
                val eps = try { Xtream.findSeries(app0, name, 0)?.let { Xtream.episodes(app0, it) } } catch (e: Exception) { null }.orEmpty()
                val i = eps.indexOfFirst { it.season == s0 && it.number == e0 }
                if (i >= 0) runOnUiThread {
                    if (queue.isEmpty()) eps.drop(i + 1).forEach { queue.addLast(Queued(Xtream.episodeUrl(app0, it), it.season, it.number)) }
                }
            }.start()
        }
        liveId = intent.getIntExtra(EXTRA_LIVE_ID, -1)
        if (live && liveId >= 0) rememberLive()
        if (live && liveId >= 0) {
            val app = applicationContext
            Thread {
                val cat = intent.getStringExtra(EXTRA_LIVE_CAT)
                val group = (if (cat != null) try { Xtream.liveGroup(app, cat) } catch (e: Exception) { emptyList() } else {
                    val all = try { Xtream.live(app) } catch (e: Exception) { emptyList() }
                    val cur = all.firstOrNull { it.id == liveId }
                    if (cur == null) emptyList() else all.filter { it.category == cur.category }
                }).sortedBy { it.num }
                runOnUiThread { zapList = group; zapIndex = group.indexOfFirst { it.id == liveId }.coerceAtLeast(0) }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        watching = true
        start(url, resume = !live && !intent.getBooleanExtra(EXTRA_FROM_START, false))
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
    }

    private fun newPlayer(): ExoPlayer {
        // Start sooner than the defaults: a small buffer to begin with, then it fills while you watch.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (live) 2_000 else 15_000, if (live) 20_000 else 60_000, if (live) 500 else 900, if (live) 1_500 else 2_000)
            .build()
        // Files without a seek index (common with .ts/.mkv from providers) can still jump to a point.
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractors)
        val p = ExoPlayer.Builder(this).setLoadControl(load).setMediaSourceFactory(sources).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Pause/rewind couldn't keep up with this channel: play it straight from the internet instead.
                if (live && ts != null) { tsOffFor = url; ts?.stop(); ts = null; tsView.visibility = View.GONE; start(url, resume = false); return }
                // Some providers only serve live TV one way: try the other before giving up.
                val alt = altUrl
                if (!triedAlt && alt != null) { triedAlt = true; url = alt; start(alt, resume = false); return }
                val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                Diag.send(this@PlayerActivity, "player", "error: ${error.errorCodeName}${http?.let { " (HTTP $it)" }.orEmpty()} for \"$titleText\"")
                GlassUi.toast(this@PlayerActivity, when {
                    http == 466 || http == 403 || http == 401 -> "Dodgy Box turned this down - check the VPN is on, and that no other TV is watching Dodgy Box right now"
                    http == 404 -> "Dodgy Box doesn’t have this episode any more - try another"
                    http != null -> "Dodgy Box couldn’t play this right now (error $http) - try again in a minute"
                    else -> "This won’t play right now (${error.errorCodeName})"
                })
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // Skipped forward by hand early in an episode: that's probably where the intro ends - remember it for this series.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !live && seriesTitle != null && !skippingIntro &&
                    old.positionMs < 300_000 && new.positionMs - old.positionMs in 20_000..240_000) {
                    Prefs.setInt(this@PlayerActivity, introKey(), (new.positionMs / 1000).toInt())
                }
                skippingIntro = false
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(showPause)
                val pausedByYou = !isPlaying && player?.playWhenReady == false && player?.playbackState == Player.STATE_READY
                if (pausedByYou) handler.postDelayed(showPause, 1200) else hidePauseInfo()
                // A new episode (or film) has started: clear any controls left over from the last one.
                if (isPlaying && hideControlsOnStart) { hideControlsOnStart = false; view.hideController() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Live pause buffer filled up (or the stream ended): start a fresh one from live.
                if (state == Player.STATE_ENDED && live && ts != null) { ts?.stop(); ts = null; tsStart = 0L; start(url, resume = false); return }
                if (state == Player.STATE_ENDED && !live) {
                    Prefs.setInt(this@PlayerActivity, posKey(), 0)
                    if (sleepAfterEpisode) goToSleep()
                    else if (queue.isNotEmpty()) startCountdown() else finish()
                }
            }
        })
        return p
    }

    private fun start(u: String, resume: Boolean) {
        val p = player ?: newPlayer().also { player = it; view.player = it }   // changing channel reuses the player
        p.stop()
        val useTs = live && tsOffFor != u && Prefs.bool(this, "timeshift_on", true) && u.substringBefore('?').endsWith(".ts")
        if (useTs) {
            if (ts?.url != u) { ts?.stop(); ts = Timeshift(applicationContext, u).also { it.start() }; tsStart = 0L }
            val t = ts!!
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            p.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(t.factory(tsStart), extractors)
                .createMediaSource(MediaItem.fromUri("timeshift://$liveId/$tsStart")))
        } else {
            if (live) { ts?.stop(); ts = null }
            p.setMediaItem(MediaItem.fromUri(u))
        }
        // Pick up the subtitle and audio language you chose last time for this title.
        val key = trackKey()
        val tl = Prefs.string(this, ProfileManager.scope(this, "trk_t_$key")); val al = Prefs.string(this, ProfileManager.scope(this, "trk_a_$key"))
        if (tl != null || al != null) {
            val b = p.trackSelectionParameters.buildUpon()
            al?.let { b.setPreferredAudioLanguage(it) }
            if (tl == "off") b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) else if (tl != null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(tl)
            p.trackSelectionParameters = b.build()
        }
        val resumeAt = if (resume) Prefs.int(this, posKey(), 0).takeIf { it > 30 } else null
        resumeAt?.let { p.seekTo(it * 1000L) }
        p.playWhenReady = true
        p.prepare()
        // If it can't get going (often: it can't jump to where you left off), start from the beginning instead.
        val startedFor = u
        handler.postDelayed({
            val q = player ?: return@postDelayed
            if (url != startedFor || isFinishing || q.isPlaying || q.playerError != null) return@postDelayed
            if (q.playbackState == Player.STATE_BUFFERING || q.playbackState == Player.STATE_IDLE) {
                Diag.send(this, "player", "stuck: state=${q.playbackState} pos=${q.currentPosition / 1000}s dur=${q.duration / 1000}s resumeAt=${resumeAt ?: 0}s for \"$titleText\"")
                if (resumeAt != null && !live) {
                    GlassUi.toast(this, "Couldn’t pick up where you left off - starting from the beginning")
                    Prefs.setInt(this, posKey(), 0)
                    start(startedFor, resume = false)
                }
            }
        }, 15_000)
    }

    // Near the end of an episode, offer the next one (rather than only when the picture stops).
    private val progressTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !live && queue.isNotEmpty() && !counting && p.duration > 0) {
                val left = p.duration - p.currentPosition
                val next = queue.first()
                if (left in 1..25_000 && !nextDismissed) {
                    nextView.text = "Next episode · S${next.season} E${next.episode}\nPress OK to play it now"
                    nextView.visibility = View.VISIBLE
                } else if (left > 25_000) { nextDismissed = false; if (nextView.visibility == View.VISIBLE) nextView.visibility = View.GONE }
            }
            if (p != null && !live && seriesTitle != null) {
                val pos = p.currentPosition
                val end = introEnd()
                val inIntro = pos in 8_000 until minOf(end - 3_000, 240_000L)
                if (inIntro && !skipDismissed && skipShownAt == 0L) skipShownAt = System.currentTimeMillis()
                // Ignored for 12 seconds: it goes away (and stays away for this episode).
                if (skipShownAt > 0 && System.currentTimeMillis() - skipShownAt > 12_000L) skipDismissed = true
                val show = inIntro && !skipDismissed
                if (show && skipView.visibility != View.VISIBLE) { skipView.alpha = 0f; skipView.visibility = View.VISIBLE; skipView.animate().alpha(1f).setDuration(300).start() }
                else if (!show && skipView.visibility == View.VISIBLE) skipView.animate().alpha(0f).setDuration(400).withEndAction { skipView.visibility = View.GONE }.start()
            }
            if (++ticks % 15 == 0) saveProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private fun posKey() = WatchHistory.posKey(this, url)

    // Save your place as soon as you leave, before the screen behind refreshes its Continue Watching row.
    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onStop() {
        super.onStop()
        ts?.stop(); ts = null; tsStart = 0L
        watching = false
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
        skipDismissed = false; skipShownAt = 0L; skipView.visibility = View.GONE
        hideControlsOnStart = true
        view.hideController()
        val n = queue.removeFirstOrNull() ?: return finish()
        url = n.url
        bannerSub.text = "Season ${n.season}, Episode ${n.episode}"
        seriesTitle?.let { WatchHistory.record(applicationContext, it, n.season, n.episode) }
        curSeason = n.season; curEpisode = n.episode
        nextDismissed = false
        WatchHistory.forget(applicationContext, n.url)
        showBanner(4500)
        start(n.url, resume = false)
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
    }

    private var nextDismissed = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Paused with "You're watching" up: a press brings the controls back (and still does its job).
        if (event.action == KeyEvent.ACTION_DOWN && ::pauseInfo.isInitialized && pauseInfo.visibility == View.VISIBLE) {
            hidePauseInfo()
        }
        if (swallowOkUp && event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            swallowOkUp = false; return true
        }
        // Back: first clear the playback controls and pop-ups so the picture is clean; only then leave.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !counting) {
            val controls = !live && view.isControllerFullyVisible
            val pill = optionsPill.visibility == View.VISIBLE
            val card = nextView.visibility == View.VISIBLE
            if (skipView.visibility == View.VISIBLE && !controls && !pill && !card) {
                if (event.action == KeyEvent.ACTION_DOWN) { skipView.visibility = View.GONE; skipDismissed = true }
                return true
            }
            if (controls || pill || card) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (controls) view.hideController()
                    optionsPill.visibility = View.GONE
                    if (card) { nextView.visibility = View.GONE; nextDismissed = true }
                    view.requestFocus()
                }
                return true
            }
        }
        if (counting && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { handler.removeCallbacksAndMessages(null); swallowOkUp = true; playNext(); return true }
                KeyEvent.KEYCODE_BACK -> { counting = false; finish(); return true }
            }
            return true
        }
        if (!live && skipView.visibility == View.VISIBLE && !view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) { swallowOkUp = true; skipIntro(); return true }
        if (!live && !counting && queue.isNotEmpty() && nextView.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) { swallowOkUp = true; playNext(); return true }
        if (live && event.action == KeyEvent.ACTION_DOWN && !optionsPill.isFocused) {
            val k = event.keyCode
            when {
                k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(k - KeyEvent.KEYCODE_0); return true }
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && numberBuf.isNotEmpty() -> { commitNumberNow(); return true }
                ts != null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) -> { tsJump(-30); return true }
                ts != null && (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) -> { tsJump(30); return true }
                ts != null && (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY) -> { tsTogglePause(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO)) {
            options(); return true
        }
        // Live: OK pauses / plays (with pause and rewind on); hold OK for subtitles, audio and picture.
        if (live && !optionsPill.isFocused && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) okLong = false
                else if (event.repeatCount == 1 || ts == null && event.repeatCount == 0) { okLong = true; showLiveOptions() }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                if (!okLong) { if (ts != null) tsTogglePause() else showLiveOptions() }
                okLong = false
                return true
            }
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
        val d0 = GlassDialog(this).title("Playback")
        queue.firstOrNull()?.let { n -> if (!live) d0.button("Next episode · S${n.season} E${n.episode}", GlassDialog.Style.BOLD) { playNext() } }
        d0
            .button("Subtitles · ${selectedLabel(C.TRACK_TYPE_TEXT, "Off")}") { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles") }
            .button("Audio · ${selectedLabel(C.TRACK_TYPE_AUDIO, null)}") { pickTrack(C.TRACK_TYPE_AUDIO, "Audio") }
            .button("Picture · ${resizeName(view.resizeMode)}") { pickPicture() }
            .button("Speed · ${p.playbackParameters.speed}x") { pickSpeed() }
            .button("Sleep Timer · ${sleepLabel()}") { pickSleep() }
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
        if (type == C.TRACK_TYPE_TEXT) Prefs.setString(this, ProfileManager.scope(this, "trk_t_$k"), language)
        else if (language != null) Prefs.setString(this, ProfileManager.scope(this, "trk_a_$k"), language)
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

    // ---------- Live: pause and rewind ----------

    private fun showLiveOptions() {
        showBanner()
        optionsPill.visibility = View.VISIBLE; optionsPill.requestFocus()
        handler.postDelayed({ if (!optionsPill.isFocused) optionsPill.visibility = View.GONE }, 6000)
    }

    /** Where in the saved stream the picture is now. */
    private fun tsPlayingByte(): Long {
        val t = ts ?: return 0L
        val p = player ?: return tsStart
        return tsStart + p.currentPosition.coerceAtLeast(0) * t.bytesPerSecond / 1000L
    }

    private fun tsBehindSecs(): Long {
        val t = ts ?: return 0L
        return ((t.written - tsPlayingByte()) / t.bytesPerSecond).coerceAtLeast(0)
    }

    private fun tsRestartAt(byte: Long) {
        val t = ts ?: return
        tsStart = t.align(byte.coerceIn(t.oldest(), maxOf(t.oldest(), t.liveEdge())))
        val playing = player?.playWhenReady ?: true
        start(url, resume = false)
        player?.playWhenReady = playing
    }

    private fun tsJump(secs: Int) {
        val t = ts ?: return
        val target = tsPlayingByte() + secs * t.bytesPerSecond
        when {
            target >= t.liveEdge() -> { tsRestartAt(t.liveEdge()); player?.playWhenReady = true }
            target <= t.oldest() -> { tsRestartAt(t.oldest()); GlassUi.toast(this, "That’s as far back as it goes") }
            else -> tsRestartAt(target)
        }
        tsShowStatus()
    }

    private fun tsTogglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        tsShowStatus()
    }

    private fun clock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    /** "Paused" / "2:10 behind live", with what the buttons do. */
    private fun tsShowStatus() {
        val paused = player?.playWhenReady == false
        val behind = tsBehindSecs()
        tsView.text = when {
            paused -> "❚❚  Paused" + (if (behind > 5) "  ·  ${clock(behind)} behind live" else "") + "     OK play  ·  ◀ 30s  ·  ▶ 30s"
            behind > 8 -> "${clock(behind)} behind live     ◀ 30s  ·  ▶ 30s  ·  keep pressing ▶ to catch up"
            else -> "● LIVE     OK pause  ·  ◀ rewind 30s"
        }
        tsView.visibility = View.VISIBLE
        handler.removeCallbacks(hideTs)
        if (!paused) handler.postDelayed(hideTs, 4000)
    }
    private val hideTs = Runnable { if (player?.playWhenReady != false) tsView.visibility = View.GONE }

    // ---------- Skip intro ----------

    private var skippingIntro = false
    private fun introKey() = ProfileManager.scope(this, "intro_" + TextMatch.vodKey(seriesTitle.orEmpty()))
    /** Where the intro ends: learned from this series, otherwise a typical 1 min 20 s. */
    private fun introEnd(): Long = Prefs.int(this, introKey(), 0).takeIf { it > 0 }?.times(1000L) ?: 80_000L

    private fun skipIntro() {
        val p = player ?: return
        view.hideController()
        skippingIntro = true
        val learned = Prefs.int(this, introKey(), 0)
        p.seekTo(if (learned > 0) learned * 1000L else maxOf(p.currentPosition + 60_000L, 80_000L))
        skipView.visibility = View.GONE
        skipDismissed = true
    }

    // ---------- Sleep timer ----------

    private fun sleepLabel(): String = when {
        sleepAfterEpisode -> "End of this ${if (live) "programme" else "episode"}"
        sleepAt > 0 -> "${((sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)} min left"
        else -> "Off"
    }

    private fun pickSleep() {
        val d = GlassDialog(this).title("Sleep Timer").message("The TV stops and goes to sleep when the time's up.")
        fun set(mins: Int?, afterEpisode: Boolean = false) {
            sleepHandler.removeCallbacksAndMessages(null)
            sleepAfterEpisode = afterEpisode
            sleepAt = if (mins != null) System.currentTimeMillis() + mins * 60_000L else 0L
            if (mins != null) sleepHandler.postDelayed({ goToSleep() }, mins * 60_000L)
            GlassUi.toast(this, when { afterEpisode -> "Sleeping after this episode"; mins != null -> "Sleeping in $mins minutes"; else -> "Sleep timer off" })
        }
        if (!live) d.button("End of this episode") { set(null, afterEpisode = true) }
        for (m in listOf(15, 30, 45, 60, 90, 120)) d.button(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60.0} hours".replace(".0", "")) { set(m) }
        d.button("Off") { set(null) }
        d.button("Cancel").show()
    }

    /** Time's up: stop, save the place, and put the TV to sleep. */
    private fun goToSleep() {
        sleepHandler.removeCallbacksAndMessages(null)
        sleepAt = 0; sleepAfterEpisode = false
        saveProgress()
        player?.pause()
        HomeRedirectService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        finish()
    }

    private fun showPauseInfo() {
        val p = player ?: return
        if (p.playWhenReady) return
        val ep = if (curSeason > 0) episodeList?.firstOrNull { it.season == curSeason && it.number == curEpisode } else null
        pauseTitle.text = seriesTitle ?: titleText
        pauseEp.text = when {
            curSeason > 0 -> "Season $curSeason: Episode $curEpisode" + (ep?.title?.let { cleanEpTitle(it) }?.takeIf { it.isNotBlank() }?.let { "  “$it”" } ?: "")
            live -> bannerSub.text?.toString().orEmpty()
            else -> bannerSub.text?.toString().orEmpty()
        }
        pauseEp.visibility = if (pauseEp.text.isNullOrBlank()) View.GONE else View.VISIBLE
        pausePlot.text = ep?.plot.orEmpty()
        pausePlot.visibility = if (pausePlot.text.isNullOrBlank()) View.GONE else View.VISIBLE
        pauseEyebrow.text = if (live) "You’re watching live" else "You’re watching"
        if (pauseInfo.visibility != View.VISIBLE) {
            // Like Netflix: the picture with the title card, without the buttons on top; any press brings them back.
            view.hideController()
            pauseInfo.visibility = View.VISIBLE
            pauseInfo.translationX = -dp(this, 24f).toFloat()
            pauseInfo.animate().alpha(1f).translationX(0f).setDuration(350).start()
        }
        // First pause in a series: fetch the episode names and stories once, then fill them in.
        if (curSeason > 0 && episodeList == null && !episodeListLoading && seriesTitle != null) {
            episodeListLoading = true
            val app = applicationContext; val name = seriesTitle!!
            Bg.run {
                val eps = try { Xtream.findSeries(app, name, 0)?.let { Xtream.episodes(app, it) } } catch (e: Exception) { null }
                runOnUiThread {
                    episodeListLoading = false
                    episodeList = eps.orEmpty()
                    if (!isDestroyed && pauseInfo.visibility == View.VISIBLE) showPauseInfo()
                }
            }
        }
    }

    /** "Ghosts - S01E04 - The Thomas Thorne Affair" -> "The Thomas Thorne Affair"; plain "Episode 4" -> "". */
    private fun cleanEpTitle(t: String): String {
        val last = t.split(" - ").last().trim()
        return if (Regex("(?i)^(s\\d+\\s*e\\d+|episode\\s*\\d+|e\\d+)$").matches(last)) "" else last
    }

    private fun hidePauseInfo() {
        handler.removeCallbacks(showPause)
        if (!::pauseInfo.isInitialized || pauseInfo.visibility != View.VISIBLE) return
        pauseInfo.animate().alpha(0f).setDuration(200).withEndAction { pauseInfo.visibility = View.GONE }.start()
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** True while something is on screen in the player (updates wait until it closes). */
        @Volatile var watching = false
        const val EXTRA_URL = "url"
        const val EXTRA_ALT_URL = "alt"
        const val EXTRA_LIVE = "live"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "sub"
        const val EXTRA_EYEBROW = "eyebrow"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_LIVE_ID = "live_id"
        const val EXTRA_LIVE_CAT = "live_cat"
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
            image: String? = null, season: Int = 0, episode: Int = 0, liveCat: String? = null,
        ) {
            // Subscription run out: say so instead of trying (and failing) to play.
            val server = Xtream.server(c)
            if (server != null && url.startsWith(server) && Xtream.isExpired(c)) {
                (c as? android.app.Activity)?.let { a -> Xtream.showExpired(a) { play(c, url, title, live, subtitle, eyebrow, alt, fromStart, liveId, series, queue, image, season, episode, liveCat) } }
                    ?: android.widget.Toast.makeText(c, "Dodgy Box subscription expired", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            // On this TV Dodgy Box only works through the VPN: say so before it fails.
            if (server != null && url.startsWith(server) && Xtream.needsVpn(c) && !Xtream.vpnOn(c) && c is android.app.Activity) {
                val go = { playNow(c, url, title, live, subtitle, eyebrow, alt, fromStart, liveId, series, queue, image, season, episode, liveCat) }
                val vpnApp = vpnApp(c)
                val d = GlassDialog(c).title("Turn On the VPN").message("Dodgy Box only works through the VPN on this TV, and it’s off right now." +
                    (vpnApp?.let { "\n\nOpen ${it.second}, connect, then come back and press play." } ?: ""))
                vpnApp?.let { (pkg, label) -> d.button("Open $label", GlassDialog.Style.BOLD) {
                    c.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { i -> c.startActivity(i) } ?: c.packageManager.getLaunchIntentForPackage(pkg)?.let { i -> c.startActivity(i) }
                } }
                d.button("Play Anyway") { go() }.button("Cancel").show()
                return
            }
            playNow(c, url, title, live, subtitle, eyebrow, alt, fromStart, liveId, series, queue, image, season, episode, liveCat)
        }

        /** The VPN app on this TV, if there is one: (package, name). */
        fun vpnApp(c: Context): Pair<String, String>? = try {
            val pm = c.packageManager
            val known = listOf("com.surfshark.vpnclient.android", "com.nordvpn.android", "com.expressvpn.vpn", "ch.protonvpn.android", "com.ipvanish.ipvanish", "de.mobileconcepts.cyberghost", "com.privateinternetaccess.android")
            val pkg = known.firstOrNull { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
                ?: pm.getInstalledApplications(0).map { it.packageName }.firstOrNull { it.contains("vpn", true) && (pm.getLeanbackLaunchIntentForPackage(it) != null || pm.getLaunchIntentForPackage(it) != null) }
            pkg?.let { it to pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
        } catch (e: Exception) { null }

        private fun playNow(
            c: Context, url: String, title: String, live: Boolean, subtitle: String?, eyebrow: String?, alt: String?,
            fromStart: Boolean, liveId: Int, series: String?, queue: List<Triple<String, Int, Int>>,
            image: String?, season: Int, episode: Int, liveCat: String?,
        ) {
            c.startActivity(Intent(c, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_ALT_URL, alt).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SUBTITLE, subtitle).putExtra(EXTRA_EYEBROW, eyebrow).putExtra(EXTRA_FROM_START, fromStart)
                .putExtra(EXTRA_LIVE_ID, liveId).putExtra(EXTRA_LIVE_CAT, liveCat).putExtra(EXTRA_SERIES, series).putExtra(EXTRA_IMAGE, image)
                .putExtra(EXTRA_SEASON, season).putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_Q_URLS, queue.map { it.first }.toTypedArray())
                .putExtra(EXTRA_Q_S, queue.map { it.second }.toIntArray())
                .putExtra(EXTRA_Q_E, queue.map { it.third }.toIntArray())
                .addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
