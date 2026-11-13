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
    private var allUk: List<String> = emptyList()
    private lateinit var recScroll: ScrollView
    private lateinit var recList: LinearLayout
    private var catNames: Map<String, String> = emptyMap()
    private val mainMap = LinkedHashMap<String, List<String>>()
    private var otherIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Activation.guard(this)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Xtream.isExpired(this)) window.decorView.post { if (!isDestroyed) Xtream.showExpired(this) }
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        // The same blurred, darkened wallpaper as the rest of the launcher.
        GlassUi.installBackdrop(root)
        val accent = try { ProfileStyle.of(c).accent } catch (e: Exception) { 0xFFFF453A.toInt() }

        // ---- left panel ----
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = GlassUi.panel(c, 0f, 0x14) }
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
        epg = EpgView(c).apply { this.accent = accent; fetcher = { row, cb -> ProviderEpg.fetch(applicationContext, row.ch, cb) } }
        right.addView(epg, LinearLayout.LayoutParams(-1, 0, 1f))
        recList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 6f), dp(c, 4f), dp(c, 6f), dp(c, 30f)) }
        recScroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; visibility = View.GONE; addView(recList) }
        right.addView(recScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(c, 250f) })
        setContentView(root)

        val fmt = SimpleDateFormat("h.mma", Locale.UK)
        val tick = object : Runnable { override fun run() { clockView.text = fmt.format(Date()).lowercase(); handler.postDelayed(this, 20_000) } }
        tick.run()

        epg.onSelect = { row, prog ->
            selected = row
            title.text = prog?.title ?: row.ch.name
            desc.text = prog?.desc?.takeIf { it.isNotBlank() } ?: "Live on ${row.ch.name}"
            meta.text = if (prog == null) row.ch.name else {
                val mins = ((prog.stop - prog.start) / 60_000).toInt()
                val len = if (mins >= 60) "${mins / 60}h${if (mins % 60 > 0) " ${mins % 60}m" else ""}" else "${mins}m"
                val q = when { Regex("(?i)\\b(uhd|4k)\\b").containsMatchIn(row.ch.name) -> "UHD"; Regex("(?i)\\b(fhd|hd)\\b").containsMatchIn(row.ch.name) -> "HD"; else -> "" }
                val started = if (prog.start <= System.currentTimeMillis()) "Started at" else "Starts at"
                listOf(row.ch.name.replace(Regex("(?i)\\s*\\b(uhd|fhd|hd|4k)\\b"), "").trim(), len, q, "$started ${fmt.format(Date(prog.start)).lowercase()}").filter { it.isNotEmpty() }.joinToString("   ·   ")
            }
            handler.removeCallbacks(pipJob); handler.postDelayed(pipJob, 900)        // only once you settle on a channel
        }
        epg.onPlay = { row -> watch(row.ch) }
        epg.onProgramme = { row, prog -> recordDialog(row, prog) }
        epg.onRecord = { row, prog -> recordDialog(row, prog ?: row.guide?.now()) }
        epg.onLeftEdge = { catViews[current]?.requestFocus() }
        storage.text = Recordings.storageSummary(this)
        title.text = "Loading the guide…"

        // Categories, then the channels of the first one.
        val app = applicationContext
        Bg.run {
            if (LiveGuide.cached() == null) {
                val ready = java.util.concurrent.CountDownLatch(1)
                runOnUiThread { LiveGuide.load(app) { ready.countDown() } }
                try { ready.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
            } else runOnUiThread { LiveGuide.load(app) { } }      // keeps it fresh without waiting
            val all = Xtream.liveCategories(app)
            fun rank(n: String) = when { n.trim().startsWith("UK", true) -> 0; n.trim().startsWith("LIVE", true) -> 10; n.trim().startsWith("24/7", true) -> 20; n.trim().startsWith("US", true) -> 30; else -> 40 }
            fun ids(pred: (String) -> Boolean) = all.filter { pred(it.value.trim()) && !it.value.contains("radio", true) }.keys.toList()
            fun uk(n: String, vararg kw: String) = n.startsWith("UK", true) && kw.any { n.contains(it, true) }
            // The categories in the order a set-top box shows them; anything else goes under "Others".
            val map = LinkedHashMap<String, List<String>>()
            map["+1"] = ids { uk(it, "plus one") }
            map["Documentaries"] = ids { uk(it, "documentar") }
            map["Kids"] = ids { uk(it, "kids") }
            map["Movies"] = ids { uk(it, "movies") }
            map["Sport"] = ids { uk(it, "sky sports", "tnt sports") || (it.startsWith("UK", true) && it.endsWith("sports", true)) }
            map["News"] = ids { uk(it, "news") }
            map["Entertainment"] = ids { uk(it, "entertainment") }
            map["Music"] = ids { uk(it, "music") }
            map["Shopping"] = ids { uk(it, "shopping") }
            map["Religion"] = ids { it.contains("religio", true) }
            map["International"] = ids { it.startsWith("INT", true) }
            val claimed = map.values.flatten().toSet()
            val others = all.keys.filter { it !in claimed && !all[it].orEmpty().contains("radio", true) }.sortedWith(compareBy { rank(all[it].orEmpty()) })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                catNames = all
                mainMap.clear(); map.filter { it.value.isNotEmpty() }.forEach { (k, v) -> mainMap[k] = v }
                otherIds = others
                allUk = listOf("Entertainment", "Documentaries", "Kids", "Movies", "News", "Music", "Sport").flatMap { mainMap[it].orEmpty() }.distinct()
                showCategories(sub = false)
                selectCategory(if (Kids.isOn(this) && mainMap.containsKey("Kids")) "m:Kids" else "_all", focusGrid = true)
            }
        }
    }

    private fun addCategory(id: String, name: String) {
        cats[id] = name
        val v = categoryView(id, name)
        catViews[id] = v
        catList.addView(v, LinearLayout.LayoutParams(-1, dp(this, 34f)).apply { bottomMargin = dp(this@LiveGuideActivity, 1f) })
    }

    /** The main list (Favourites, All channels, HD, +1 ... International, Others) or, under "Others", everything else. */
    private fun showCategories(sub: Boolean) {
        catList.removeAllViews(); catViews.clear(); cats.clear()
        if (!sub && Kids.isOn(this)) {
            // Kids profile: children's channels only.
            if (mainMap.containsKey("Kids")) addCategory("m:Kids", "Kids")
            addCategory("_fav", "Favourites")
        } else if (!sub) {
            addCategory("_rec", "Recordings"); addCategory("_fav", "Favourites"); addCategory("_all", "All channels"); addCategory("_hd", "HD")
            mainMap.keys.forEach { addCategory("m:$it", it) }
            if (otherIds.isNotEmpty()) addCategory("_others", "Others  ›")
        } else {
            addCategory("_back", "‹  Categories")
            for (id in otherIds) addCategory(id, catNames[id].orEmpty().replace(Regex("^\\s*UK\\s*[~|:-]*\\s*", RegexOption.IGNORE_CASE), "UK · ").replace(Regex("\\s*[~|]+\\s*"), " · ").trim().ifBlank { "Channels" })
        }
    }

    private fun categoryView(id: String, name: String) = GlassUi.text(this, name, 14f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12f), 0, dp(context, 8f), 0)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isFocusable = true; isClickable = true
        background = null
        setOnClickListener {
            when (id) {
                "_others" -> { showCategories(sub = true); catViews.values.firstOrNull()?.requestFocus() }
                "_back" -> { showCategories(sub = false); (catViews[current] ?: catViews["_all"])?.requestFocus() }
                else -> selectCategory(id, focusGrid = true)
            }
        }
        setOnFocusChangeListener { v, has ->
            Motion.spring(v, if (has) 1.04f else 1f)
            v.background = if (has) GlassUi.panel(context, 10f, 0x55) else if (id == current) GlassUi.panel(context, 10f, 0x24) else null
            if (has && id != "_others" && id != "_back") selectCategory(id, focusGrid = false)
        }
        setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_RIGHT)) { if (current == "_rec") recList.getChildAt(0)?.requestFocus() else epg.requestFocus(); true } else false
        }
    }

    private val HD = Regex("(?i)\\b(uhd|fhd|hd|4k)\\b")

    private fun selectCategory(id: String, focusGrid: Boolean) {
        if (id == current) { if (focusGrid) (if (id == "_rec") recList.getChildAt(0) else epg)?.requestFocus(); return }
        current = id
        catViews.forEach { (k, v) -> if (!v.isFocused) v.background = if (k == id) GlassUi.panel(this, 10f, 0x24) else null }
        if (id == "_rec") { showRecordings(focusGrid); return }
        recScroll.visibility = View.GONE; epg.visibility = View.VISIBLE
        val app = applicationContext
        Bg.run {
            val list: List<Xtream.Live> = try {
                when (id) {
                    "_fav" -> Xtream.best(Xtream.liveById(app, Xtream.favourites(app)))
                    "_hd" -> {
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                        val parts = allUk.map { gid -> pool.submit<List<Xtream.Live>> { try { Xtream.liveInCategory(app, gid) } catch (e: Exception) { emptyList() } } }
                        val merged = parts.flatMap { it.get() }; pool.shutdown()
                        Xtream.best(merged).filter { HD.containsMatchIn(it.name) }
                    }
                    "_all" -> {
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                        val parts = allUk.map { gid -> pool.submit<List<Xtream.Live>> { try { Xtream.liveInCategory(app, gid) } catch (e: Exception) { emptyList() } } }
                        val merged = parts.flatMap { it.get() }
                        pool.shutdown()
                        Xtream.best(merged)
                    }
                    else -> if (id.startsWith("m:")) {
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                        val parts = mainMap[id.removePrefix("m:")].orEmpty().map { gid -> pool.submit<List<Xtream.Live>> { try { Xtream.liveInCategory(app, gid) } catch (e: Exception) { emptyList() } } }
                        val merged = parts.flatMap { it.get() }; pool.shutdown()
                        Xtream.best(merged)
                    } else Xtream.liveGroup(app, id)
                }
            } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (current != id || isDestroyed) return@runOnUiThread
                epg.setRows(list.take(250).map { EpgView.Row(it, LiveGuide.guideFor(it.name)) })
                if (list.isEmpty()) {
                    title.text = if (id == "_fav") "No favourites yet" else "No channels here"
                    desc.text = if (id == "_fav") "Highlight a channel and press Menu to add it to your favourites." else ""
                    meta.text = ""
                }
                if (focusGrid) epg.requestFocus()
            }
        }
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

    override fun onStop() { super.onStop(); Updater.guideOpen = false; handler.removeCallbacks(pipJob); player?.release(); player = null; pipChannel = -1 }
    override fun onStart() { super.onStart(); Updater.guideOpen = true; if (selected != null) handler.postDelayed(pipJob, 300) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---- actions ----

    private fun watch(ch: Xtream.Live) {
        val app = applicationContext
        player?.release(); player = null; pipChannel = -1
        PlayerActivity.play(this, Xtream.liveUrl(app, ch.id), ch.name, live = true, subtitle = LiveGuide.nowNext(app, ch.name),
            eyebrow = "LIVE", alt = Xtream.liveUrl(app, ch.id, hls = true), liveId = ch.id, liveCat = ch.category)
    }

    /**
     * Hold OK on a channel (or press Record / Menu): Record, Record series, Favourites.
     * If the programme is already on Dodgy Box, "recording" saves it to Recordings straight away - the episode,
     * or with Record series the whole series - so it is ready at once. Otherwise it records the broadcast.
     */
    private fun recordDialog(row: EpgView.Row, prog: LiveGuide.Programme?) {
        val g = row.guide
        val fav = row.ch.id in Xtream.favourites(this)
        val d = GlassDialog(this).title(prog?.title ?: row.ch.name).message(row.ch.name + (prog?.let { "  ·  " + SimpleDateFormat("h.mma", Locale.UK).format(Date(it.start)).lowercase() } ?: ""))
        d.button("Record", GlassDialog.Style.BOLD) { record(row, prog, series = false) }
        if (prog != null) d.button("Record series") { record(row, prog, series = true) }
        if (prog == null || prog.isOn()) d.button("Watch now") { watch(row.ch) }
        if (prog != null && prog.start > System.currentTimeMillis()) {
            val set = Reminders.has(this, row.ch.id, prog.start)
            d.button(if (set) "Cancel Reminder" else "Remind Me") {
                if (set) { Reminders.remove(this, row.ch.id, prog.start); GlassUi.toast(this, "Reminder cancelled") }
                else {
                    Reminders.add(this, Reminders.Item(row.ch.id, row.ch.name, row.ch.category, prog.title, prog.start))
                    GlassUi.toast(this, "We’ll remind you when ${prog.title} starts")
                }
            }
        }
        d.button(if (fav) "Remove from Favourites" else "Add to Favourites") {
            val added = Xtream.toggleFavourite(applicationContext, row.ch.id)
            GlassUi.toast(this, if (added) "${row.ch.name} added to Favourites" else "${row.ch.name} removed from Favourites")
            if (current == "_fav") { val keep = current; current = null; selectCategory(keep!!, focusGrid = true) }
        }
        d.button("Cancel")
        d.show()
    }

    private fun record(row: EpgView.Row, prog: LiveGuide.Programme?, series: Boolean) {
        val app = applicationContext
        val title = prog?.let { LibraryRecordings.cleanTitle(it.title) } ?: row.ch.name
        GlassUi.toast(this, "Looking for $title on Dodgy Box…")
        Bg.run {
            val show = try { if (prog != null) Xtream.findSeries(app, title, 0) else null } catch (e: Exception) { null }
            val eps = try { show?.let { Xtream.episodes(app, it) } } catch (e: Exception) { null }.orEmpty()
            val film = if (show == null && prog != null) try { Xtream.findVod(app, title, 0) } catch (e: Exception) { null } else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val now = System.currentTimeMillis()
                when {
                    show != null && series -> {
                        LibraryRecordings.add(app, LibraryRecordings.Item("series", title, 0, 0, show.cover ?: prog?.image, row.ch.name, now))
                        GlassUi.toast(this, "$title recorded - the whole series (${eps.size} episodes) is in Recordings")
                    }
                    show != null && prog != null && LibraryRecordings.matchEpisode(prog, eps) != null -> {
                        val e = LibraryRecordings.matchEpisode(prog, eps)!!
                        LibraryRecordings.add(app, LibraryRecordings.Item("episode", title, e.season, e.number, e.image ?: show.cover, row.ch.name, now))
                        GlassUi.toast(this, "$title S${e.season} E${e.number} recorded - it's in Recordings")
                    }
                    film != null -> {
                        LibraryRecordings.add(app, LibraryRecordings.Item("film", title, 0, 0, film.icon ?: prog?.image, row.ch.name, now))
                        GlassUi.toast(this, "$title recorded - it's in Recordings")
                    }
                    else -> recordBroadcast(row, prog, series)       // not in the library: record it off air
                }
            }
        }
    }

    /** Records the programme as it's broadcast (or links the series in the guide) when Dodgy Box doesn't have it. */
    private fun recordBroadcast(row: EpgView.Row, prog: LiveGuide.Programme?, series: Boolean) {
        val g = row.guide
        when {
            series && g != null && prog != null -> {
                val n = TiviRecorder.addSeries(this, g, prog, LiveGuide.cached() ?: listOf(g))
                GlassUi.toast(this, "Series linked - ${if (n > 0) "$n episode${if (n == 1) "" else "s"} will record" else "new episodes will record"}")
            }
            prog == null || prog.isOn() -> {
                Recordings.start(applicationContext, g?.name ?: row.ch.name, prog?.title ?: row.ch.name, prog?.stop ?: (System.currentTimeMillis() + 3_600_000L), prog?.image)
                GlassUi.toast(this, "Recording ${prog?.title ?: row.ch.name}")
            }
            g != null && prog != null -> { TiviRecorder.schedule(this, g, prog); GlassUi.toast(this, "Will record ${prog.title}") }
            else -> GlassUi.toast(this, "Can't record this channel")
        }
    }

    // ---- Recordings (above Favourites) ----

    private fun showRecordings(focus: Boolean) {
        epg.visibility = View.GONE
        recScroll.visibility = View.VISIBLE
        recList.removeAllViews()
        val lib = LibraryRecordings.all(this)
        val files = Recordings.shown(this)
        title.text = "Recordings"
        desc.text = if (lib.isEmpty() && files.isEmpty()) "Nothing yet. Hold OK on any programme in the guide and choose Record or Record series." else "Everything you've recorded, ready to watch. Hold OK on one to remove it."
        meta.text = Recordings.storageSummary(this)
        for (it in lib) {
            val sub = when (it.kind) { "series" -> "Whole series  ·  from ${it.channel}"; "episode" -> "S${it.season} E${it.episode}  ·  from ${it.channel}"; else -> "Film  ·  from ${it.channel}" }
            recList.addView(recRow(it.name, sub, it.image, { playLibrary(it) }) {
                GlassDialog(this).title(it.name).message(sub).button("Remove from Recordings", GlassDialog.Style.DESTRUCTIVE) {
                    LibraryRecordings.remove(applicationContext, it); showRecordings(true)
                }.button("Cancel").show()
            })
        }
        val df = SimpleDateFormat("EEE d MMM, h.mma", Locale.UK)
        for (r in files) {
            val sub = (if (Recordings.isRecording(r)) "● Recording now  ·  " else "") + "${r.channel}  ·  ${df.format(Date(r.start)).replace("AM", "am").replace("PM", "pm")}"
            recList.addView(recRow(r.title, sub, r.image, {
                PlayerActivity.play(this, android.net.Uri.fromFile(java.io.File(r.file)).toString(), r.title, live = false, subtitle = r.channel, eyebrow = "RECORDING")
            }) {
                GlassDialog(this).title(r.title).message(sub).button("Delete recording", GlassDialog.Style.DESTRUCTIVE) {
                    Recordings.delete(applicationContext, r); showRecordings(true)
                }.button("Cancel").show()
            })
        }
        if (focus) recList.post { recList.getChildAt(0)?.requestFocus() ?: catViews["_rec"]?.requestFocus() }
    }

    private fun recRow(name: String, sub: String, image: String?, open: () -> Unit, hold: () -> Unit): View {
        val c = this
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 14f)
            setPadding(dp(c, 10f), dp(c, 8f), dp(c, 16f), dp(c, 8f))
            isFocusable = true; isClickable = true; isLongClickable = true
            setOnClickListener { open() }
            setOnLongClickListener { hold(); true }
            setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) { catViews["_rec"]?.requestFocus(); true } else false }
        }
        val img = android.widget.ImageView(c).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0xFF26262B.toInt()) }
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        if (image != null) ImageLoader.load(img, image, ImageLoader.CARD)
        row.addView(img, LinearLayout.LayoutParams(dp(c, 112f), dp(c, 63f)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        col.addView(GlassUi.rowText(c, name, 15f, R.font.inter_semibold, GlassUi.primaryText()))
        col.addView(GlassUi.rowText(c, sub, 12f, R.font.inter_medium, GlassUi.secondaryText()), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 14f) })
        row.layoutParams = LinearLayout.LayoutParams(-1, dp(c, 80f)).apply { bottomMargin = dp(c, 4f) }
        return row
    }

    private fun playLibrary(item: LibraryRecordings.Item) {
        val app = applicationContext
        if (item.kind == "series") {
            startActivity(android.content.Intent(this, EpisodesActivity::class.java).putExtra(EpisodesActivity.EXTRA_NAME, item.name).putExtra(EpisodesActivity.EXTRA_IMAGE, item.image))
            return
        }
        Bg.run {
            if (item.kind == "film") {
                val v = try { Xtream.findVod(app, item.name, 0) } catch (e: Exception) { null }
                runOnUiThread { if (v != null) PlayerActivity.play(this, Xtream.vodUrl(app, v), item.name, live = false, eyebrow = "RECORDING", image = item.image) else GlassUi.toast(this, "${item.name} is no longer on Dodgy Box") }
                return@run
            }
            val eps = try { Xtream.findSeries(app, item.name, 0)?.let { Xtream.episodes(app, it) } } catch (e: Exception) { null }.orEmpty().sortedWith(compareBy({ it.season }, { it.number }))
            val e = eps.firstOrNull { it.season == item.season && it.number == item.episode }
            runOnUiThread {
                if (e == null) { GlassUi.toast(this, "That episode is no longer on Dodgy Box"); return@runOnUiThread }
                WatchHistory.record(app, item.name, e.season, e.number)
                val rest = eps.dropWhile { it.id != e.id }.drop(1).map { Triple(Xtream.episodeUrl(app, it), it.season, it.number) }
                PlayerActivity.play(this, Xtream.episodeUrl(app, e), item.name, live = false, subtitle = "Season ${e.season}, Episode ${e.number}", eyebrow = "RECORDING",
                    series = item.name, queue = rest, image = item.image, season = e.season, episode = e.number)
            }
        }
    }

    private fun storageNote() = "Recordings are kept on this TV: ${Recordings.storageSummary(this)}. Plug in a USB drive for more room."

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back from the categories goes to the guide grid first, then out.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && !epg.isFocused) { epg.requestFocus(); return true }
        return super.dispatchKeyEvent(event)
    }
}
