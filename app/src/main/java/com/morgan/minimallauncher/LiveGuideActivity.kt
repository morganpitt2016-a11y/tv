package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.morgan.minimallauncher.GlassUi.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen TV guide, laid out like a set-top box guide: a picture-in-picture of the highlighted channel
 * and the list of categories down the left; the programme's details across the top; the grid below it.
 * Up / Down move one channel, Channel +/- a page, Left from the first programme opens the categories,
 * OK watches (or offers to record), and the red / record button records.
 */
class LiveGuideActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var epg: EpgView
    private lateinit var catList: LinearLayout
    private lateinit var title: TextView
    private lateinit var desc: TextView
    private lateinit var meta: TextView
    private lateinit var clockView: TextView
    private lateinit var pip: PlayerView
    private lateinit var storage: TextView
    private var player: ExoPlayer? = null
    private val cats = LinkedHashMap<String, String>()
    private val catViews = LinkedHashMap<String, TextView>()
    private var current: String? = null
    private var pipChannel = -1
    private val pipJob = Runnable { playPip() }
    private var selected: EpgView.Row? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF0B0F1E.toInt()) }
        // A soft gradient, like a guide's backdrop.
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B2A5A.toInt(), 0xFF0B0F1E.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        // ---- left panel ----
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = GlassUi.panel(c, 0f, 0x18) }
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 20f), dp(c, 14f), dp(c, 20f), dp(c, 8f)) }
        top.addView(GlassUi.text(c, "TV Guide", 13f, R.font.inter_semibold, 0xB3FFFFFF.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        clockView = GlassUi.text(c, "", 13f, R.font.inter_medium, 0xB3FFFFFF.toInt())
        top.addView(clockView)
        left.addView(top)
        pip = PlayerView(c).apply {
            useController = false
            setShutterBackgroundColor(0xFF000000.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(c, 10f).toFloat()) } }
        }
        left.addView(pip, LinearLayout.LayoutParams(-1, dp(c, 134f)).apply { setMargins(dp(c, 20f), 0, dp(c, 20f), 0) })
        left.addView(GlassUi.text(c, "Categories", 17f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(c, 24f), dp(c, 12f), 0, dp(c, 4f)) })
        catList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 14f), 0, dp(c, 14f), dp(c, 10f)) }
        left.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(catList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        storage = GlassUi.text(c, "", 10.5f, R.font.inter_medium, 0x80FFFFFF.toInt())
        left.addView(storage, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(c, 24f), 0, 0, dp(c, 10f)) })
        root.addView(left, FrameLayout.LayoutParams(dp(c, 250f), -1, Gravity.START))

        // ---- right side ----
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 24f), dp(c, 16f), dp(c, 28f), dp(c, 14f)) }
        val head = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        title = GlassUi.text(c, "", 21f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        desc = GlassUi.text(c, "", 12.5f, R.font.inter_medium, 0xD9FFFFFF.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        meta = GlassUi.text(c, "", 12f, R.font.inter_medium, 0x99FFFFFF.toInt())
        head.addView(title); head.addView(desc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 3f) }); head.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
        right.addView(head, LinearLayout.LayoutParams(-1, dp(c, 92f)))
        epg = EpgView(c)
        right.addView(epg, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(c, 250f) })
        setContentView(root)

        val fmt = SimpleDateFormat("h:mma", Locale.UK)
        val tick = object : Runnable { override fun run() { clockView.text = fmt.format(Date()).lowercase(); handler.postDelayed(this, 20_000) } }
        tick.run()

        epg.onSelect = { row, prog ->
            selected = row
            title.text = prog?.title ?: row.ch.name
            desc.text = prog?.desc?.takeIf { it.isNotBlank() } ?: "Live on ${row.ch.name}"
            meta.text = if (prog == null) row.ch.name else {
                val mins = ((prog.stop - prog.start) / 60_000).toInt()
                val started = if (prog.start <= System.currentTimeMillis()) "Started at" else "Starts at"
                "${row.ch.name}   ·   ${mins}m   ·   $started ${fmt.format(Date(prog.start)).lowercase()}"
            }
            handler.removeCallbacks(pipJob); handler.postDelayed(pipJob, 900)        // only once you settle on a channel
        }
        epg.onPlay = { row -> watch(row.ch) }
        epg.onProgramme = { row, prog -> recordDialog(row, prog) }
        epg.onRecord = { row, prog -> recordDialog(row, prog ?: row.guide?.now()) }
        epg.onLeftEdge = { catViews[current]?.requestFocus() }
        storage.text = Recordings.storageSummary(this)

        // Categories, then the channels of the first one.
        val app = applicationContext
        Thread {
            val ready = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { LiveGuide.load(app) { ready.countDown() } }
            try { ready.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
            val all = Xtream.liveCategories(app)
            fun rank(n: String) = when { n.trim().startsWith("UK", true) -> 0; n.trim().startsWith("LIVE", true) -> 10; n.trim().startsWith("24/7", true) -> 20; n.trim().startsWith("US", true) -> 30; else -> 40 }
            val ids = all.keys.filterNot { all[it].orEmpty().contains("radio", true) }.sortedWith(compareBy { rank(all[it].orEmpty()) })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (id in ids) {
                    cats[id] = all[id].orEmpty().replace(Regex("\\s*[~|]+\\s*"), " · ").trim()
                    val v = categoryView(id, cats[id]!!)
                    catViews[id] = v
                    catList.addView(v, LinearLayout.LayoutParams(-1, dp(c, 36f)).apply { bottomMargin = dp(c, 2f) })
                }
                ids.firstOrNull()?.let { selectCategory(it, focusGrid = true) }
            }
        }.start()
    }

    private fun categoryView(id: String, name: String) = GlassUi.text(this, name, 14f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12f), 0, dp(context, 8f), 0)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isFocusable = true; isClickable = true
        background = null
        setOnClickListener { selectCategory(id, focusGrid = true) }
        setOnFocusChangeListener { v, has ->
            Motion.spring(v, if (has) 1.04f else 1f)
            v.background = if (has) GlassUi.panel(context, 10f, 0x55) else if (id == current) GlassUi.panel(context, 10f, 0x24) else null
            if (has) selectCategory(id, focusGrid = false)
        }
        setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_RIGHT)) { epg.requestFocus(); true } else false
        }
    }

    private fun selectCategory(id: String, focusGrid: Boolean) {
        if (id == current) { if (focusGrid) epg.requestFocus(); return }
        current = id
        catViews.forEach { (k, v) -> if (!v.isFocused) v.background = if (k == id) GlassUi.panel(this, 10f, 0x24) else null }
        val app = applicationContext
        Thread {
            val list = try { Xtream.liveInCategory(app, id) } catch (e: Exception) { emptyList() }.sortedBy { it.num }
            runOnUiThread {
                if (current != id || isDestroyed) return@runOnUiThread
                epg.setRows(list.take(200).map { EpgView.Row(it, LiveGuide.guideFor(it.name)) })
                if (focusGrid) epg.requestFocus()
            }
        }.start()
    }

    // ---- picture-in-picture ----

    private fun playPip() {
        val row = selected ?: return
        if (row.ch.id == pipChannel) return
        pipChannel = row.ch.id
        val p = player ?: ExoPlayer.Builder(this).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(1500, 10_000, 500, 1000).build()).build().also { player = it; pip.player = it }
        p.stop()
        p.setMediaItem(MediaItem.fromUri(Xtream.liveUrl(applicationContext, row.ch.id)))
        p.playWhenReady = true
        p.prepare()
    }

    override fun onStop() { super.onStop(); handler.removeCallbacks(pipJob); player?.release(); player = null; pipChannel = -1 }
    override fun onStart() { super.onStart(); if (selected != null) handler.postDelayed(pipJob, 300) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---- actions ----

    private fun watch(ch: Xtream.Live) {
        val app = applicationContext
        player?.release(); player = null; pipChannel = -1
        PlayerActivity.play(this, Xtream.liveUrl(app, ch.id), ch.name, live = true, subtitle = LiveGuide.nowNext(app, ch.name),
            eyebrow = "LIVE", alt = Xtream.liveUrl(app, ch.id, hls = true), liveId = ch.id, liveCat = ch.category)
    }

    private fun recordDialog(row: EpgView.Row, prog: LiveGuide.Programme?) {
        val g = row.guide
        if (g == null || prog == null) { GlassUi.toast(this, "No programme information for this channel to record from"); return }
        val on = prog.isOn()
        val d = GlassDialog(this).title(prog.title)
            .message("${row.ch.name}\n${storageNote()}")
        if (on) d.button("Watch", GlassDialog.Style.BOLD) { watch(row.ch) }
        val scheduled = TiviRecorder.isScheduled(this, g, prog)
        if (on) d.button("Record now") { Recordings.start(applicationContext, g.name, prog.title, prog.stop, prog.image); GlassUi.toast(this, "Recording ${prog.title}") }
        else if (scheduled) d.button("Cancel recording", GlassDialog.Style.DESTRUCTIVE) { TiviRecorder.cancel(this, TiviRecorder.idFor(g.name, prog.start)); GlassUi.toast(this, "Recording cancelled") }
        else d.button("Record", GlassDialog.Style.BOLD) { TiviRecorder.schedule(this, g, prog); GlassUi.toast(this, "Will record ${prog.title}") }
        d.button("Record series") {
            val n = TiviRecorder.addSeries(this, g, prog, LiveGuide.cached() ?: listOf(g))
            GlassUi.toast(this, if (n > 0) "Series linked - $n episode${if (n == 1) "" else "s"} scheduled" else "Series linked")
        }
        d.button("Cancel")
        d.show()
    }

    private fun storageNote() = "Recordings are kept on this TV: ${Recordings.storageSummary(this)}. Plug in a USB drive for more room."

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back from the categories goes to the guide grid first, then out.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && !epg.isFocused) { epg.requestFocus(); return true }
        return super.dispatchKeyEvent(event)
    }
}
