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
import android.view.ViewGroup
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
    /** TV Colour Lights: the Magic Hue lights follow the picture while it plays (Settings › Magic Hue Lights). */
    private var colourSync: TvColourSync? = null
    private var droppedTotal = 0
    private var videoNote = ""
    /** 10-bit, HDR or 4K video: the lights read the picture more gently so the decoder never goes short. */
    private var heavyVideo = false
    private var tunnelled = false
    /** Big HEVC: the lights read the picture gently from the start. */
    private var bigHevc = false
    private var tenBitVideo = false
    /** Reading the picture cost this film frames: the lights follow the sound instead, for the rest of it. */
    private var pictureFirst = false
    private var lastDropCount = -1
    private val recentDrops = ArrayDeque<Pair<Long, Int>>()
    private var posterColour: IntArray? = null
    /** Dropped frames right after starting, skipping or resuming are the TV settling in - not the lights' fault. */
    private var settleUntil = 0L
    private var pictureFirstAt = 0L
    private var pictureRetryMs = 60_000L
    /** Talk to the Characters (pause, and the people on screen talk back) - and what's been said so far. */
    private var talk: CharacterTalk? = null
    private val transcript = ArrayList<String>()
    private var transcriptFor = ""
    /** A film: the room becomes the cinema (lights, quiet, house lights at the credits). */
    private var room: CinemaRoom? = null
    private var isFilm = false
    private var curtainShownAt = 0L
    private var houseLit = false
    private lateinit var banner: LinearLayout
    private lateinit var optionsPill: android.widget.TextView
    private lateinit var bannerTitle: android.widget.TextView
    private lateinit var bannerSub: android.widget.TextView
    private lateinit var numberView: android.widget.TextView
    private lateinit var nextView: android.widget.TextView
    private lateinit var skipView: android.widget.TextView
    private lateinit var jumpBadge: android.widget.TextView
    private var jumpTotal = 0L
    private var skipKeyUp = 0
    private var jumpAt = 0L
    private val hideJump = Runnable { jumpBadge.animate().alpha(0f).setDuration(220).withEndAction { jumpBadge.visibility = View.GONE; jumpTotal = 0 }.start() }
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
    private val triedCopies = HashSet<String>()
    private var triedResolved = false
    private var audioFallback = false
    private var audioSession = 0
    private var stalls = 0
    private var audioLoggedFor: String? = null
    private var usingResolved = false
    // Next episode loading behind the credits: its address, whether that went wrong this episode, and whether we're paused at the end.
    private var preloadFor: String? = null
    private var preloadFailed = false
    private var preloaded = false
    private var movingOn = false
    private var atItemEnd = false
    private var startedAt = 0L
    private var firstFrameLogged = false
    /** Netflix-style "You're watching" card shown while paused. */
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseEyebrow: android.widget.TextView
    private lateinit var pauseTitle: android.widget.TextView
    private lateinit var pauseEp: android.widget.TextView
    private lateinit var pausePlot: android.widget.TextView
    // InSight (Apple TV): who's in it, under the story when you pause.
    private lateinit var pauseCast: android.widget.TextView
    private var castFor: String? = null
    private var episodeList: List<Xtream.Episode>? = null
    private var episodeListLoading = false
    private val showPause = Runnable { showPauseInfo() }
    private var soundFx: SoundProfile.Effect? = null
    /** "Are you still watching?": episodes played on by themselves with no button pressed. */
    private var autoInARow = 0
    private var pressedThisEpisode = false
    /** Next-episode card shown during the credits (the picture shrinks into the corner). */
    private var creditsMode = false
    private var creditsCard: LinearLayout? = null
    private var creditsLine: android.widget.TextView? = null
    /** Episodes strip (Down while watching). */
    private var strip: android.widget.HorizontalScrollView? = null
    /** Scrub previews. */
    private var thumbView: android.widget.ImageView? = null
    private val thumbExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var thumbWant = -1L
    private var thumbRetriever: android.media.MediaMetadataRetriever? = null
    private var thumbFor: String? = null
    private var subsUntil = 0L
    // Auto Subtitles: the subtitles are loaded but hidden, and shown only while the voices are drowned out.
    private var autoSubsHidden = false
    private var subsChosenByYou = false
    // Our own subtitles (OurSubtitles): for files without any, drawn by the player itself.
    private var ourSubs: List<OurSubtitles.Line>? = null
    private var ourSubsFor = ""
    private var ourSubsOn = false
    private var ourOffset = 0L
    private var ourShown: OurSubtitles.Line? = null
    private val ourTick = object : Runnable {
        override fun run() {
            val lines = ourSubs ?: return
            val p = player ?: return
            val pos = p.currentPosition + ourOffset
            val now = lines.firstOrNull { pos in it.start..it.end }
            if (now !== ourShown) {
                ourShown = now
                view.subtitleView?.setCues(if (now == null) emptyList() else listOf(androidx.media3.common.text.Cue.Builder().setText(now.text).build()))
                if (now != null) {
                    val line = now.text.replace('\n', ' ')
                    if (transcript.lastOrNull()?.endsWith(line) != true) transcript += "[%d:%02d] %s".format(p.currentPosition / 60_000, (p.currentPosition / 1000) % 60, line)
                }
            }
            val buried = VirtualSurround.autoSubs && SoundEvents.buriedUntil > android.os.SystemClock.uptimeMillis()
            view.subtitleView?.visibility = if (ourSubsOn || buried || subsUntil > 0) View.VISIBLE else View.INVISIBLE
            handler.postDelayed(this, 100)
        }
    }

    /** The file has no subtitles: find ours (this TV, your library, then OpenSubtitles) - quietly, in the background. */
    private fun fetchOurSubs(force: Boolean = false) {
        if (live || (!force && !Prefs.bool(this, "our_subs", true))) return
        val key = "$url|$curSeason|$curEpisode"
        if (ourSubsFor == key && !force) return
        ourSubsFor = key
        val name = seriesTitle ?: titleText
        val year = intent.getStringExtra(EXTRA_SUBTITLE)?.trim()?.toIntOrNull() ?: Regex("\\((19|20)\\d\\d\\)").find(titleText)?.value?.trim('(', ')')?.toIntOrNull() ?: 0
        val season = if (seriesTitle != null) curSeason else 0; val ep = if (seriesTitle != null) curEpisode else 0
        val u = url; val app = applicationContext
        Bg.run {
            val lines = try { OurSubtitles.find(app, u, name, year, season, ep) } catch (e: Exception) { null }
            Diag.send(app, "player", "our subtitles for \"$name\"${if (season > 0) " S${season}E$ep" else ""}: ${if (lines != null) "${lines.size} lines, " else ""}${OurSubtitles.lastNote}")
            runOnUiThread {
                if (isDestroyed || ourSubsFor != key || lines == null) { if (force && lines == null) GlassUi.toast(this, "No subtitles found - ${OurSubtitles.lastNote}"); return@runOnUiThread }
                ourSubs = lines; ourShown = null
                val wanted = Prefs.string(this, ProfileManager.scope(this, "trk_t_${trackKey()}"))
                ourSubsOn = force || (wanted != null && wanted != "off")
                handler.removeCallbacks(ourTick); handler.post(ourTick)
                if (force) GlassUi.toast(this, "Subtitles on")
            }
        }
    }
    // Still watching?: late at night with nothing pressed for 90 minutes, check before fading out.
    private var lastKeyAt = android.os.SystemClock.uptimeMillis()
    private var sleepAsked = 0L
    private var sleepDialog: android.app.Dialog? = null

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
        optionsPill = GlassUi.text(this, if (live) "Subtitles · Audio · Sound" else "Info · Subtitles · Audio · Sound", 13f, R.font.inter_medium).apply {
            background = GlassUi.panel(context, 18f, 0x40)
            setPadding(dp(context, 18f), dp(context, 10f), dp(context, 18f), dp(context, 10f))
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { if (live) options() else showInfoPanel("subs") }
            setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.06f else 1f) }
        }
        root.addView(optionsPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(this@PlayerActivity, 36f), dp(this@PlayerActivity, 48f), 0) })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            optionsPill.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
            hideGear()
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
        // Quick skip with the controls hidden: just a small glass badge at the side ("10s »"), no menu (Apple TV / YouTube).
        jumpBadge = GlassUi.text(this, "", 20f, R.font.inter_semibold).apply {
            background = GlassUi.panel(context, 22f, 0x50)
            setPadding(dp(context, 22f), dp(context, 12f), dp(context, 22f), dp(context, 12f))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(jumpBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(dp(this@PlayerActivity, 64f), 0, dp(this@PlayerActivity, 64f), 0) })
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
        pauseCast = GlassUi.text(this, "", 14f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { maxLines = 3; setLineSpacing(0f, 1.2f); visibility = View.GONE }
        pauseInfo.addView(pauseCast, LinearLayout.LayoutParams(dp(this@PlayerActivity, 520f), -2).apply { topMargin = dp(this@PlayerActivity, 14f) })
        root.addView(pauseInfo, 1, FrameLayout.LayoutParams(dp(this, 900f), -1, Gravity.START))
        setContentView(root)
        if (!live) view.post { setUpScrubPreview(); styleControls() }
        applySubtitleStyle()
        handler.postDelayed({ banner.animate().alpha(0f).setDuration(600).start() }, 4500)

        seriesTitle = intent.getStringExtra(EXTRA_SERIES)
        image = intent.getStringExtra(EXTRA_IMAGE)
        // While it loads: the title's artwork as a card in the middle (over a dark, blurred copy), with its name
        // underneath - instead of a black screen. Fades into the picture.
        curtain = FrameLayout(this).apply {
            val c = this@PlayerActivity
            setBackgroundColor(0xFF000000.toInt())
            val back = android.widget.ImageView(c).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0.3f
                if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60f, 60f, android.graphics.Shader.TileMode.CLAMP))
            }
            addView(back, FrameLayout.LayoutParams(-1, -1))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val card = android.widget.ImageView(c).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 16f).toFloat(); setColor(0xFF1C1C1E.toInt()) }
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            // The show's wide artwork when we have it (a tall cover cropped into a wide card cut the faces off);
            // with only a tall poster, the card becomes poster-shaped instead.
            val art = Prefs.string(c, "rart_" + (seriesTitle ?: titleText)) ?: image
            art?.let { a ->
                ImageLoader.load(back, a, ImageLoader.HERO)
                ImageLoader.load(card, a, ImageLoader.HERO) { bmp ->
                    if (bmp != null && bmp.height > bmp.width) card.layoutParams = LinearLayout.LayoutParams(dp(c, 170f), dp(c, 255f))
                }
            }
            if (art != null) col.addView(card, LinearLayout.LayoutParams(dp(c, 400f), dp(c, 225f)))
            // A film: "Now Showing", like the moment the cinema lights go down.
            isFilm = !live && seriesTitle == null && intent.getStringExtra(EXTRA_EYEBROW) != "START OVER"
            if (isFilm) col.addView(GlassUi.text(c, "NOW SHOWING", 12f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.32f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 22f) })
            col.addView(GlassUi.text(c, (seriesTitle ?: titleText), if (isFilm) 30f else 24f, R.font.inter_semibold).apply { maxLines = 2; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, if (isFilm) 6f else 18f) })
            if (isFilm) {
                val year = Xtream.yearOf(titleText)?.toString().orEmpty()
                val line = AvBadges.withBadges(c, year, titleText)
                if (line.isNotBlank()) col.addView(GlassUi.text(c, "", 14f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { text = line; gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
                // The backdrop drifts slowly while it loads.
                back.animate().scaleX(1.08f).scaleY(1.08f).setDuration(9000).start()
            }
            intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }?.let {
                col.addView(GlassUi.text(c, it, 14f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
            }
            addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        root.addView(curtain, 1, FrameLayout.LayoutParams(-1, -1))
        curtainShownAt = android.os.SystemClock.uptimeMillis()
        // A film with the Magic Hue lights: the room starts turning into the cinema now, while the card is up.
        val roomIp = MagicHue.ip(this)
        if (isFilm && roomIp != null && CinemaRoom.enabled(this)) {
            val r = CinemaRoom(applicationContext, roomIp).also { room = it }
            val art = Prefs.string(this, "rart_$titleText") ?: image
            if (art == null) r.begin(null)
            else Bg.run { val b = try { ImageLoader.fetch(applicationContext, art, ImageLoader.ICON) } catch (_: Exception) { null }; runOnUiThread { if (room === r) r.begin(b) } }
        }
        handler.postDelayed({ curtain?.let { c -> curtain = null; (c.parent as? ViewGroup)?.removeView(c) } }, 20_000)   // never stuck over the screen
        // The artwork's main colour: what the lights hold when they follow the sound instead of the picture.
        if (!live) (Prefs.string(this, "rart_${seriesTitle ?: titleText}") ?: image)?.let { art ->
            Bg.run {
                val c = try { ImageLoader.fetch(applicationContext, art, ImageLoader.ICON)?.let { ColourEngine.dominant(it) } } catch (_: Exception) { null }
                runOnUiThread { posterColour = c }
            }
        }
        curSeason = intent.getIntExtra(EXTRA_SEASON, 0); curEpisode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val qu = intent.getStringArrayExtra(EXTRA_Q_URLS); val qs = intent.getIntArrayExtra(EXTRA_Q_S); val qe = intent.getIntArrayExtra(EXTRA_Q_E)
        if (qu != null && qs != null && qe != null) for (i in qu.indices) queue.addLast(Queued(qu[i], qs[i], qe[i]))
        Diag.send(applicationContext, "player", "open: \"$titleText\" series=$seriesTitle S$curSeason E$curEpisode queue=${queue.size} live=$live")
        // Opened without the list of following episodes (e.g. from a saved "up next" card)? Work it out.
        if (!live && seriesTitle != null && queue.isEmpty() && curSeason > 0) {
            val app0 = applicationContext; val name = seriesTitle!!; val s0 = curSeason; val e0 = curEpisode
            Thread {
                val eps = try { Xtream.seriesEpisodes(app0, name, 0)?.second } catch (e: Exception) { null }.orEmpty()
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
                // Channel up/down in the guide's order (1 BBC One, 2 BBC Two, 3 ITV1 ...) when this channel is one of those.
                val guide = try { Xtream.ukGuideList(app) } catch (e: Exception) { emptyList() }
                val curName = TextMatch.normalise(Xtream.displayName(titleText))
                val inGuide = guide.indexOfFirst { it.id == liveId }.takeIf { it >= 0 }
                    ?: guide.indexOfFirst { TextMatch.normalise(it.name) == curName }.takeIf { it >= 0 }
                runOnUiThread {
                    if (inGuide != null) { zapList = guide; zapIndex = inGuide }
                    else { zapList = group; zapIndex = group.indexOfFirst { it.id == liveId }.coerceAtLeast(0) }
                }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        current = java.lang.ref.WeakReference(this)
        watching = true
        // Wake the soundbar while the stream loads, so it has locked on before the sound starts (PCM modes only).
        SoundbarWake.hold(this, "player")
        start(url, resume = !live && !intent.getBooleanExtra(EXTRA_FROM_START, false))
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
    }

    private fun newPlayer(): ExoPlayer {
        // Start sooner than the defaults: a small buffer to begin with, then it fills while you watch.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            // Films and shows: start once 3 s is in hand, carry on after a stall once 6 s is, and keep up to 2 minutes
            // ahead - big files over Starlink + VPN otherwise ran the buffer dry and stuttered stop-start.
            .setBufferDurationsMs(if (live) 4_000 else 30_000, if (live) 30_000 else 60_000, if (live) 900 else 2_500, if (live) 3_000 else 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Files without a seek index (common with .ts/.mkv from providers) can still jump to a point.
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractors)
        // Audio Format (Settings → Video and Audio): Auto lets Dolby go straight to the soundbar; the others
        // decode on the TV (so Sound Check, Enhance Dialogue and Reduce Loud Sounds work on everything).
        val format = if (audioFallback) "auto" else (Prefs.string(this, "audio_format") ?: "auto")
        // Auto: Dolby goes straight to the soundbar (full surround - decoding it here gave stereo and the sound profile
        // can't attach to it anyway); everything else is decoded here, where the sound profile shapes it.
        decodingAudio = format != "auto"
        virtualMode = format != "auto"      // every decoded mode goes through our own mixer (it does the sound profile too)
        VirtualSurround.settings = SoundProfile.get(this)
        VirtualSurround.tvSpeakers = Prefs.string(this, "speakers") == "tv"
        val builder = ExoPlayer.Builder(this).setLoadControl(load).setMediaSourceFactory(sources)
        if (decodingAudio) builder.setRenderersFactory(object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            // The TV's own decoders first (they handle AAC, MPEG and live TV fine); the app's FFmpeg decoder takes
            // over for what they can't - Dolby Digital Plus - so it's decoded here, into our mixer.
            init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) }
            @Suppress("DEPRECATION")
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                // No Context: with one, the sink reads the TV's real abilities and quietly passes Dolby through
                // (bypassing the mixer, and making the soundbar switch modes and swallow the start).
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .apply { setAudioProcessors(arrayOf(if (format == "virtual") VirtualSurround.mixer() else VirtualSurround.stereo())) }
                    .build()
        })
        val p = builder.build()
        if (format == "stereo") p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxAudioChannelCount(2).build()
        // Our own sound session, so the sound profile (Sound Check, Dialogue Boost, Even Volume) can shape it.
        try {
            val sid = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
            p.setAudioSessionId(sid)
            soundFx?.release(); soundFx = null
            audioSession = sid
            fxFor = null
        } catch (e: Throwable) {}
        // Timing of the sound starting (for the TV's log): which decoder, how long it took, and when real sound came out.
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            // Jerky picture: how many frames the decoder couldn't show in time (and whether the lights were reading).
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                droppedTotal += droppedFrames
                if (droppedFrames >= 5) Diag.send(this@PlayerActivity, "player", "dropped $droppedFrames frames in ${elapsedMs} ms (total $droppedTotal) on \"$titleText\" · ${videoNote} · lights=${colourSync != null}")
            }
            override fun onVideoInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                videoNote = "${format.width}x${format.height} ${format.sampleMimeType} ${format.codecs ?: ""} ${format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: ""}".trim()
                heavyVideo = format.width > 1920 || (format.codecs ?: "").startsWith("hvc1.2") || (format.codecs ?: "").startsWith("hev1.2") || format.colorInfo?.colorTransfer.let { it == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || it == androidx.media3.common.C.COLOR_TRANSFER_HLG }
                val codecs = format.codecs ?: ""
                bigHevc = (codecs.startsWith("hvc1") || codecs.startsWith("hev1") || format.sampleMimeType == "video/hevc") && format.width >= 1900
                if (heavyVideo || bigHevc) colourSync?.gentle = true
                // 10-bit / HDR on this Chromecast: the decoder copes, but converting every frame for the screen
                // through the graphics chip doesn't (~7 dropped frames a second). Tunnelled playback hands the frames
                // straight to the TV's video layer instead. The picture can't be read then, so the lights hold the
                // film's colour (and 4D still reacts to the sound).
                // Only big 10-bit (1080p and up), HDR or 4K needs it - a 720p 10-bit film plays fine normally, so the lights
                // can still follow its picture (and the frame guard below tunnels it later if it ever can't keep up).
                val tenBit = codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2")
                tenBitVideo = tenBit
                colourSync?.slowRead = tenBit
                // Any 10-bit file: even read 2-3 times a second for the lights it dropped a frame every few seconds
                // (Percy Jackson, 720p) - the picture comes first, so it plays tunnelled and the lights follow the sound.
                val needsTunnel = format.width > 1920 || tenBit ||
                    format.colorInfo?.colorTransfer.let { it == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || it == androidx.media3.common.C.COLOR_TRANSFER_HLG }
                if (needsTunnel && !tunnelled) tunnelNow("heavy video ($videoNote)")
            }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Diag.send(this@PlayerActivity, "player", "audio decoder $decoderName ready in ${initializationDurationMs} ms · \"$titleText\"")
            }
        })
        val name0 = titleText
        VirtualSurround.onFirstSound = { ms -> Diag.send(applicationContext, "player", "first sound after ${ms} ms of decoded audio · \"$name0\"") }
        p.addListener(object : Player.Listener {
            // Every line of dialogue as it's shown (when the file has subtitles), so the characters know what's been said.
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                val key = "$titleText|$curSeason|$curEpisode"
                if (key != transcriptFor) { transcriptFor = key; transcript.clear() }
                val line = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString(" ").replace('\n', ' ')
                if (line.isBlank() || transcript.lastOrNull()?.endsWith(line) == true) return
                val pos = player?.currentPosition ?: 0L
                transcript += "[%d:%02d] %s".format(pos / 60_000, (pos / 1000) % 60, line)
                if (transcript.size > 600) transcript.subList(0, transcript.size - 600).clear()
            }
            override fun onTracksChanged(tracks: Tracks) {
                // Match the TV's refresh rate to the film's frame rate (Apple TV's "Match Content"): smooth pans, no judder.
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g ->
                    (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let {
                        val fr = g.getTrackFormat(it).frameRate
                        // Many files don't say their frame rate: measure it from the frames themselves instead.
                        if (fr > 0f) matchFrameRate(fr) else measureFrameRate()
                    }
                }
                autoSubtitles(tracks)
                // A new film or episode: the last one's subtitles go.
                if (ourSubs != null && ourSubsFor != "$url|$curSeason|$curEpisode") { ourSubs = null; ourShown = null; handler.removeCallbacks(ourTick); view.subtitleView?.setCues(emptyList()) }
                if (!live && tracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) fetchOurSubs()
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isEmpty()) return
                val selected = audio.firstOrNull { it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                // Remember what this title actually came in (4K / HDR / Dolby / 5.1) for its badges in Watch.
                if (!live) {
                    val vf = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                    AvBadges.fromPlayer(vf, selected)?.let { AvBadges.save(applicationContext, seriesTitle ?: titleText, it) }
                }
                val anyPlayable = audio.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }
                // What this programme's sound is (in the TV's log), once per programme.
                if (audioLoggedFor != url) {
                    audioLoggedFor = url
                    Diag.send(this@PlayerActivity, "player", "audio for \"$titleText\": " + audio.joinToString(" | ") { g -> (0 until g.length).joinToString(", ") { i ->
                        val f = g.getTrackFormat(i); "${f.sampleMimeType} ${f.channelCount}ch ${f.language ?: ""} ${if (g.isTrackSupported(i)) "ok" else "UNSUPPORTED"}${if (g.isTrackSelected(i)) " *" else ""}" } } +
                        " · format=${Prefs.string(this@PlayerActivity, "audio_format") ?: "auto"} settings=${if (SoundProfile.get(this@PlayerActivity).flat) "off" else "on"} · ${passFormats()}")
                }
                // The sound can't be played with the TV decoding it (e.g. Dolby Digital Plus): let it go to the TV/soundbar as it is.
                if (!anyPlayable && !audioFallback) {
                    audioFallback = true
                    Diag.send(this@PlayerActivity, "player", "audio: nothing playable - retrying with the sound passed straight through")
                    saveProgress()
                    player?.release(); player = null
                    start(url, resume = true)
                    return
                }
                if (!anyPlayable) GlassUi.toast(this@PlayerActivity, "This programme’s sound isn’t in a format the TV can play")
                // Surround sound (5.1 / 7.1) or sound passed straight through: the sound-profile effect is stereo-only and can
                // silence it, so it steps aside for this programme.
                // Sound passed straight to the soundbar (Dolby the TV can't decode): nothing can shape it.
                val mime = selected?.sampleMimeType.orEmpty()
                passthroughNow = audioFallback || (!decodingAudio && (mime.contains("ac3") || mime.contains("dts") || mime.contains("truehd") || mime.contains("ac4")))
                if (passthroughNow) { soundFx?.release(); soundFx = null }
            }
            override fun onRenderedFirstFrame() {
                if (player?.isPlaying == true || player?.playWhenReady == true) startColourSync()
                curtain?.let { c ->
                    curtain = null
                    // A film's "Now Showing" stays up at least 4 seconds - long enough for the room to change.
                    val wait = if (isFilm) (4000 - (android.os.SystemClock.uptimeMillis() - curtainShownAt)).coerceAtLeast(0) else 0L
                    c.animate().alpha(0f).setStartDelay(wait).setDuration(if (isFilm) 900 else 450).withEndAction { (c.parent as? ViewGroup)?.removeView(c) }.start()
                }
                // How long from pressing play to the picture (in the TV's timing log).
                if (firstFrameLogged) return
                firstFrameLogged = true
                val ms = Perf.now() - startedAt
                Perf.record("Player: first picture ${ms} ms" + (if (preloaded) " (loaded during credits)" else if (usingResolved) " (ready connection)" else "") + " · $titleText")
                preloaded = false
            }
            override fun onPlayerError(error: PlaybackException) {
                // Loading the next episode early went wrong: drop it and carry on with this one the usual way.
                player?.let { q -> if (preloadFor != null && q.mediaItemCount > 1 && q.currentMediaItemIndex == 0) {
                    Diag.send(this@PlayerActivity, "player", "next-episode preload failed (${error.errorCodeName}) - carrying on without it")
                    preloadFailed = true; preloadFor = null
                    q.removeMediaItem(1); q.prepare(); q.play(); return
                } }
                // The ready-made connection didn't work: try the normal address once.
                if (usingResolved) { triedResolved = true; usingResolved = false; start(url, resume = true); return }
                // Pause/rewind couldn't keep up with this channel: play it straight from the internet instead.
                if (live && ts != null) { stopLiveRecording(); tsOffFor = url; ts?.stop(); ts = null; tsView.visibility = View.GONE; start(url, resume = false); return }
                // Some providers only serve live TV one way: try the other before giving up.
                val alt = altUrl
                if (!triedAlt && alt != null) { triedAlt = true; url = alt; start(alt, resume = false); return }
                val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                val ext = url.substringBefore('?').substringAfterLast('.', "?").take(5)
                Diag.send(this@PlayerActivity, "player", "error: ${error.errorCodeName}${http?.let { " (HTTP $it)" }.orEmpty()} [.$ext] for \"$titleText\" S${curSeason}E${curEpisode}")
                // An episode file this player can't read (or a broken upload): Dodgy Box often has the show more than once
                // (other languages, HD/SD, older and newer uploads) - play the same episode from another copy.
                if (!live && seriesTitle != null && curSeason > 0 && http == null) { triedCopies += url; if (tryOtherCopy()) return }
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
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { startWatch?.let { handler.removeCallbacks(it) }; settleUntil = android.os.SystemClock.uptimeMillis() + 10_000L }
                if (reason == Player.DISCONTINUITY_REASON_SEEK && atItemEnd && player?.currentMediaItemIndex == 0) atItemEnd = false   // went back into it
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // The sound has started: set the sound profile up now, when the real channel layout (stereo, 5.1…) is known.
                if (isPlaying && fxFor != url) { fxFor = url; startSoundFx() }
                val pausedByYou = !isPlaying && !atItemEnd && scrubWasPlaying == null && player?.playWhenReady == false && player?.playbackState == Player.STATE_READY
                if (isPlaying) startColourSync() else colourSync?.pause(glow = pausedByYou && !houseLit)
                handler.removeCallbacks(showPause)
                if (pausedByYou) handler.postDelayed(showPause, 1200) else hidePauseInfo()
                // A new episode (or film) has started: clear any controls left over from the last one.
                if (isPlaying && hideControlsOnStart) { hideControlsOnStart = false; view.hideController() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Live picture stalling (for the TV's log): count it, and note which channel.
                if (state == Player.STATE_BUFFERING && firstFrameLogged) {
                    stalls++
                    val speed = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this@PlayerActivity).bitrateEstimate / 1000
                    if (stalls == 1 || stalls % 3 == 0) Diag.send(this@PlayerActivity, "player", "stall #$stalls on \"$titleText\" at ${(player?.currentPosition ?: 0) / 1000}s (live=$live, timeshift=${ts != null}, buffered ${((player?.bufferedPosition ?: 0) - (player?.currentPosition ?: 0)) / 1000}s ahead, download ~$speed kbps, video ${videoNote}, lights=${colourSync != null})")
                }
                // Live pause buffer filled up (or the stream ended): start a fresh one from live.
                if (state == Player.STATE_ENDED && live && ts != null) { ts?.stop(); ts = null; tsStart = 0L; start(url, resume = false); return }
                if (state == Player.STATE_ENDED && !live) episodeEnded()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // It moved on to the waiting episode by itself (e.g. Play pressed at the very end): catch up.
                if (!live && !movingOn && preloadFor != null && player?.currentMediaItemIndex == 1) playNext()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Paused at the end of the episode because the next one is waiting in the same player.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && !live) {
                    handler.removeCallbacks(showPause)
                    episodeEnded()
                }
            }
        })
        return p
    }

    private fun episodeEnded() {
        if (atItemEnd) return
        atItemEnd = true
        Prefs.setInt(this, posKey(), 0)
        if (sleepAfterEpisode) goToSleep()
        else if (queue.isNotEmpty()) startCountdown() else askRating { finish() }
    }

    /** Near the end: get the next episode's address ready, then let the player load it once this one is fully loaded. */
    private fun prepareNext(left: Long) {
        val p = player ?: return
        val n = queue.firstOrNull() ?: return
        if (live || !n.url.startsWith("http")) return
        if (left < 180_000) Xtream.preResolve(n.url)
        if (left < 240_000 && preloadFor == null && !preloadFailed && p.mediaItemCount == 1) {
            preloadFor = n.url
            p.pauseAtEndOfMediaItems = true
            p.addMediaItem(MediaItem.fromUri(n.url))
        }
    }

    private fun start(u: String, resume: Boolean) {
        atItemEnd = false; preloadFor = null; preloadFailed = false
        liveChannel = if (live) titleText else null
        val p = player ?: newPlayer().also { player = it; view.player = it }   // changing channel reuses the player
        p.stop()
        if (u != lastStartUrl) { lastStartUrl = u; freshTitle(p) }
        val useTs = live && tsOffFor != u && Prefs.bool(this, "timeshift_on", true) && u.substringBefore('?').endsWith(".ts")
        if (useTs) {
            if (ts?.url != u) { ts?.stop(); ts = Timeshift(applicationContext, u).also { it.start() }; tsStart = 0L }
            val t = ts!!
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            p.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(t.factory(tsStart), extractors)
                .createMediaSource(MediaItem.fromUri("timeshift://$liveId/$tsStart")))
        } else {
            if (live) { ts?.stop(); ts = null }
            val direct = if (!live && !triedResolved) Xtream.resolved(u) else null
            usingResolved = direct != null
            p.setMediaItem(MediaItem.fromUri(direct ?: u))
        }
        startedAt = Perf.now(); firstFrameLogged = false
        // Pick up the subtitle and audio language you chose last time for this title.
        val key = trackKey()
        val tl = Prefs.string(this, ProfileManager.scope(this, "trk_t_$key"))
        // Audio Language: what you picked for this title, else your default (Settings → Video and Audio).
        val al = Prefs.string(this, ProfileManager.scope(this, "trk_a_$key")) ?: Prefs.string(this, "audio_lang")?.takeIf { it != "auto" }
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
        startWatch?.let { handler.removeCallbacks(it) }
        val watch = Runnable {
            val q = player ?: return@Runnable
            if (url != startedFor || isFinishing || q.isPlaying || q.playerError != null) return@Runnable
            if (q.playbackState == Player.STATE_BUFFERING || q.playbackState == Player.STATE_IDLE) {
                Diag.send(this, "player", "stuck: state=${q.playbackState} pos=${q.currentPosition / 1000}s dur=${q.duration / 1000}s resumeAt=${resumeAt ?: 0}s retried=$startRetried for \"$titleText\"")
                // First time: often Dodgy Box still counting the last connection - one quiet fresh try usually gets it going.
                if (!startRetried && !firstFrameLogged) {
                    startRetried = true
                    q.stop(); q.prepare(); q.playWhenReady = true
                    handler.postDelayed(this@PlayerActivity.startWatch ?: return@Runnable, 9_000)
                    return@Runnable
                }
                if (resumeAt != null && !live) {
                    GlassUi.toast(this, "Couldn’t pick up where you left off - starting from the beginning")
                    Prefs.setInt(this, posKey(), 0)
                    start(startedFor, resume = false)
                }
            }
        }
        startWatch = watch
        startRetried = false
        handler.postDelayed(watch, 8_000)
    }

    // The "did it get going?" check after starting. Rewinding or skipping cancels it: a seek that's still loading
    // isn't a failed resume (it used to send you back to the beginning).
    private var startWatch: Runnable? = null
    private var startRetried = false
    private var curtain: FrameLayout? = null

    /** Leaving the player shrinks it away, like closing an app. */
    override fun finish() {
        // Let go of Dodgy Box at once (it allows one connection): what you open next mustn't wait for this to close.
        handOffRecording()
        ts?.stop(); ts = null
        try { player?.stop() } catch (_: Throwable) {}
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.screen_back_in, R.anim.player_out)
    }

    // Near the end of an episode, offer the next one (rather than only when the picture stops).
    private val progressTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !live && queue.isNotEmpty() && !counting && p.duration > 0) {
                val left = p.duration - p.currentPosition
                prepareNext(left)
                val window = creditsWindow()
                if (left in 1..window && !nextDismissed && p.isPlaying) enterCredits(left)
                else if (left > window) { nextDismissed = false; if (creditsMode) restoreFromCredits() }
                if (creditsMode) creditsLine?.text = "Next episode in ${(left / 1000).coerceAtLeast(0)}s"
            }
            // A film's credits: the house lights come up (and go down again if you go back into the film).
            if (p != null && isFilm && room != null && p.duration > 0) {
                val left = p.duration - p.currentPosition
                val window = minOf(6 * 60_000L, p.duration / 12)
                if (!houseLit && left in 1..window) { houseLit = true; colourSync?.pause(); room?.houseLights() }
                else if (houseLit && left > window + 60_000L) { houseLit = false; startColourSync() }
            }
            if (subsUntil > 0 && System.currentTimeMillis() > subsUntil) endTempSubs()
            if (autoSubsHidden && ourSubs == null) view.subtitleView?.visibility =
                if (SoundEvents.buriedUntil > android.os.SystemClock.uptimeMillis() || subsUntil > 0) View.VISIBLE else View.INVISIBLE
            if (p != null && p.isPlaying && sleepAsked == 0L && Prefs.bool(this@PlayerActivity, "sleep_check", true)) {
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if ((h >= 23 || h < 6) && android.os.SystemClock.uptimeMillis() - lastKeyAt > 90 * 60_000L) askIfAsleep()
            }
            if (p != null && !live && seriesTitle != null) {
                val pos = p.currentPosition
                skipView.text = if (curEpisode > 1 && pos < 60_000L) "Skip Recap  ›" else "Skip Intro  ›"
                val end = introEnd()
                val inIntro = pos in 8_000 until minOf(end - 3_000, 240_000L)
                if (inIntro && !skipDismissed && skipShownAt == 0L) skipShownAt = System.currentTimeMillis()
                // Ignored for 12 seconds: it goes away (and stays away for this episode).
                if (skipShownAt > 0 && System.currentTimeMillis() - skipShownAt > 12_000L) skipDismissed = true
                val show = inIntro && !skipDismissed
                if (show && skipView.visibility != View.VISIBLE) { skipView.alpha = 0f; skipView.visibility = View.VISIBLE; skipView.animate().alpha(1f).setDuration(300).start() }
                else if (!show && skipView.visibility == View.VISIBLE) skipView.animate().alpha(0f).setDuration(400).withEndAction { skipView.visibility = View.GONE }.start()
            }
            // Picture first: if reading the picture for the lights costs the film frames, read it more gently; if it
            // still does, stop reading and let the lights follow the sound for the rest of this film.
            val cs = colourSync
            val nowT = android.os.SystemClock.uptimeMillis()
            if (p != null && !p.isPlaying) settleUntil = nowT + 10_000L
            if (p != null && !tunnelled && p.isPlaying && ticks > 8) {
                val dcnt = p.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount } ?: -1
                if (dcnt >= 0 && nowT < settleUntil) { lastDropCount = dcnt; recentDrops.clear() }
                else if (dcnt >= 0) {
                    val d = if (lastDropCount in 0..dcnt) dcnt - lastDropCount else 0
                    lastDropCount = dcnt
                    val now = android.os.SystemClock.uptimeMillis()
                    if (d > 0) recentDrops.addLast(now to d)
                    while (recentDrops.isNotEmpty() && now - recentDrops.first().first > 10_000L) recentDrops.removeFirst()
                    // Reading slowly already: a dropped frame now and then is the TV, not the lights - only act on a lot.
                    if (recentDrops.sumOf { it.second } >= (if (cs?.slowRead == true) 12 else 6)) {
                        recentDrops.clear()
                        // Only ever reads the picture less often - never changes mode or playback mid-film.
                        if (cs != null && !cs.soundOnly) {
                            if (!cs.gentle) { cs.gentle = true; Diag.send(this@PlayerActivity, "player", "picture first: frames dropped on \"$titleText\" - lights read ~12 times a second") }
                            else if (!cs.slowRead) { cs.slowRead = true; Diag.send(this@PlayerActivity, "player", "picture first: still dropping on \"$titleText\" - lights read ~2½ times a second") }
                        }
                    }
                }
            }
            if (++ticks % 15 == 0) saveProgress()
            // Let the other TV know what's on here (for its "On the other TV" corner).
            if ((ticks == 5 || ticks % 180 == 0) && player?.isPlaying == true) {
                val app = applicationContext
                val t = seriesTitle ?: titleText
                val sub = if (curSeason > 0) "Season $curSeason, Episode $curEpisode" else if (live) bannerSub.text?.toString() else null
                Bg.run { Sync.shareNow(app, t, sub) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun posKey() = WatchHistory.posKey(this, url)

    // Save your place as soon as you leave, before the screen behind refreshes its Continue Watching row.
    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    private var lastStartUrl: String? = null
    private var stillColours: List<IntArray>? = null

    /** A new film, episode or channel: nothing about the last one carries over (tunnelling, lights mode, guard). */
    private fun freshTitle(p: ExoPlayer) {
        if (tunnelled) (p.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)?.let { ts ->
            ts.setParameters(ts.buildUponParameters().setTunnelingEnabled(false))
        }
        tunnelled = false; heavyVideo = false; bigHevc = false; tenBitVideo = false; stillColours = null
        pictureFirst = false; pictureRetryMs = 60_000L; recentDrops.clear(); lastDropCount = -1
        settleUntil = android.os.SystemClock.uptimeMillis() + 10_000L
        SoundEvents.loudness = 0f
        colourSync?.let { cs -> cs.gentle = false; cs.slowRead = false; if (cs.soundOnly) cs.backToPicture() }
    }

    /** Hand the video straight to the TV's video layer (no frame conversion): smooth heavy video, but the picture can
     *  no longer be read, so the lights follow the soundtrack in the film's colour. */
    private fun tunnelNow(why: String) {
        if (tunnelled) return
        tunnelled = true
        (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)?.let { ts ->
            ts.setParameters(ts.buildUponParameters().setTunnelingEnabled(true))
        }
        colourSync?.followSound(null)
        if (colourSync == null) room?.hold()
        Diag.send(this, "player", "$why: tunnelled playback on, lights glow warm white with the soundtrack")
    }

    /** The film's own stills (from the provider), each turned into its scene colour, for the lights to move between. */
    private fun loadStills() {
        val name = seriesTitle ?: titleText
        val year = intent.getStringExtra(EXTRA_SUBTITLE)?.trim()?.toIntOrNull() ?: 0
        val show = seriesTitle != null
        val app = applicationContext
        Bg.run {
            val urls = try { Xtream.stills(app, name, year, show) } catch (e: Exception) { emptyList() }
            val colours = urls.mapNotNull { u -> try { ImageLoader.fetch(app, u, ImageLoader.ICON)?.let { ColourEngine.dominant(it) } } catch (e: Exception) { null } }
            Diag.send(app, "lights", "stills for \"$name\": ${colours.size} of ${urls.size} (${colours.joinToString(" ") { it.joinToString(",") }})")
            runOnUiThread { if (!isDestroyed && tunnelled) { stillColours = colours; colourSync?.stills(colours) } }
        }
    }

    private fun startColourSync() {
        val ip = MagicHue.ip(this)
        val r = room
        if (houseLit) return
        // A film without TV Colour Lights: a dim glow in the film's colour stays for the film.
        if (r != null && !MagicHue.syncOn(this)) { r.hold(); return }
        if (!MagicHue.syncOn(this) || ip == null || !MagicHue.allowed(this)) { if (tunnelled) r?.hold(); return }
        val sv = view.videoSurfaceView as? android.view.SurfaceView
        if (sv == null) { Diag.send(this, "lights", "TV Colour Lights: no SurfaceView (${view.videoSurfaceView?.javaClass?.simpleName})"); return }
        if (colourSync == null) Diag.send(this, "lights", "TV Colour Lights starting → $ip")
        r?.handOver()
        // 4D Mode: the room reacts to what it hears - explosions, gunshots, jump scares. (When the lights follow the
        // sound, the soundtrack is listened to even with 4D Mode off - its loudness drives them.)
        val fourD = !live && Prefs.bool(this, "fourd_mode", true)
        SoundEvents.listener = if (fourD || tunnelled || pictureFirst) { kind -> if (!houseLit && fourD) colourSync?.impulse(kind) } else null
        val cs = colourSync ?: (if (r != null) TvColourSync(applicationContext, sv, ip, shared = r.link, seed = r.current())
            else TvColourSync(applicationContext, sv, ip)).also { colourSync = it; it.gentle = heavyVideo || bigHevc; it.slowRead = tenBitVideo
                it.mood(Prefs.string(this, ResumeStore.keyOf("genres", seriesTitle ?: titleText)).orEmpty()) }
        // Tunnelled (10-bit) films can't be read at all, and some films drop frames if they are: follow the sound.
        // Can't see the picture (10-bit): a neutral warm glow, never a guessed colour (artwork isn't the scene - Percy
        // Jackson's stills were all ocean blue).
        if ((tunnelled || pictureFirst) && !cs.soundOnly) cs.followSound(null)
        cs.start()
    }

    override fun onStop() {
        super.onStop()
        SoundEvents.listener = null; SoundEvents.tension = 0f; SoundEvents.rain = false
        colourSync?.stop(); colourSync = null
        room?.end(); room = null
        SoundbarWake.release("player")
        liveChannel = null
        handOffRecording()
        ts?.stop(); ts = null; tsStart = 0L
        watching = false
        handler.removeCallbacks(progressTick)
        applicationContext.let { app -> if (Sync.isOn(app)) Bg.run { Sync.shareNow(app, null, null) } }
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
        handler.removeCallbacks(previewSeek); previewPlayer?.release(); previewPlayer = null; previewFor = null
        soundFx?.release(); soundFx = null
        restoreFromCredits()
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
        } else if (pos >= 2) ResumeStore.upsert(c, ResumeStore.Item(key, kind, name, seriesTitle, curSeason, curEpisode, url, image, pos, dur, now))
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
        if (liveRec != null) { stopLiveRecording(); GlassUi.toast(this, "Recording saved (changed channel) - it's in Live TV ▸ Recordings") }
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

    // ---------- Live: the channel strip ----------

    private var chStrip: View? = null
    private var chStripClosedBy = 0
    private val hideChStripLater = Runnable { hideChannelStrip() }

    private fun hideChannelStrip() {
        val s = chStrip ?: return
        chStrip = null
        handler.removeCallbacks(hideChStripLater)
        s.animate().alpha(0f).translationY(dp(this, 40f).toFloat()).setDuration(220).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
    }

    private fun showChannelStrip() {
        if (chStrip != null) return
        val c = this
        val list = zapList
        val now = System.currentTimeMillis()
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
            setPadding(0, dp(c, 60f), 0, dp(c, 34f))
        }
        box.addView(GlassUi.text(c, "Channels", 15f, R.font.inter_semibold).apply { setPadding(dp(c, 56f), 0, 0, dp(c, 12f)) })
        val rv = androidx.recyclerview.widget.RecyclerView(c).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(c, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false; clipChildren = false
            setPadding(dp(c, 56f), dp(c, 14f), dp(c, 56f), dp(c, 14f))
            itemAnimator = null
        }
        class VH(val root: FrameLayout, val logo: android.widget.ImageView, val name: android.widget.TextView, val show: android.widget.TextView,
                 val next: android.widget.TextView, val bar: View, val barBack: View, val art: android.widget.ImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<VH>() {
            override fun getItemCount() = list.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
                val root = FrameLayout(c).apply {
                    background = GlassUi.panel(c, 16f, 0x40)
                    clipToOutline = true
                    isFocusable = true; isClickable = true
                    layoutParams = ViewGroup.MarginLayoutParams(dp(c, 250f), dp(c, 128f)).apply { marginEnd = dp(c, 16f) }
                }
                // A picture of what's on now behind the card, faded at the bottom so the words read (Apple TV).
                val art = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0.6f }
                root.addView(art, FrameLayout.LayoutParams(-1, -1))
                root.addView(View(c).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0x33000000, 0x99000000.toInt(), 0xE6000000.toInt()))
                }, FrameLayout.LayoutParams(-1, -1))
                val logo = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
                root.addView(logo, FrameLayout.LayoutParams(dp(c, 54f), dp(c, 30f)).apply { setMargins(dp(c, 14f), dp(c, 12f), 0, 0) })
                val name = GlassUi.text(c, "", 12f, R.font.inter_medium).apply { alpha = 0.75f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                root.addView(name, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 78f), dp(c, 18f), dp(c, 12f), 0) })
                val show = GlassUi.text(c, "", 15f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                root.addView(show, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 14f), dp(c, 52f), dp(c, 14f), 0) })
                val next = GlassUi.text(c, "", 11.5f, R.font.inter_medium).apply { alpha = 0.6f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                root.addView(next, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 14f), dp(c, 76f), dp(c, 14f), 0) })
                val barBack = View(c).apply { setBackgroundColor(0x33FFFFFF) }
                root.addView(barBack, FrameLayout.LayoutParams(-1, dp(c, 3f), Gravity.BOTTOM).apply { setMargins(dp(c, 14f), 0, dp(c, 14f), dp(c, 14f)) })
                val bar = View(c).apply { setBackgroundColor(0xE6FFFFFF.toInt()) }
                root.addView(bar, FrameLayout.LayoutParams(0, dp(c, 3f), Gravity.BOTTOM).apply { setMargins(dp(c, 14f), 0, 0, dp(c, 14f)) })
                root.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.08f, 10f) }
                return VH(root, logo, name, show, next, bar, barBack, art)
            }
            override fun onBindViewHolder(h: VH, pos: Int) {
                val ch = list[pos]
                val g = LiveGuide.channelFor(ch.name)
                val p = g?.now(now)
                val nx = g?.upcoming(now)?.firstOrNull { p == null || it.start >= p.stop }
                h.name.text = (if (ch.num > 0) "${ch.num}  " else "") + ch.name + if (pos == zapIndex) "  ·  Watching" else ""
                h.show.text = p?.title ?: ch.name
                h.next.text = when { p != null -> timeRange(p) + (nx?.let { "  ·  Next: " + it.title } ?: ""); nx != null -> "Next: " + nx.title; else -> "" }
                ImageLoader.load(h.logo, ch.icon ?: g?.logo, ImageLoader.ICON)
                h.art.setImageDrawable(null)
                h.art.visibility = if (p?.image != null) View.VISIBLE else View.GONE
                p?.image?.let { ImageLoader.load(h.art, it, ImageLoader.CARD) }
                val frac = p?.let { ((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) }
                h.bar.visibility = if (frac == null) View.GONE else View.VISIBLE
                h.barBack.visibility = h.bar.visibility
                (h.bar.layoutParams as FrameLayout.LayoutParams).width = ((dp(c, 250f) - dp(c, 28f)) * (frac ?: 0f)).toInt()
                h.bar.requestLayout()
                h.root.setOnClickListener {
                    hideChannelStrip()
                    if (pos != zapIndex) { zapIndex = pos; tuneTo(list[pos]) }
                }
            }
        }
        if (LiveGuide.cached() == null) LiveGuide.load(applicationContext) { runOnUiThread { if (chStrip === rv.parent) rv.adapter?.let { a -> a.notifyItemRangeChanged(0, a.itemCount) } } }
        box.addView(rv, LinearLayout.LayoutParams(-1, dp(c, 156f)))
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        chStrip = box
        view.hideController()
        box.alpha = 0f; box.translationY = dp(c, 40f).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.enter).start()
        // Start on the channel you're watching, a little in from the left.
        (rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(zapIndex, dp(c, 0f))
        rv.post { rv.findViewHolderForAdapterPosition(zapIndex)?.itemView?.requestFocus() ?: rv.getChildAt(0)?.requestFocus() }
        handler.postDelayed(hideChStripLater, 10_000)
    }

    private fun timeRange(p: LiveGuide.Programme): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        return f.format(java.util.Date(p.start)) + " – " + f.format(java.util.Date(p.stop))
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
            if (left-- <= 0) { playNext(auto = true); return }
            handler.postDelayed({ tick() }, 1000)
        }
        tick()
    }

    private fun playNext(auto: Boolean = false) {
        if (creditsMode) {
            // Pressed during the credits: learn how long this series' credits are.
            if (!auto) player?.let { p -> if (p.duration > 0) seriesTitle?.let { Prefs.setInt(this, creditsKey(), ((p.duration - p.currentPosition) / 1000).toInt().coerceIn(10, 240)) } }
            restoreFromCredits()
        }
        autoInARow = if (auto && !pressedThisEpisode) autoInARow + 1 else 0
        pressedThisEpisode = false
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
        val p = player
        if (p != null && preloadFor == n.url && p.mediaItemCount > 1) {
            // Already loading behind the credits: just move on to it.
            preloadFor = null; preloadFailed = false; atItemEnd = false
            startedAt = Perf.now(); firstFrameLogged = false; usingResolved = false; preloaded = true
            movingOn = true
            try {
                if (p.currentMediaItemIndex == 0) p.seekTo(1, 0)
                p.removeMediaItem(0)
                p.playWhenReady = true
            } finally { movingOn = false }
        } else start(n.url, resume = false)
        handler.removeCallbacks(progressTick); handler.postDelayed(progressTick, 1000)
        if (autoInARow >= 3) askStillWatching()
    }

    private var nextDismissed = false

    /** Pause, and talk to the people on screen. */
    private fun startTalk() {
        if (talk != null || live) return
        player?.pause()
        hidePauseInfo()
        val ep = if (curSeason > 0) episodeList?.firstOrNull { it.season == curSeason && it.number == curEpisode } else null
        val host = view.parent as? FrameLayout ?: return
        talk = CharacterTalk(this, host, view.videoSurfaceView as? android.view.SurfaceView, seriesTitle ?: titleText, curSeason, curEpisode,
            ep?.title, ep?.plot, player?.currentPosition ?: 0L, transcript.toList()) { talk = null }.also { it.start() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CharacterTalk.LISTEN)
            talk?.heard(if (resultCode == RESULT_OK) data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastKeyAt = android.os.SystemClock.uptimeMillis()
        if (sleepAsked > 0L && event.action == KeyEvent.ACTION_DOWN) { stillHere(); return true }
        // Talking to the characters: Back closes it; everything else moves around its buttons.
        talk?.let { t -> if (t.onKey(event)) return true; if (event.keyCode == KeyEvent.KEYCODE_BACK) return true; return super.dispatchKeyEvent(event) }
        // Paused with "You're watching" up: a press brings the controls back (and still does its job).
        if (event.action == KeyEvent.ACTION_DOWN && ::pauseInfo.isInitialized && pauseInfo.visibility == View.VISIBLE) {
            hidePauseInfo()
            // OK on "You're watching": carry on watching (Netflix), rather than only bringing the buttons back.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                player?.play(); swallowOkUp = true; return true
            }
        }
        // A quick skip's release: the player's own view would bring its controls up on any arrow key, so it never sees it.
        if (skipKeyUp != 0 && event.keyCode == skipKeyUp && event.action == KeyEvent.ACTION_UP) { skipKeyUp = 0; return true }
        // The key that closed the channel strip: its release mustn't also leave the player.
        if (chStripClosedBy != 0 && event.keyCode == chStripClosedBy) { if (event.action == KeyEvent.ACTION_UP) chStripClosedBy = 0; return true }
        chStripClosedBy = 0
        if (event.action == KeyEvent.ACTION_DOWN) pressedThisEpisode = true
        // The live channel strip along the bottom: Left / Right browse, OK changes channel, Back / Up closes it.
        chStrip?.let {
            val k = event.keyCode
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_UP) { if (event.action == KeyEvent.ACTION_DOWN) { hideChannelStrip(); chStripClosedBy = k }; return true }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) return true
            if (event.action == KeyEvent.ACTION_DOWN) { handler.removeCallbacks(hideChStripLater); handler.postDelayed(hideChStripLater, 10_000) }
            return super.dispatchKeyEvent(event)
        }
        infoPanel?.let {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_DOWN) hideInfoPanel(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                // On the tabs: stay put. From the buttons: back to the tab they belong to (not whichever tab is nearest).
                if (event.action == KeyEvent.ACTION_DOWN && currentFocus?.getTag(R.id.blur_anim) != "tab") infoTabs[infoShowing]?.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Episodes strip open: Back closes it; everything else moves around it.
        strip?.let { st ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_DOWN) hideStrip(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) { hideStrip(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true
            if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val row = st.getChildAt(0) as? ViewGroup
                val i = row?.indexOfChild(currentFocus) ?: -1
                if (row != null && ((event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && i == 0) || (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && i == row.childCount - 1))) return true
            }
            return super.dispatchKeyEvent(event)
        }
        // During the credits: OK plays the next episode, Back carries on with the credits full screen.
        if (creditsMode && !counting && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { swallowOkUp = true; playNext(); return true }
                KeyEvent.KEYCODE_BACK -> { nextDismissed = true; restoreFromCredits(); return true }
            }
        }
        // Watching a film or episode with the controls hidden: Up = "What did they say?", Down = episodes.
        if (!live && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible && !counting && !creditsMode) {
            // Up: the menu along the top (Info · Subtitles · Audio · Sound), like Apple TV's swipe down.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) { showInfoPanel(); return true }
            // Left / Right: straight back or forward 10 seconds - just a small badge at the side, no controls (Apple TV).
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val p = player
                if (p != null && p.duration > 0) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                    val target = (p.currentPosition + step).coerceIn(0L, (p.duration - 1_000L).coerceAtLeast(0L))
                    p.seekTo(target)
                    showJump(step, target, p.duration)
                    skipKeyUp = event.keyCode
                    return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Controls up, with the progress bar ready to skip along (and the preview pictures getting ready).
                view.showController(); hideGear(); warmPreview()
                view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.let { b -> b.post { b.requestFocus() } }
                return true
            }
        }
        // On the progress bar, Up: the Info / Subtitles / Audio / Sound panel along the top (like Apple TV's swipe down).
        if (!live && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && view.isControllerFullyVisible &&
            currentFocus?.id == androidx.media3.ui.R.id.exo_progress) { showInfoPanel(); return true }
        // On the progress bar, Down again: this season's episodes.
        if (!live && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && view.isControllerFullyVisible &&
            currentFocus?.id == androidx.media3.ui.R.id.exo_progress && seriesTitle != null && curSeason > 0) {
            showStrip(); return true
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
                k == KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                // Down: the channel strip along the bottom, the channel still playing above it (Apple TV live).
                k == KeyEvent.KEYCODE_DPAD_DOWN -> { if (zapList.size > 1) showChannelStrip() else zap(-1); return true }
                k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(k - KeyEvent.KEYCODE_0); return true }
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && numberBuf.isNotEmpty() -> { commitNumberNow(); return true }
                ts != null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) -> { tsJump(-30); return true }
                ts != null && (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) -> { tsJump(30); return true }
                ts != null && (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY) -> { tsTogglePause(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO)) {
            if (live) options() else if (infoPanel == null) showInfoPanel() else hideInfoPanel()
            return true
        }
        // Live: OK pauses / plays (with pause and rewind on); hold OK for subtitles, audio and picture.
        if (live && !optionsPill.isFocused && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) okLong = false
                // Held: the full menu (Record, Start Over, subtitles, audio…) at once - not the pill first.
                else if (event.repeatCount == 1) { okLong = true; options() }
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

    private data class Choice(val group: Tracks.Group, val index: Int, val label: String)

    /**
     * Track names the Apple TV way - "English", "English (SDH)", "French (Forced)", "English · 5.1", "Commentary" -
     * not the file's own labels (release-group tags, "eng.srt", "Track 3").
     */
    private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
        val raw = f.label.orEmpty()
        val lower = raw.lowercase()
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.UK) }?.takeIf { it.isNotBlank() }
            ?: Regex("(?i)\\b(english|french|spanish|german|italian|portuguese|dutch|polish|arabic|turkish|greek|hindi|japanese|korean|chinese|swedish|danish|norwegian|finnish)\\b").find(raw)?.value?.replaceFirstChar { it.uppercase() }
        val tags = mutableListOf<String>()
        val isText = f.sampleMimeType?.let { androidx.media3.common.MimeTypes.isText(it) } == true || f.channelCount <= 0 && f.sampleRate <= 0
        if (isText) {
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || "forced" in lower || "signs" in lower) tags += "Forced"
            else if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || Regex("\\b(sdh|cc|hi|hearing)\\b").containsMatchIn(lower)) tags += "SDH"
        } else {
            if ("commentary" in lower || f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) tags += "Commentary"
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 || "audio description" in lower || Regex("\\bad\\b").containsMatchIn(lower)) tags += "Audio Description"
        }
        val base = lang ?: if (tags.isNotEmpty()) "" else "Track $n"
        val name = (base + if (tags.isNotEmpty()) (if (base.isEmpty()) tags.joinToString(", ") else " (${tags.joinToString(", ")})") else "").trim()
        val extra = if (f.channelCount > 2) " · ${if (f.channelCount == 6) "5.1" else if (f.channelCount == 8) "7.1" else "${f.channelCount}ch"}" else ""
        return name + extra
    }

    private fun choices(type: Int): List<Choice> {
        val out = mutableListOf<Choice>()
        player?.currentTracks?.groups?.filter { it.type == type }?.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) out += Choice(g, i, trackLabel(g.getTrackFormat(i), out.size + 1))
        }
        // Two tracks with the same name: number the later ones ("English", "English 2").
        val seen = HashMap<String, Int>()
        return out.map { c -> val k = (seen[c.label] ?: 0) + 1; seen[c.label] = k; if (k == 1) c else c.copy(label = "${c.label} $k") }
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

    /** No copy of this episode plays: say so plainly and offer the way on (skip to the next one), not just a message. */
    private fun cantPlayEpisode(season: Int, number: Int) {
        val n = queue.firstOrNull()
        val d = GlassDialog(this).title("S$season E$number won't play")
            .message("Dodgy Box's copy of this episode is damaged or in a format the TV can't read, and there isn't another copy of this show that works.")
        if (n != null) d.button("Skip to S${n.season} E${n.episode}", GlassDialog.Style.BOLD) { playNext() }
        d.button("Try Again") { triedCopies.clear(); triedResolved = false; start(url, resume = true) }
        d.button("Back to the Show") { finish() }
        d.show()
    }

    /** Same season and episode from another listing of this show on Dodgy Box. False if there's none left to try. */
    private fun tryOtherCopy(): Boolean {
        val name = seriesTitle ?: return false
        val app = applicationContext
        val season = curSeason; val number = curEpisode
        GlassUi.toast(this, "Trying another copy of this episode…")
        Bg.run {
            var copyId = -1
            var copyEps: List<Xtream.Episode> = emptyList()
            val remaining = queue.size
            // A spare copy in the same listing first (Dodgy Box sometimes lists an episode twice) - it's the same show for sure.
            val spare = triedCopies.flatMap { Xtream.spareCopyUrls(app, it) }.firstOrNull { it !in triedCopies }
            val next = spare ?: try {
                val all = Xtream.seriesCandidates(app, name, 0).take(8).map { cand -> cand to (try { Xtream.episodes(app, cand) } catch (ex: Exception) { emptyList() }) }
                // The copy you were watching (the one the failed episode came from), to compare the others with.
                val orig = all.firstOrNull { (_, eps) -> eps.any { Xtream.episodeUrl(app, it) in triedCopies } }
                val origYear = orig?.first?.let { Xtream.yearOf(it.name) }
                val origSize = orig?.second?.size ?: 0
                val origSeasons = orig?.second?.maxOfOrNull { it.season } ?: 0
                // Only the SAME show: same year when both say one, and about the same size - so The Office (US, 9 seasons)
                // never falls back to The Office (UK, 2 seasons) or a different show with the same name.
                fun sameShow(cand: Xtream.Series, eps: List<Xtream.Episode>): Boolean {
                    val y = Xtream.yearOf(cand.name)
                    if (origYear != null && y != null && y != origYear) return false
                    if (orig != null) {
                        if (eps.size < origSize * 0.6) return false
                        if ((eps.maxOfOrNull { it.season } ?: 0) < origSeasons - 1) return false
                    } else if (eps.size <= remaining) return false        // can't hold the rest of what you're watching
                    return true
                }
                all.filter { it !== orig && sameShow(it.first, it.second) }.firstNotNullOfOrNull { (cand, eps) ->
                    val e = eps.firstOrNull { it.season == season && it.number == number }
                    e?.let { Xtream.episodeUrl(app, it) }?.takeIf { it !in triedCopies }?.also { copyId = cand.id; copyEps = eps }
                }
            } catch (ex: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (next == null) { cantPlayEpisode(season, number); return@runOnUiThread }
                Diag.send(app, "player", "trying another copy of \"$name\" S${season}E${number} [.${next.substringAfterLast('.').take(5)}]")
                triedCopies += next
                url = next; triedResolved = true; usingResolved = false
                // The rest of the series from this copy too (and next time), so each episode doesn't fail first.
                if (copyId >= 0) {
                    Prefs.setInt(app, "xpick2_" + TiviTuner.vodKey(name), copyId)
                    val moved = queue.map { q -> copyEps.firstOrNull { it.season == q.season && it.number == q.episode }?.let { Queued(Xtream.episodeUrl(app, it), q.season, q.episode) } ?: q }
                    queue.clear(); queue.addAll(moved)
                }
                start(next, resume = true)
            }
        }
        return true
    }

    // ---------- Live: recording what you're watching ----------

    private var liveRec: Recordings.Rec? = null
    private var liveRecOut: java.io.OutputStream? = null
    private val liveRecEnd = Runnable { stopLiveRecording(); GlassUi.toast(this, "Recording finished - it's in Live TV ▸ Recordings") }

    private fun startLiveRecording() {
        val prog = LiveGuide.guideFor(titleText)?.now()
        val title = prog?.title ?: titleText
        val stop = prog?.stop ?: (System.currentTimeMillis() + 3_600_000L)
        val t = ts
        if (t == null) {
            // No pause-and-rewind on this channel: the background recorder (needs Dodgy Box to allow it alongside).
            Recordings.start(this, titleText, title, stop, prog?.image)
            GlassUi.toast(this, "Recording $title - it'll be in Live TV ▸ Recordings")
            return
        }
        val rec = Recordings.begin(this, titleText, title, stop, prog?.image)
        val out = try { java.io.FileOutputStream(rec.file, true) } catch (e: Exception) { GlassUi.toast(this, "Couldn't start recording"); return }
        liveRec = rec; liveRecOut = out; t.recordTo = out
        handler.removeCallbacks(liveRecEnd); handler.postDelayed(liveRecEnd, (rec.stop - System.currentTimeMillis()).coerceAtLeast(10_000))
        Diag.send(this, "record", "recording from the live stream: \"$title\" on $titleText until ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(rec.stop))}")
        GlassUi.toast(this, "● Recording $title - it'll be in Live TV ▸ Recordings")
    }

    private fun stopLiveRecording() {
        val rec = liveRec ?: return
        handler.removeCallbacks(liveRecEnd)
        ts?.recordTo = null
        try { liveRecOut?.close() } catch (_: Exception) {}
        liveRec = null; liveRecOut = null
        Recordings.finished(applicationContext, rec.id)
    }

    /** Leaving the player with a recording still going: it carries on in the background once this connection is free. */
    private fun handOffRecording() {
        val rec = liveRec ?: return
        handler.removeCallbacks(liveRecEnd)
        ts?.recordTo = null
        try { liveRecOut?.close() } catch (_: Exception) {}
        liveRec = null; liveRecOut = null
        if (System.currentTimeMillis() >= rec.stop) { Recordings.finished(applicationContext, rec.id); return }
        val app = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Recordings.continueInBackground(app, rec) }, 2000)
        GlassUi.toast(this, "Still recording ${rec.title} in the background")
    }

    private fun options() {
        val p = player ?: return
        val d0 = GlassDialog(this).title("Playback").scrolling()
        queue.firstOrNull()?.let { n -> if (!live) d0.button("Next episode · S${n.season} E${n.episode}", GlassDialog.Style.BOLD) { playNext() } }
        // Live: Start Over - back to the beginning of the programme that's on.
        if (live) LiveGuide.guideFor(titleText)?.programmes?.firstOrNull { it.isOn() }?.let { prog ->
            if (System.currentTimeMillis() - prog.start > 2 * 60_000L) d0.button("Start Over · ${prog.title}", GlassDialog.Style.BOLD) { startOver(prog) }
        }
        // Live: record what's on, from the stream you're watching (Dodgy Box allows one connection).
        if (live) {
            val rec = liveRec
            if (rec != null) d0.button("Stop Recording · ${rec.title}", GlassDialog.Style.DESTRUCTIVE) { stopLiveRecording(); GlassUi.toast(this, "Recording saved - it's in Live TV ▸ Recordings") }
            else d0.button("Record · ${LiveGuide.guideFor(titleText)?.now()?.title ?: titleText}") { startLiveRecording() }
        }
        d0
            .button("Subtitles · ${selectedLabel(C.TRACK_TYPE_TEXT, "Off")}") { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles") }
            .button("Audio · ${selectedLabel(C.TRACK_TYPE_AUDIO, null)}") { pickTrack(C.TRACK_TYPE_AUDIO, "Audio") }
            .button("Sound · ${soundLabel()}") { pickSound() }
            .button("Picture · ${resizeName(view.resizeMode)}") { pickPicture() }
            .button("Speed · ${p.playbackParameters.speed}x") { pickSpeed() }
            .button("Sleep Timer · ${sleepLabel()}") { pickSleep() }
            .button("Close").show()
    }

    private fun pickTrack(type: Int, title: String) {
        val p = player ?: return
        val list = choices(type)
        val d = GlassDialog(this).title(title).scrolling()
        val ours = type == C.TRACK_TYPE_TEXT && list.isEmpty()
        if (list.isEmpty() && !ours) d.message("Only one audio track.")
        if (ours) d.message(when {
            ourSubs != null -> "The file has none, so these are Morgan's Subtitles - from your own subtitle library."
            OurSubtitles.isSetUp(this) -> "The file has no subtitles. Find Morgan's Subtitles for it (free, then kept in your library for every TV)."
            else -> "The file has no subtitles. To find some automatically, add a free OpenSubtitles account in Settings › Video and Audio."
        })
        if (type == C.TRACK_TYPE_TEXT) d.button("Off" + if (ours && !ourSubsOn) "  ✓" else "") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            ourSubsOn = false
            rememberTrack(C.TRACK_TYPE_TEXT, "off")
        }
        if (ours && ourSubs != null) {
            d.button("Morgan's Subtitles" + if (ourSubsOn) "  ✓" else "") { ourSubsOn = true; rememberTrack(C.TRACK_TYPE_TEXT, "en") }
            d.button("Subtitles Earlier (½ s)") { ourOffset += 500; ourShown = null; GlassUi.toast(this, "Subtitles ${fmtOffset()}") }
            d.button("Subtitles Later (½ s)") { ourOffset -= 500; ourShown = null; GlassUi.toast(this, "Subtitles ${fmtOffset()}") }
        } else if (ours && OurSubtitles.isSetUp(this)) d.button("Find Subtitles", GlassDialog.Style.BOLD) { GlassUi.toast(this, "Looking for subtitles…"); fetchOurSubs(force = true) }
        for (c in list) d.button(c.label + if (c.group.isTrackSelected(c.index)) "  ✓" else "") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index)).build()
            rememberTrack(type, c.group.getTrackFormat(c.index).language?.takeIf { it != "und" })
        }
        d.button("Back") { options() }
        d.show()
    }

    private fun fmtOffset() = if (ourOffset == 0L) "back in time" else "%+.1f s".format(ourOffset / 1000f)

    private fun rememberTrack(type: Int, language: String?) {
        if (type == C.TRACK_TYPE_TEXT) { subsChosenByYou = true; if (autoSubsHidden) { autoSubsHidden = false; view.subtitleView?.visibility = View.VISIBLE } }
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

    /** From the start of the programme: from the pause buffer if it goes back that far, else Dodgy Box's catch-up. */
    private fun startOver(prog: LiveGuide.Programme) {
        val t = ts
        val backSecs = (System.currentTimeMillis() - prog.start) / 1000
        if (t != null && t.written - backSecs * t.bytesPerSecond >= t.oldest()) {
            tsRestartAt(t.written - backSecs * t.bytesPerSecond); tsShowStatus()
            GlassUi.toast(this, "From the start of ${prog.title}")
            return
        }
        if (liveId < 0) { GlassUi.toast(this, "This channel can’t start over"); return }
        val mins = ((prog.stop - prog.start) / 60_000L).toInt().coerceAtLeast(10)
        GlassUi.toast(this, "Starting ${prog.title} from the beginning (if Dodgy Box keeps catch-up for this channel)…")
        play(this, Xtream.archiveUrl(applicationContext, liveId, prog.start, mins), prog.title, live = false,
            subtitle = "$titleText · from the start", eyebrow = "START OVER")
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

    /** The skip badge: "« 10s" on the left going back, "10s »" on the right going forward; presses in a row add up. */
    private fun showJump(step: Long, pos: Long, dur: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (jumpBadge.visibility != View.VISIBLE || now - jumpAt > 1200 || (jumpTotal > 0) != (step > 0)) jumpTotal = 0
        jumpAt = now
        jumpTotal += step
        val secs = kotlin.math.abs(jumpTotal) / 1000
        val amount = if (secs >= 60) "${secs / 60}m ${(secs % 60).toString().padStart(2, '0')}s" else "${secs}s"
        fun clock(ms: Long) = (ms / 1000).let { t -> if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
        jumpBadge.text = (if (step > 0) "$amount  »" else "«  $amount") + "\n" + clock(pos) + " / " + clock(dur)
        jumpBadge.setLineSpacing(0f, 1.1f)
        (jumpBadge.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (step > 0) Gravity.END else Gravity.START)
        jumpBadge.requestLayout()
        handler.removeCallbacks(hideJump)
        if (jumpBadge.visibility != View.VISIBLE) { jumpBadge.alpha = 0f; jumpBadge.visibility = View.VISIBLE }
        jumpBadge.animate().cancel()
        jumpBadge.animate().alpha(1f).setDuration(120).start()
        jumpBadge.scaleX = 1.06f; jumpBadge.scaleY = 1.06f
        jumpBadge.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        handler.postDelayed(hideJump, 1300)
    }

    /**
     * Apple TV's Match Frame Rate: films are mostly 24 (23.976) frames a second, but the TV is fed 60 - so every
     * other frame is held a little longer and slow pans judder. Switch the HDMI output to a refresh rate the frame
     * rate divides into exactly (24 → 24 Hz, 25 → 50 Hz, 30 → 60 Hz), same resolution. The TV blanks for a moment
     * while it switches, as on Apple TV; Back to the menus puts it back to the usual rate.
     */
    private var matchedFps = 0f
    /** Timestamps of the first frames, to work out a frame rate the file doesn't state (the TV shook at 60 Hz). */
    private fun measureFrameRate() {
        val p = player ?: return
        if (live) return
        val stamps = java.util.ArrayList<Long>()
        p.setVideoFrameMetadataListener(object : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: androidx.media3.common.Format, mediaFormat: android.media.MediaFormat?) {
                synchronized(stamps) {
                    if (stamps.size >= 90) return
                    stamps += presentationTimeUs
                    if (stamps.size < 90) return
                }
                val d = synchronized(stamps) { stamps.sorted().zipWithNext { a, b -> b - a }.filter { it in 5_000..100_000 }.sorted() }
                if (d.size < 30) return
                val median = d[d.size / 2].toDouble()
                val raw = (1_000_000.0 / median).toFloat()
                val standard = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
                val fps = standard.minByOrNull { kotlin.math.abs(it - raw) }!!.takeIf { kotlin.math.abs(it - raw) / it < 0.01f } ?: raw
                runOnUiThread {
                    player?.clearVideoFrameMetadataListener(this)
                    Diag.send(this@PlayerActivity, "player", "frame rate not in the file - measured %.3f fps".format(raw))
                    matchFrameRate(fps)
                }
            }
        })
    }

    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || fps == matchedFps || !Prefs.bool(this, "match_frame_rate", true)) return
        matchedFps = fps
        try {
            @Suppress("DEPRECATION")
            val d = (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
            val cur = d.mode
            fun fits(r: Float): Int? { val k = Math.round(r / fps); return if (k in 1..2 && kotlin.math.abs(r - k * fps) < 0.06f) k else null }
            val same = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && fits(it.refreshRate) != null }
            val best = same.filter { it.refreshRate >= 47f }.minByOrNull { it.refreshRate } ?: same.minByOrNull { it.refreshRate }
            if (best == null) {
                Diag.send(this, "player", "frame rate %.3f: no matching TV mode (TV at %.2f Hz) · \"%s\"".format(fps, cur.refreshRate, titleText)); return
            }
            if (best.modeId == cur.modeId) return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            settleUntil = android.os.SystemClock.uptimeMillis() + 10_000L
            Diag.send(this, "player", "frame rate %.3f → TV %.3f Hz (was %.2f) · \"%s\"".format(fps, best.refreshRate, cur.refreshRate, titleText))
        } catch (e: Throwable) { Diag.send(this, "player", "frame rate match failed: ${e.message}") }
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

    /** The player's own gear button (bottom right) - everything it did is in our Options. */
    private fun hideGear() {
        for (id in listOf(androidx.media3.ui.R.id.exo_settings, androidx.media3.ui.R.id.exo_overflow_show, androidx.media3.ui.R.id.exo_subtitle))
            view.findViewById<View>(id)?.let { it.visibility = View.GONE; it.isFocusable = false }
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
        loadCast()
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
                val eps = try { Xtream.seriesEpisodes(app, name, 0)?.second } catch (e: Exception) { null }
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

    // ---------- Sound ----------

    private fun soundLabel(): String {
        val s = SoundProfile.get(this)
        return when { s.flat -> "Original"; else -> listOfNotNull(if (SoundProfile.calibrated(this)) "Tuned" else null,
            if (s.dialogue) (if (s.more) "Enhance More" else "Enhance Dialogue") else null, if (s.level) "Reduce Loud Sounds" else null).joinToString(" · ").ifBlank { "Custom" } }
    }

    private var decodingAudio = false

    /** What the Chromecast says it can send to the TV/soundbar untouched (if Dolby isn't here, it gets turned into LPCM). */
    @Suppress("DEPRECATION")
    private fun passFormats(): String = try {
        val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this)
        val names = listOf(C.ENCODING_AC3 to "DD", C.ENCODING_E_AC3 to "DD+", C.ENCODING_E_AC3_JOC to "Atmos", C.ENCODING_DTS to "DTS", C.ENCODING_DOLBY_TRUEHD to "TrueHD")
            .filter { caps.supportsEncoding(it.first) }.map { it.second }
        "passthrough=" + (names.joinToString("/").ifEmpty { "none" }) + " maxch=" + caps.maxChannelCount
    } catch (e: Throwable) { "passthrough=?" }
    private var passthroughNow = false
    private var virtualMode = false
    private var fxFor: String? = null
    private var fxFailedFor: String? = null

    /** (Re)starts the sound profile on what's playing. Logged, so the TV's log shows whether it's really on. */
    private fun startSoundFx() {
        soundFx?.release(); soundFx = null
        val s = SoundProfile.get(this)
        VirtualSurround.settings = s
        // Virtual Surround does the sound profile itself (Android's effects won't attach to decoded Dolby here).
        if (virtualMode) { Diag.send(this, "player", "sound processing in the mixer (" + (Prefs.string(this, "audio_format") ?: "auto") + ") · " + SoundProfile.describe(s).removePrefix("Your sound: ") + " · \"$titleText\""); return }
        if (audioSession == 0 || passthroughNow || fxFailedFor == url) {
            if (!s.flat && passthroughNow) Diag.send(this, "player", "sound settings not applied to \"$titleText\": Dolby goes straight to the soundbar")
            return
        }
        val name = titleText
        soundFx = SoundProfile.Effect(audioSession) { Diag.send(applicationContext, "player", "sound processing $it · \"$name\"") }.also { it.apply(s) }
        if (soundFx?.state?.startsWith("failed") == true) { fxFailedFor = url; soundFx = null }
    }

    /** Rebuilds the player at the same moment with the new Audio Format (for comparing the two by ear). */
    private fun reloadSound() {
        val p = player ?: return
        val pos = p.currentPosition
        saveProgress()
        p.release(); player = null
        audioFallback = false
        start(url, resume = false)
        if (!live) player?.seekTo(pos)
        GlassUi.toast(this, if ((Prefs.string(this, "audio_format") ?: "auto") == "virtual") "Virtual Surround" else "Dolby to your soundbar")
    }

    private fun applySound(s: SoundProfile.Settings) {
        VirtualSurround.settings = s
        if (virtualMode) return
        // Dolby is going straight to the soundbar in full surround: nothing here can change it.
        if (!s.flat && passthroughNow) GlassUi.toast(this, "This programme’s Dolby sound goes straight to your soundbar, so these settings can’t change it - try your soundbar’s voice or night mode")
        if (soundFx == null && !s.flat) startSoundFx() else soundFx?.apply(s)
    }

    private fun dlgName(n: Int) = when (n) { 1 -> "Enhance"; 2 -> "Enhance More"; else -> "Off" }

    /** Off → Enhance → Enhance More → Off, like Apple TV's Enhance Dialogue. */
    private fun cycleDialogue() {
        SoundProfile.setDialogueLevel(this, (SoundProfile.dialogueLevel(this) + 1) % 3)
        applySound(SoundProfile.get(this))
    }

    private fun pickSound() {
        val s = SoundProfile.get(this)
        val passthrough = player?.audioFormat?.sampleMimeType?.let { it.contains("ac3") || it.contains("dts") || it.contains("eac3") } == true
        GlassDialog(this).title("Sound").message(SoundProfile.describe(s) +
                (if (passthrough) "\n\nThis programme’s Dolby sound may go straight to your soundbar, which then does its own processing." else ""))
            .button("Sound Check…", GlassDialog.Style.BOLD) {
                SoundProfile.soundCheck(this, { applySound(it.copy(dialogue = s.dialogue, more = s.more)) }) { r ->
                    val keep = r.copy(dialogue = s.dialogue, more = s.more); SoundProfile.save(this, keep); applySound(keep)
                }
            }
            .button("Enhance Dialogue · ${dlgName(SoundProfile.dialogueLevel(this))}") { cycleDialogue(); pickSound() }
            .toggle("Reduce Loud Sounds", s.level) {
                val n = SoundProfile.get(this).let { it.copy(level = !it.level) }; SoundProfile.save(this, n); applySound(n)
            }
            .button("Back to the Original Sound") { val n = SoundProfile.Settings(0, 0, 0, false, false); SoundProfile.save(this, n); applySound(n) }
            .button("Done").show()
    }

    // ---------- Auto Subtitles ----------

    /** Subtitles off but the file has some: load them hidden, so they can appear the moment the voices get buried. */
    private fun autoSubtitles(tracks: Tracks) {
        VirtualSurround.autoSubs = Prefs.bool(this, "auto_subs", true) && !live
        VirtualSurround.nightAllowed = Prefs.bool(this, "night_sound", true)
        if (!VirtualSurround.autoSubs || autoSubsHidden || subsChosenByYou) return
        val p = player ?: return
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        val anyOn = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        if (!hasText || anyOn && !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return
        autoSubsHidden = true
        view.subtitleView?.visibility = View.INVISIBLE
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(java.util.Locale.getDefault().language).build()
    }

    // ---------- Still watching? ----------

    private val sleepFade = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (sleepAsked == 0L) return
            val t = ((android.os.SystemClock.uptimeMillis() - sleepAsked) / 60_000f).coerceIn(0f, 1f)
            p.volume = 1f - t
            if (t < 1f) { handler.postDelayed(this, 250); return }
            // Asleep: pause right here (it's remembered), sound back to normal for next time, screen dark.
            p.pause(); p.volume = 1f
            sleepDialog?.dismiss(); sleepDialog = null
            Diag.send(applicationContext, "player", "nodded off at ${p.currentPosition / 60_000} min of \"$titleText\" - paused, lights off")
            finish()
        }
    }

    private fun askIfAsleep() {
        sleepAsked = android.os.SystemClock.uptimeMillis()
        colourSync?.sleep(60_000L) ?: room?.sleep()
        sleepDialog = GlassDialog(this).title("Still Watching?")
            .message("Nothing's been pressed for a while. If you've nodded off, the sound and lights will fade and it'll pause right here, ready for next time.")
            .button("Keep Watching", GlassDialog.Style.BOLD) { stillHere() }.show()
        handler.post(sleepFade)
    }

    private fun stillHere() {
        lastKeyAt = android.os.SystemClock.uptimeMillis()
        if (sleepAsked == 0L) return
        sleepAsked = 0L
        handler.removeCallbacks(sleepFade)
        sleepDialog?.dismiss(); sleepDialog = null
        player?.volume = 1f
        colourSync?.awake(); room?.link?.asleep = false; startColourSync()
    }

    // ---------- "What did they say?": back 10 seconds with subtitles on for a moment ----------

    private var subsWereOff = false
    private fun whatDidTheySay() {
        val p = player ?: return
        p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0))
        val hasSubs = choices(C.TRACK_TYPE_TEXT).isNotEmpty() || ourSubs != null
        if (hasSubs && subsUntil == 0L && ourSubs == null) {
            subsWereOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || choices(C.TRACK_TYPE_TEXT).none { it.group.isTrackSelected(it.index) }
            if (subsWereOff) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(java.util.Locale.getDefault().language).build()
        }
        if (hasSubs) subsUntil = System.currentTimeMillis() + 15_000L
        tsView.text = if (hasSubs) "⟲  Back 10 seconds, with subtitles" else "⟲  Back 10 seconds"
        tsView.visibility = View.VISIBLE
        handler.removeCallbacks(hideTs); handler.postDelayed({ tsView.visibility = View.GONE }, 2500)
    }

    private fun endTempSubs() {
        subsUntil = 0L
        val p = player ?: return
        if (subsWereOff) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        subsWereOff = false
    }

    // ---------- Next episode during the credits ----------

    private fun creditsKey() = ProfileManager.scope(this, "credits_" + TextMatch.vodKey(seriesTitle.orEmpty()))
    private fun creditsWindow(): Long = (Prefs.int(this, creditsKey(), 0).takeIf { it > 0 } ?: 25) * 1000L

    private fun enterCredits(left: Long) {
        if (creditsMode) return
        creditsMode = true
        view.hideController()
        nextView.visibility = View.GONE
        val c = this
        // The picture shrinks to the top-left, like Netflix; a big "Next Episode" card takes the rest.
        view.pivotX = 0f; view.pivotY = 0f
        view.animate().scaleX(0.46f).scaleY(0.46f).translationX(dp(c, 48f).toFloat()).translationY(dp(c, 40f).toFloat()).setDuration(450).start()
        val n = queue.first()
        val ep = episodeList?.firstOrNull { it.season == n.season && it.number == n.episode }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 8f), 0, 0, 0)
            alpha = 0f
        }
        card.addView(GlassUi.text(c, "UP NEXT", 12f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.2f })
        card.addView(GlassUi.text(c, "Season ${n.season}: Episode ${n.episode}", 22f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f) })
        ep?.title?.let { cleanEpTitle(it) }?.takeIf { it.isNotBlank() }?.let { card.addView(GlassUi.text(c, "“$it”", 16f, R.font.inter_medium, 0xE6FFFFFF.toInt())) }
        ep?.plot?.takeIf { it.isNotBlank() }?.let { card.addView(GlassUi.text(c, it, 13f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { maxLines = 4 },
            LinearLayout.LayoutParams(dp(c, 380f), -2).apply { topMargin = dp(c, 8f) }) }
        val btn = GlassUi.text(c, "▶  Next Episode", 16f, R.font.inter_semibold, 0xFF000000.toInt()).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 22f).toFloat(); setColor(0xFFFFFFFF.toInt()) }
            setPadding(dp(c, 24f), dp(c, 11f), dp(c, 24f), dp(c, 11f))
        }
        card.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 18f) })
        val line = GlassUi.text(c, "", 12f, R.font.inter_medium, 0x99FFFFFF.toInt())
        card.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 10f) })
        card.addView(GlassUi.text(c, "OK to play now  ·  Back to watch the credits", 12f, R.font.inter_medium, 0x80FFFFFF.toInt()))
        creditsLine = line
        (view.parent as? FrameLayout)?.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(c, 64f), dp(c, 60f)) })
        card.animate().alpha(1f).setDuration(400).start()
        creditsCard = card
        if (episodeList == null) showPauseInfoFetchOnly()
    }

    private fun restoreFromCredits() {
        if (!creditsMode) return
        creditsMode = false
        view.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(300).start()
        creditsCard?.let { (it.parent as? FrameLayout)?.removeView(it) }
        creditsCard = null; creditsLine = null
    }

    /** Fetches the episode names and stories (for the credits card and the episodes strip). */
    /** Subtitle Style (Settings, per profile): the film's own look, bigger, or bigger on a dark box (Apple TV's). */
    private fun applySubtitleStyle() {
        val sv = view.subtitleView ?: return
        when (Prefs.string(this, ProfileManager.scope(this, "sub_style")) ?: "standard") {
            "large" -> {
                sv.setApplyEmbeddedStyles(false)
                sv.setStyle(androidx.media3.ui.CaptionStyleCompat(0xFFFFFFFF.toInt(), 0, 0, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xE6000000.toInt(), null))
                sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.3f)
            }
            "box" -> {
                sv.setApplyEmbeddedStyles(false)
                sv.setStyle(androidx.media3.ui.CaptionStyleCompat(0xFFFFFFFF.toInt(), 0xCC000000.toInt(), 0, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, 0, null))
                sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.3f)
            }
            else -> { sv.setApplyEmbeddedStyles(true); sv.setUserDefaultStyle(); sv.setUserDefaultTextSize() }
        }
    }

    /** InSight: the cast (from the film and TV guide), fetched once per title the first time you pause. */
    private fun loadCast() {
        if (live) { pauseCast.visibility = View.GONE; return }
        val name = seriesTitle ?: titleText
        if (castFor == name) return
        castFor = name
        pauseCast.visibility = View.GONE
        WatchCatalog.titles(this, search = name, first = 1, types = listOf(if (seriesTitle != null) "SHOW" else "MOVIE")) { l ->
            val t = l?.firstOrNull() ?: return@titles
            WatchCatalog.detail(this, t) { d ->
                if (d == null || isDestroyed || castFor != name) return@detail
                val lines = listOfNotNull(
                    d.cast.takeIf { it.isNotEmpty() }?.let { "Starring  " + it.joinToString("  ·  ") },
                    d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by  " + it.joinToString(", ") },
                )
                pauseCast.text = lines.joinToString("\n")
                pauseCast.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showPauseInfoFetchOnly(then: (() -> Unit)? = null) {
        if (episodeList != null) { then?.invoke(); return }
        if (episodeListLoading || seriesTitle == null) return
        episodeListLoading = true
        val app = applicationContext; val name = seriesTitle!!
        Bg.run {
            val eps = try { Xtream.seriesEpisodes(app, name, 0)?.second } catch (e: Exception) { null }
            runOnUiThread { episodeListLoading = false; episodeList = eps.orEmpty(); if (!isDestroyed) then?.invoke() }
        }
    }

    // ---------- "Are you still watching?" ----------

    private fun askStillWatching() {
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            player?.playWhenReady = false
            GlassDialog(this).title("Are You Still Watching?").message("${seriesTitle ?: titleText} has played ${autoInARow} episodes in a row without a button being pressed.")
                .button("Continue Watching", GlassDialog.Style.BOLD) { autoInARow = 0; player?.playWhenReady = true }
                .button("Back to Browse") { finish() }
                .button("Turn Off the TV") { goToSleep() }
                .show()
        }, 1500)
    }

    // ---------- Rate it at the end ----------

    private fun askRating(then: () -> Unit) {
        val name = seriesTitle ?: titleText
        if (live || name.isBlank() || Ratings.get(this, name) != null) return then()
        val d = GlassDialog(this).title("How Was $name?").message("Your rating tunes what’s recommended to this profile.")
        d.button("👍👍  Love This!", GlassDialog.Style.BOLD) { Ratings.set(this, name, 2); then() }
        d.button("👍  I Like This") { Ratings.set(this, name, 1); then() }
        d.button("👎  Not for Me") { Ratings.set(this, name, -1); then() }
        d.button("Skip") { then() }
        d.show().setOnCancelListener { then() }
    }

    // ---------- Episodes strip ----------

    private fun showStrip() {
        if (strip != null) return
        showPauseInfoFetchOnly { buildStrip() }
        if (episodeList != null) buildStrip() else GlassUi.toast(this, "Getting the episodes…")
    }

    private fun buildStrip() {
        if (strip != null || isFinishing) return
        val eps = episodeList.orEmpty().filter { it.season == curSeason }.ifEmpty { return }
        val c = this
        val row = LinearLayout(c).apply { setPadding(dp(c, 48f), dp(c, 12f), dp(c, 48f), dp(c, 8f)); clipChildren = false; clipToPadding = false }
        var focusMe: View? = null
        for (e in eps) {
            val now = e.number == curEpisode
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = GlassUi.panel(c, 14f, if (now) 0x70 else 0x40)
                setPadding(dp(c, 14f), dp(c, 10f), dp(c, 14f), dp(c, 10f))
                isFocusable = true; isClickable = true
                setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.08f else 1f); v.alpha = if (has) 1f else 0.8f
                    if (has) (v.parent?.parent as? android.widget.HorizontalScrollView)?.let { hs -> hs.smoothScrollTo((v.left - (hs.width - v.width) / 2).coerceAtLeast(0), 0) } }
                alpha = 0.8f
                setOnClickListener { hideStrip(); if (!now) playEpisode(e) }
            }
            card.addView(GlassUi.text(c, (if (now) "▶ NOW  ·  " else "") + "EPISODE ${e.number}", 11f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.1f })
            card.addView(GlassUi.text(c, cleanEpTitle(e.title).ifBlank { "Episode ${e.number}" }, 14f, R.font.inter_semibold).apply { maxLines = 2 })
            if (e.minutes > 0) card.addView(GlassUi.text(c, "${e.minutes} min", 11f, R.font.inter_medium, 0x99FFFFFF.toInt()))
            row.addView(card, LinearLayout.LayoutParams(dp(c, 210f), dp(c, 96f)).apply { marginEnd = dp(c, 14f) })
            if (now) focusMe = card
        }
        val hs = StripScroll(c).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            setPadding(0, dp(c, 40f), 0, dp(c, 24f))
        }
        (view.parent as? FrameLayout)?.addView(hs, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        strip = hs
        view.hideController()
        (focusMe ?: row.getChildAt(0))?.let { f -> f.post { hs.scrollTo((f.left - (hs.width - f.width) / 2).coerceAtLeast(0), 0); f.requestFocus() } }
    }

    /** The episode strip: scrolls only when we say (its own jump on every move fought with the smooth glide). */
    private class StripScroll(c: Context) : android.widget.HorizontalScrollView(c) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?) = 0
    }

    private fun hideStrip() {
        strip?.let { (it.parent as? FrameLayout)?.removeView(it) }
        strip = null
        view.requestFocus()
    }

    private fun playEpisode(e: Xtream.Episode) {
        saveProgress()
        val app = applicationContext
        val eps = episodeList.orEmpty()
        val i = eps.indexOfFirst { it.season == e.season && it.number == e.number }
        queue.clear()
        eps.drop(i).forEach { queue.addLast(Queued(Xtream.episodeUrl(app, it), it.season, it.number)) }
        playNext()
    }

    // ---------- Previews while skipping through ----------

    // ---------- Info panel (Up from the progress bar) ----------

    private var infoPanel: LinearLayout? = null
    private var infoBody: LinearLayout? = null
    private val infoTabs = LinkedHashMap<String, android.widget.TextView>()

    private fun glassPill(label: String, selected: Boolean = false, click: () -> Unit) = GlassUi.text(this, label, 14f, R.font.inter_semibold,
            if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()).apply {
        val c = this@PlayerActivity
        maxLines = 1; isSingleLine = true
        gravity = Gravity.CENTER; setPadding(dp(c, 18f), dp(c, 9f), dp(c, 18f), dp(c, 9f))
        isFocusable = true; isClickable = true
        fun paint(f: Boolean) {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 20f).toFloat()
                setColor(if (f) 0xFFFFFFFF.toInt() else if (selected) 0xD9FFFFFF.toInt() else 0x33FFFFFF) }
            setTextColor(if (f || selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        paint(false)
        setOnFocusChangeListener { v, has -> paint(has); Motion.spring(v, if (has) 1.06f else 1f) }
        setOnClickListener { click() }
    }

    private fun showInfoPanel(tab: String = "info") {
        val c = this
        hideInfoPanel()
        view.hideController()
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2000000.toInt(), 0xCC000000.toInt(), 0x00000000))
            setPadding(dp(c, 56f), dp(c, 30f), dp(c, 56f), dp(c, 70f))
            alpha = 0f; translationY = -dp(c, 30f).toFloat()
        }
        val tabs = LinearLayout(c).apply { gravity = Gravity.CENTER_HORIZONTAL }
        infoTabs.clear()
        for ((id, label) in listOf("info" to "Info", "subs" to "Subtitles", "audio" to "Audio", "sound" to "Sound")) {
            val t = GlassUi.text(c, label, 15f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply {
                setPadding(dp(c, 16f), dp(c, 6f), dp(c, 16f), dp(c, 6f)); isFocusable = true; isClickable = true
                setTag(R.id.blur_anim, "tab")
                setOnFocusChangeListener { v, has -> if (has && !refilling) fillInfo(id); Motion.spring(v, if (has) 1.08f else 1f)
                    (v as android.widget.TextView).setTextColor(if (has) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()) }
                setOnClickListener { infoBody?.getChildAt(0)?.let { b -> (b as? ViewGroup)?.let { firstFocusable(it) }?.requestFocus() } }
            }
            infoTabs[id] = t
            tabs.addView(t)
        }
        panel.addView(tabs)
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        panel.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })
        infoBody = body
        (view.parent as? FrameLayout)?.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        infoPanel = panel
        panel.animate().alpha(1f).translationY(0f).setDuration(300).start()
        infoTabs[tab]?.requestFocus()
        fillInfo(tab)
        if (seriesTitle != null && episodeList == null) showPauseInfoFetchOnly { if (infoPanel != null && infoTabs["info"]?.isFocused == true) fillInfo("info") }
    }

    private fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) { val ch = g.getChildAt(i); if (ch.isFocusable) return ch; if (ch is ViewGroup) firstFocusable(ch)?.let { return it } }
        return null
    }

    private fun hideInfoPanel() {
        infoPanel?.let { p -> p.animate().alpha(0f).setDuration(180).withEndAction { (p.parent as? FrameLayout)?.removeView(p) }.start() }
        infoPanel = null; infoBody = null
        view.requestFocus()
    }

    private var infoShowing = "info"
    private var refilling = false

    private fun fillInfo(tab: String) {
        val body = infoBody ?: return
        infoShowing = tab
        val c = this
        // Pressed a button that redraws this row (Dialogue, Reduce Loud Sounds, a subtitle…): stay on that button afterwards,
        // instead of the highlight falling back to the film behind.
        val oldRow = ((body.getChildAt(body.childCount - 1) as? ViewGroup)?.getChildAt(0) as? ViewGroup)
        val keepAt = oldRow?.let { r -> (0 until r.childCount).map { r.getChildAt(it) }.filter { it.isFocusable }.indexOfFirst { it.hasFocus() } } ?: -1
        if (keepAt >= 0) infoTabs[tab]?.let { refilling = true; it.requestFocus(); refilling = false }   // park on the tab while the row is rebuilt
        body.removeAllViews()
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        fun pill(label: String, selected: Boolean = false, click: () -> Unit) =
            row.addView(glassPill(label, selected, click), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 12f) })
        val p = player
        when (tab) {
            "info" -> {
                val ep = if (curSeason > 0) episodeList?.firstOrNull { it.season == curSeason && it.number == curEpisode } else null
                val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                txt.addView(GlassUi.text(c, seriesTitle ?: titleText, 24f, R.font.inter_semibold))
                val line = when { curSeason > 0 -> "Season $curSeason, Episode $curEpisode" + (ep?.title?.let { cleanEpTitle(it) }?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "")
                                  else -> bannerSub.text?.toString().orEmpty() }
                if (line.isNotBlank()) txt.addView(GlassUi.text(c, line, 15f, R.font.inter_medium, 0xE6FFFFFF.toInt()), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
                ep?.plot?.takeIf { it.isNotBlank() }?.let { txt.addView(GlassUi.text(c, it, 13.5f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply { maxLines = 3 },
                    LinearLayout.LayoutParams(dp(c, 620f), -2).apply { topMargin = dp(c, 8f) }) }
                body.addView(txt)
                pill("Start Over") { hideInfoPanel(); player?.seekTo(0) }
                pill("⟲ 10s with Subtitles") { hideInfoPanel(); whatDidTheySay() }
                pill("Talk to the Characters") { hideInfoPanel(); startTalk() }
                if (seriesTitle != null && curSeason > 0) pill("Episodes") { hideInfoPanel(); showStrip() }
                if (queue.isNotEmpty()) pill("Next Episode") { hideInfoPanel(); playNext() }
                pill("Sleep Timer · ${sleepLabel()}") { hideInfoPanel(); pickSleep() }
            }
            "subs" -> {
                val off = p?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) != false
                pill("Off", off && !ourSubsOn) { ourSubsOn = false; p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                    rememberTrack(C.TRACK_TYPE_TEXT, "off"); fillInfo("subs") }
                val list = choices(C.TRACK_TYPE_TEXT)
                if (list.isEmpty() && ourSubs != null) pill("Morgan's Subtitles", ourSubsOn) { ourSubsOn = true; rememberTrack(C.TRACK_TYPE_TEXT, "en"); fillInfo("subs") }
                else if (list.isEmpty()) row.addView(GlassUi.text(c, "No subtitles in this file", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()))
                for (ch in list) pill(ch.label, !off && ch.group.isTrackSelected(ch.index)) {
                    p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(ch.group.mediaTrackGroup, ch.index)).build() }
                    rememberTrack(C.TRACK_TYPE_TEXT, ch.group.getTrackFormat(ch.index).language?.takeIf { it != "und" }); fillInfo("subs")
                }
            }
            "audio" -> {
                val list = choices(C.TRACK_TYPE_AUDIO)
                if (list.size <= 1) row.addView(GlassUi.text(c, "Only soundtrack · " + (list.firstOrNull()?.label?.substringAfter("| ")?.trim() ?: "Original"), 13f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(c, 330f)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 18f) })
                else for (ch in list) pill(ch.label, ch.group.isTrackSelected(ch.index)) {
                    p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(ch.group.mediaTrackGroup, ch.index)).build() }
                    rememberTrack(C.TRACK_TYPE_AUDIO, ch.group.getTrackFormat(ch.index).language?.takeIf { it != "und" }); fillInfo("audio")
                }
                pill("Speed · ${p?.playbackParameters?.speed ?: 1f}x") { hideInfoPanel(); pickSpeed() }
                pill("Picture · ${resizeName(view.resizeMode)}") { hideInfoPanel(); pickPicture() }
            }
            "sound" -> {
                val st = SoundProfile.get(this)
                // A/B: our Virtual Surround mixer, or Dolby straight to the soundbar (its own surround) - same scene, both ways.
                val fmt = Prefs.string(this, "audio_format") ?: "auto"
                pill("Format · " + when (fmt) { "virtual" -> "Virtual Surround"; "auto" -> "Dolby to Soundbar"; "decode" -> "Decode on TV"; else -> "Stereo" }) {
                    Prefs.setString(this, "audio_format", if (fmt == "virtual") "auto" else "virtual")
                    reloadSound(); fillInfo("sound")
                }
                pill("Calibrate") { hideInfoPanel(); startActivity(android.content.Intent(this, RoomCalibrationActivity::class.java)) }
                pill("Listening Test") { hideInfoPanel(); SoundProfile.soundCheck(this, { applySound(it.copy(dialogue = st.dialogue, more = st.more)) }) { r ->
                    val keep = r.copy(dialogue = st.dialogue, more = st.more); SoundProfile.save(this, keep); applySound(keep) } }
                pill("Dialogue · ${dlgName(SoundProfile.dialogueLevel(this))}", st.dialogue) { cycleDialogue(); fillInfo("sound") }
                pill("Reduce Loud Sounds", st.level) { val n = SoundProfile.get(this).let { it.copy(level = !it.level) }; SoundProfile.save(this, n); applySound(n); fillInfo("sound") }
                pill("Original Sound", st.flat) { val n = SoundProfile.Settings(0, 0, 0, false, false); SoundProfile.save(this, n); applySound(n); fillInfo("sound") }
            }
        }
        body.addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(c, 6f), dp(c, 40f), dp(c, 6f))
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 8f) })
        if (keepAt >= 0) {
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isFocusable }
            (buttons.getOrNull(keepAt) ?: buttons.lastOrNull())?.requestFocus()
        }
    }

    /** Apple-style controls: a thin white progress line on a light dim, no heavy boxes. */
    private fun styleControls() {
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(0xFFFFFFFF.toInt()); setScrubberColor(0xFFFFFFFF.toInt())
            setBufferedColor(0x66FFFFFF); setUnplayedColor(0x33FFFFFF)
        }
        view.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)?.background =
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xB3000000.toInt(), 0x33000000, 0x26000000))
        view.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.setBackgroundColor(0)
    }

    private var scrubBox: LinearLayout? = null
    private var previewFrame: FrameLayout? = null
    private var previewTex: android.view.TextureView? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewWant = 0L
    private var previewFor: String? = null

    private val previewSeek = Runnable {
        val tex = previewTex ?: return@Runnable
        val pp = previewPlayer ?: ExoPlayer.Builder(this)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(500, 1500, 0, 0).build())
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .build().also { p ->
                previewPlayer = p
                p.volume = 0f
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setMaxVideoSize(640, 360).build()
                p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)     // nearest keyframe: much quicker
                p.setVideoTextureView(tex)
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { previewReady = true; previewFrame?.let { f -> if (scrubBox?.visibility == View.VISIBLE) f.visibility = View.VISIBLE } }
                    override fun onPlayerError(error: PlaybackException) {
                        val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        Diag.send(this@PlayerActivity, "player", "preview pictures failed: ${error.errorCodeName}${http?.let { " (HTTP $it)" }.orEmpty()}")
                        previewPlayer?.release(); previewPlayer = null; previewFor = null; previewReady = false
                    }
                })
            }
        if (previewFor != url) {
            previewFor = url
            pp.setMediaItem(MediaItem.fromUri(Xtream.resolved(url) ?: url))
            pp.playWhenReady = false
            pp.prepare()
        }
        pp.seekTo(previewWant)
    }

    private var previewReady = false
    private val releasePreview = Runnable { previewPlayer?.release(); previewPlayer = null; previewFor = null; previewReady = false }

    private val hideScrub = Runnable {
        thumbWant = -1L; scrubBox?.visibility = View.GONE; thumbView?.visibility = View.GONE; previewFrame?.visibility = View.GONE
        handler.removeCallbacks(previewSeek); handler.removeCallbacks(releasePreview); handler.postDelayed(releasePreview, 20_000)
    }

    /** Gets the preview stream connected as soon as the bar appears, so pictures are ready when you skip. */
    private var scrubWasPlaying: Boolean? = null
    private var scrubDim: View? = null
    private var mainSeekPos = 0L
    private val mainSeek: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_BUFFERING) { handler.postDelayed(this, 200); return }
            if (kotlin.math.abs(p.currentPosition - mainSeekPos) > 1500) p.seekTo(mainSeekPos)
        }
    }

    private fun warmPreview() {
        // Streams no longer use a second connection for pictures (Dodgy Box allows one) - the film itself shows them.
        if (live || !url.startsWith("file:") || previewFor == url) return
        val p = player ?: return
        handler.removeCallbacks(releasePreview)
        previewWant = p.currentPosition
        handler.removeCallbacks(previewSeek); handler.postDelayed(previewSeek, 300)
    }
    private var scrubTime: android.widget.TextView? = null

    /**
     * While skipping along the bar: the time you'd land on, in a bubble above the bar, plus a picture of
     * that moment for downloaded films and recordings. (Streaming pictures would need a second connection
     * to Dodgy Box, which it limits - and that could cut off the programme you're watching.)
     */
    private fun setUpScrubPreview() {
        val bar = view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress) ?: return
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        val iv = android.widget.ImageView(c).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0xFF000000.toInt()); setStroke(dp(c, 2f), 0xFFFFFFFF.toInt()) }
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            visibility = View.GONE
        }
        // Pictures: from a small silent second player that jumps to wherever you're skipping to (films and episodes
        // streamed from Dodgy Box), or straight from the file for downloads and recordings.
        val frame = FrameLayout(c).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0xFF000000.toInt()); setStroke(dp(c, 2f), 0xFFFFFFFF.toInt()) }
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            visibility = View.GONE
        }
        val tex = android.view.TextureView(c)
        frame.addView(tex, FrameLayout.LayoutParams(-1, -1))
        box.addView(frame, LinearLayout.LayoutParams(dp(c, 256f), dp(c, 144f)).apply { bottomMargin = dp(c, 6f) })
        previewFrame = frame; previewTex = tex
        box.addView(iv, LinearLayout.LayoutParams(dp(c, 240f), dp(c, 135f)).apply { bottomMargin = dp(c, 6f) })
        val time = GlassUi.text(c, "", 18f, R.font.inter_semibold).apply {
            background = GlassUi.panel(c, 12f, 0x60)
            setPadding(dp(c, 14f), dp(c, 6f), dp(c, 14f), dp(c, 6f))
        }
        box.addView(time, LinearLayout.LayoutParams(-2, -2))
        // While the picture is in the box, the screen behind is dimmed (the full-size picture pauses there).
        val dim = View(c).apply { setBackgroundColor(0xB3000000.toInt()); visibility = View.GONE }
        // Inside the video area only (under the controls, so the bar stays bright).
        view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)?.addView(dim, FrameLayout.LayoutParams(-1, -1))
        scrubDim = dim
        (view.parent as? FrameLayout)?.addView(box, FrameLayout.LayoutParams(dp(c, 256f), -2, Gravity.BOTTOM or Gravity.START))
        thumbView = iv; scrubBox = box; scrubTime = time
        bar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(t: androidx.media3.ui.TimeBar, pos: Long) {
                handler.removeCallbacks(hideScrub)
                // Streaming: the film itself shows where you're skipping to (paused, jumping to the nearest keyframe) -
                // one connection to Dodgy Box, so nothing gets refused or cut off.
                val p = player
                if (p != null && !live && !url.startsWith("file:") && scrubWasPlaying == null) {
                    scrubWasPlaying = p.playWhenReady
                    p.playWhenReady = false
                    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                    // Netflix-style: the film's own picture goes into the small box above the bar while you scrub.
                    previewTex?.let { tex -> p.setVideoTextureView(tex); previewFrame?.visibility = View.VISIBLE; scrubDim?.visibility = View.VISIBLE }
                }
                onScrub(bar, pos)
            }
            override fun onScrubMove(t: androidx.media3.ui.TimeBar, pos: Long) { handler.removeCallbacks(hideScrub); onScrub(bar, pos) }
            override fun onScrubStop(t: androidx.media3.ui.TimeBar, pos: Long, canceled: Boolean) {
                scrubWasPlaying?.let { was ->
                    scrubWasPlaying = null
                    handler.removeCallbacks(mainSeek)
                    player?.let { p ->
                        // Back to full screen.
                        previewTex?.let { p.clearVideoTextureView(it) }
                        (view.videoSurfaceView as? android.view.SurfaceView)?.let { p.setVideoSurfaceView(it) }
                            ?: (view.videoSurfaceView as? android.view.TextureView)?.let { p.setVideoTextureView(it) }
                        previewFrame?.visibility = View.GONE; scrubDim?.visibility = View.GONE
                        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                        if (!canceled) p.seekTo(pos)                  // exactly where you stopped
                        p.playWhenReady = was
                    }
                }
                // Stays up a moment so you see where you landed (the picture often arrives just after you stop).
                handler.removeCallbacks(hideScrub); handler.postDelayed(hideScrub, 1500)
            }
        })
    }

    private fun onScrub(bar: View, pos: Long) {
        val iv = thumbView ?: return
        val box = scrubBox ?: return
        val p = player ?: return
        if (live || p.duration <= 0) return
        scrubTime?.text = clock(pos / 1000) + "  /  " + clock(p.duration / 1000)
        // Place it above the point on the bar.
        val loc = IntArray(2); bar.getLocationInWindow(loc)
        val w = box.layoutParams.width
        val x = loc[0] + bar.width * (pos.toFloat() / p.duration) - w / 2f
        (box.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = x.toInt().coerceIn(dp(this@PlayerActivity, 24f), (view.width - w - dp(this@PlayerActivity, 24f)).coerceAtLeast(0))
            bottomMargin = (view.height - loc[1]) + dp(this@PlayerActivity, 12f)
        }
        box.visibility = View.VISIBLE
        box.requestLayout()
        if (!url.startsWith("file:")) {
            // Streaming: the film jumps there on screen, a moment after you stop moving (not asked for every step).
            // Only once you pause on a spot (not every step), and never while the last jump is still loading - each jump
            // asks Dodgy Box for that part of the film again (holding the button made 15 of them in 10 seconds).
            if (scrubWasPlaying != null) { mainSeekPos = pos; handler.removeCallbacks(mainSeek); handler.postDelayed(mainSeek, 450) }
            return
        }
        val want = pos
        thumbWant = want
        val u = url
        thumbExec.execute {
            if (thumbWant != want) return@execute   // a newer position is waiting
            val bmp = try {
                if (thumbFor != u) {
                    thumbRetriever?.release()
                    thumbRetriever = android.media.MediaMetadataRetriever().apply { setDataSource(android.net.Uri.parse(u).path) }
                    thumbFor = u
                }
                if (android.os.Build.VERSION.SDK_INT >= 27) thumbRetriever?.getScaledFrameAtTime(want * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                else thumbRetriever?.getFrameAtTime(want * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Throwable) { thumbFor = null; null }
            runOnUiThread { if (bmp != null && thumbWant == want) { iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE } }
        }
    }

    private fun hidePauseInfo() {
        handler.removeCallbacks(showPause)
        if (!::pauseInfo.isInitialized || pauseInfo.visibility != View.VISIBLE) return
        pauseInfo.animate().alpha(0f).setDuration(200).withEndAction { pauseInfo.visibility = View.GONE }.start()
    }

    override fun onDestroy() {
        thumbExec.execute { try { thumbRetriever?.release() } catch (e: Throwable) {}; thumbRetriever = null }
        thumbExec.shutdown()
        sleepHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** True while something is on screen in the player (updates wait until it closes). */
        @Volatile var watching = false
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null

        /** Home was pressed: stop the picture and sound straight away (before the home screen appears). */
        fun pauseForHome() {
            val a = current?.get() ?: return
            a.runOnUiThread { try { a.saveProgress(); a.player?.pause() } catch (_: Exception) {} }
        }
        /** The live channel on screen (so the corner scores can leave out the match you're watching). */
        @Volatile var liveChannel: String? = null
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
            // Opens with the same zoom as an app.
            @Suppress("DEPRECATION") (c as? Activity)?.overridePendingTransition(R.anim.player_in, R.anim.screen_dim)
        }
    }
}
