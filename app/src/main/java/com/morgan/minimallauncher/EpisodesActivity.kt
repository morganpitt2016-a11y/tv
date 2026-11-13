package com.morgan.minimallauncher

import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * A show's page, laid out like Apple TV's: the show's artwork filling the screen, its name and story, one big
 * Play / Resume button, seasons as tabs, episodes as a row of picture cards, then cast and "You Might Also Like".
 * Hold OK on an episode to download it.
 */
class EpisodesActivity : Activity() {

    private lateinit var seasonList: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var heading: TextView
    private lateinit var playBtn: TextView
    private lateinit var listBtn: TextView
    private lateinit var about: TextView
    private lateinit var extras: LinearLayout
    private lateinit var scroll: android.widget.ScrollView
    private var name = ""
    private var image: String? = null
    private var title: WatchCatalog.Title? = null
    private var all: List<Xtream.Episode> = emptyList()
    private val adapter = EpisodeAdapter()
    private var season = -1
    private var epsText = "Loading episodes…"
    private var facts = ""
    private fun setHeading() { heading.text = listOf(epsText, facts).filter { it.isNotBlank() }.joinToString("  ·  ") }
    private val seasonViews = LinkedHashMap<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        image = intent.getStringExtra(EXTRA_IMAGE)
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        // The show's artwork, full screen, fading into black at the bottom and left.
        val backdrop = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.9f }
        image?.let { ImageLoader.load(backdrop, it, ImageLoader.CARD) }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0x99000000.toInt(), 0x1A000000)) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF000000.toInt(), 0xE6000000.toInt(), 0x00000000)) },
            FrameLayout.LayoutParams(-1, dp(c, 380f), Gravity.BOTTOM))

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(c, 120f), 0, dp(c, 60f)) }
        scroll = android.widget.ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(col) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        fun inset(v: View, top: Float = 0f, w: Int = -2) = col.addView(v, LinearLayout.LayoutParams(w, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, top) })

        inset(GlassUi.text(c, name, 40f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(12f, 0f, 2f, 0x99000000.toInt()) },
            w = dp(c, 640f))
        heading = GlassUi.text(c, "Loading episodes…", 13.5f, R.font.inter_medium, 0xCCFFFFFF.toInt())
        inset(heading, 6f)
        about = GlassUi.text(c, "", 14f, R.font.inter_regular, 0xD9FFFFFF.toInt()).apply { maxLines = 3; setLineSpacing(0f, 1.2f); visibility = View.GONE }
        inset(about, 10f, dp(c, 560f))

        // Play / Resume, then My List.
        val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        playBtn = bigButton("▶  Play", primary = true) { playFromButton() }
        buttons.addView(playBtn, LinearLayout.LayoutParams(-2, dp(c, 48f)))
        listBtn = bigButton("+  My List", primary = false) { toggleList() }
        listBtn.visibility = View.GONE
        buttons.addView(listBtn, LinearLayout.LayoutParams(-2, dp(c, 48f)).apply { marginStart = dp(c, 14f) })
        inset(buttons, 20f)

        // Seasons as tabs along a line.
        seasonList = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(dp(c, 48f), 0, dp(c, 48f), 0); addView(seasonList) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 30f) })

        // Episodes: a row of picture cards.
        list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@EpisodesActivity.adapter
            clipChildren = false; clipToPadding = false
            setPadding(dp(c, 56f), dp(c, 12f), dp(c, 56f), dp(c, 12f))
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        col.addView(list, LinearLayout.LayoutParams(-1, dp(c, 268f)))
        extras = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(extras, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        playBtn.requestFocus()

        val app = applicationContext
        Bg.run {
            val eps = try { Xtream.findSeries(app, name, 0)?.let { Xtream.episodes(app, it) } } catch (e: Exception) { null }.orEmpty()
            runOnUiThread { if (!isDestroyed) show(eps) }
        }
        // The show's story, cast and similar shows (from the film and TV guide).
        WatchCatalog.titles(this, search = name, first = 1, types = listOf("SHOW")) { l ->
            val t = l?.firstOrNull() ?: return@titles
            if (isDestroyed) return@titles
            title = t
            if (t.description.isNotBlank()) { about.text = t.description; about.visibility = View.VISIBLE }
            if (image == null) (t.backdrop ?: t.poster)?.let { ImageLoader.load(backdrop, it, ImageLoader.CARD) }
            listBtn.visibility = View.VISIBLE
            listBtn.text = if (WatchCatalog.inMyList(this, t)) "✓  In My List" else "+  My List"
            WatchCatalog.detail(this, t) { d -> if (d != null && !isDestroyed) showExtras(d, t) }
        }
    }

    private fun bigButton(label: String, primary: Boolean, click: () -> Unit) = GlassUi.text(this, label, 16f, R.font.inter_semibold).apply {
        val c = this@EpisodesActivity
        gravity = Gravity.CENTER; setPadding(dp(c, 26f), 0, dp(c, 26f), 0)
        isFocusable = true; isClickable = true
        fun paint(f: Boolean) {
            background = GradientDrawable().apply { cornerRadius = dp(c, 12f).toFloat(); setColor(if (f) 0xFFFFFFFF.toInt() else if (primary) 0xE6FFFFFF.toInt() else 0x40FFFFFF) }
            setTextColor(if (f || primary) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        paint(false)
        setOnFocusChangeListener { v, has -> paint(has); Motion.spring(v, if (has) 1.06f else 1f); if (has) scroll.smoothScrollTo(0, 0) }
        setOnClickListener { click() }
    }

    private fun toggleList() {
        val t = title ?: return
        val now = WatchCatalog.toggleMyList(this, t)
        listBtn.text = if (now) "✓  In My List" else "+  My List"
    }

    /** The episode Play would start: where you got to, else the first. */
    private fun nextUp(): Xtream.Episode? {
        val last = WatchHistory.last(applicationContext, name)
        val cur = all.firstOrNull { it.season == last?.season && it.number == last?.episode } ?: return all.firstOrNull()
        val at = WatchHistory.seconds(applicationContext, Xtream.episodeUrl(applicationContext, cur))
        val total = cur.minutes * 60
        // Finished it (or nearly): the next one.
        return if (total > 0 && at > total - 120) all.getOrNull(all.indexOf(cur) + 1) ?: cur else cur
    }

    private fun playFromButton() { nextUp()?.let { play(it) } }

    private fun refreshPlayButton() {
        val e = nextUp() ?: return
        val at = WatchHistory.seconds(applicationContext, Xtream.episodeUrl(applicationContext, e))
        val total = e.minutes * 60
        playBtn.text = when {
            at > 30 && total > 0 -> "▶  Resume S${e.season} E${e.number}  ·  ${((total - at) / 60).coerceAtLeast(1)} min left"
            at > 30 -> "▶  Resume S${e.season} E${e.number}"
            e == all.firstOrNull() -> "▶  Play S${e.season} E${e.number}"
            else -> "▶  Play S${e.season} E${e.number}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) { refreshPlayButton(); adapter.notifyDataSetChanged() }
    }

    private fun showExtras(d: WatchCatalog.Detail, t: WatchCatalog.Title) {
        val c = this
        extras.removeAllViews()
        fun header(s: String) = extras.addView(GlassUi.text(c, s, 15f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, 22f) })
        val facts = listOfNotNull(t.year.takeIf { it > 0 }?.toString(), d.age.ifBlank { null }, t.genres.take(3).joinToString(", ").ifBlank { null },
            t.imdb?.let { "IMDb %.1f".format(it) }).joinToString("  ·  ")
        this.facts = facts; setHeading()
        if (d.cast.isNotEmpty() || d.directors.isNotEmpty()) {
            header("Cast & Crew")
            val row = LinearLayout(c).apply { setPadding(dp(c, 56f), dp(c, 10f), dp(c, 56f), dp(c, 6f)); clipChildren = false; clipToPadding = false }
            for ((who, role) in d.cast.map { it to "Actor" } + d.directors.map { it to "Director" }) {
                val chip = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
                    setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.08f) }
                    setOnClickListener { startActivity(android.content.Intent(c, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_TAB, "search").putExtra(WatchActivity.EXTRA_QUERY, who)) }
                }
                val initials = who.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
                chip.addView(GlassUi.text(c, initials, 22f, R.font.inter_semibold).apply {
                    gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
                }, LinearLayout.LayoutParams(dp(c, 76f), dp(c, 76f)))
                chip.addView(GlassUi.text(c, who, 12f, R.font.inter_semibold).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(dp(c, 110f), -2).apply { topMargin = dp(c, 6f) })
                chip.addView(GlassUi.text(c, role, 10.5f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER })
                row.addView(chip, LinearLayout.LayoutParams(dp(c, 110f), -2).apply { marginEnd = dp(c, 14f) })
            }
            extras.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row) })
        }
        if (d.similar.isNotEmpty()) {
            header("You Might Also Like")
            val row = LinearLayout(c).apply { setPadding(dp(c, 56f), dp(c, 10f), dp(c, 56f), dp(c, 6f)); clipChildren = false; clipToPadding = false }
            for (s in d.similar.take(14)) {
                val card = FrameLayout(c).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF1C1C20.toInt()) }
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(c, 10f).toFloat()) } }
                    isFocusable = true; isClickable = true
                    contentDescription = s.name
                    setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.1f) }
                    setOnClickListener { startActivity(android.content.Intent(c, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_TAB, "search").putExtra(WatchActivity.EXTRA_QUERY, s.name)) }
                }
                card.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; s.poster?.let { ImageLoader.load(this, it) } }, FrameLayout.LayoutParams(-1, -1))
                row.addView(card, LinearLayout.LayoutParams(dp(c, 104f), dp(c, 156f)).apply { marginEnd = dp(c, 14f) })
            }
            extras.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row) })
        }
    }

    private fun show(eps: List<Xtream.Episode>) {
        all = eps.sortedWith(compareBy({ it.season }, { it.number }))
        if (all.isEmpty()) { epsText = "No episodes found on Dodgy Box"; setHeading(); playBtn.text = "Not on Dodgy Box"; return }
        val seasons = all.map { it.season }.distinct()
        epsText = if (seasons.size == 1) "${all.size} episodes" else "${seasons.size} seasons  ·  ${all.size} episodes"
        setHeading()
        val last = WatchHistory.last(applicationContext, name)
        for (s in seasons) {
            val v = GlassUi.text(this, "Season $s", 16f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
                isFocusable = true; isClickable = true
                setOnClickListener { selectSeason(s); list.getChildAt(0)?.requestFocus() }
                setOnFocusChangeListener { v, has ->
                    Motion.spring(v, if (has) 1.08f else 1f)
                    v.background = if (has) GradientDrawable().apply { cornerRadius = dp(context, 18f).toFloat(); setColor(0xFFFFFFFF.toInt()) } else null
                    if (has) selectSeason(s) else (v as TextView).setTextColor(if (season == s) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                    if (has) (v as TextView).setTextColor(0xFF000000.toInt())
                }
            }
            seasonViews[s] = v
            seasonList.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(this@EpisodesActivity, 8f) })
        }
        val start = last?.season?.takeIf { it in seasons } ?: seasons.first()
        selectSeason(start)
        refreshPlayButton()
        // Lined up on the episode you were up to.
        val idx = adapter.items.indexOfFirst { it.season == last?.season && it.number == last?.episode }.coerceAtLeast(0)
        list.scrollToPosition(idx)
    }

    private fun selectSeason(s: Int) {
        if (s == season) return
        // Never change the list while it's in the middle of laying itself out (moving focus can land here then).
        if (list.isComputingLayout) { list.post { selectSeason(s) }; return }
        season = s
        seasonViews.forEach { (k, v) -> if (!v.isFocused) v.setTextColor(if (k == s) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()) }
        adapter.items = all.filter { it.season == s }
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private fun play(e: Xtream.Episode) {
        val app = applicationContext
        val url = Xtream.episodeUrl(app, e)
        WatchHistory.record(app, name, e.season, e.number)
        val rest = all.dropWhile { it.id != e.id }.drop(1).map { Triple(Xtream.episodeUrl(app, it), it.season, it.number) }
        PlayerActivity.play(this, url, name, live = false, subtitle = "Season ${e.season}, Episode ${e.number}", eyebrow = "SERIES",
            series = name, queue = rest, image = image, season = e.season, episode = e.number)
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Xtream.Episode> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val c = parent.context
            // A picture card: the episode's still, then its number and name, length and a line of story.
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(dp(c, 270f), -1).apply { marginEnd = dp(c, 18f) }
            }
            val thumb = FrameLayout(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(c, 12f).toFloat(); setColor(0xFF26262B.toInt()) }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(c, 12f).toFloat()) } }
            }
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumb.addView(img, FrameLayout.LayoutParams(-1, -1))
            val bar = View(c).apply { setBackgroundColor(0xE6FFFFFF.toInt()) }
            thumb.addView(bar, FrameLayout.LayoutParams(0, dp(c, 4f), Gravity.BOTTOM))
            card.addView(thumb, LinearLayout.LayoutParams(-1, dp(c, 152f)))
            card.setOnFocusChangeListener { _, has -> Motion.focus(thumb, has, 1.07f) }
            val title = GlassUi.text(c, "", 14.5f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val meta = GlassUi.text(c, "", 11.5f, R.font.inter_medium, 0x99FFFFFF.toInt())
            val plot = GlassUi.text(c, "", 12f, R.font.inter_regular, 0xB3FFFFFF.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10f) })
            card.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 2f) })
            card.addView(plot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 4f) })
            card.tag = arrayOf(img, bar, title, plot, meta, thumb)
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val e = items[pos]
            @Suppress("UNCHECKED_CAST") val v = h.itemView.tag as Array<View>
            val c = h.itemView.context
            (v[0] as ImageView).let { iv -> if (e.image != null) ImageLoader.load(iv, e.image, ImageLoader.CARD) else iv.setImageDrawable(null) }
            val url = Xtream.episodeUrl(applicationContext, e)
            val at = WatchHistory.seconds(applicationContext, url)
            val total = e.minutes * 60
            v[1].post { v[1].layoutParams.width = if (at > 30 && total > 0) (v[5].width * at.toFloat() / total).toInt().coerceAtMost(v[5].width) else 0; v[1].requestLayout() }
            (v[2] as TextView).text = "${e.number}.  ${e.title.ifBlank { "Episode ${e.number}" }}"
            (v[3] as TextView).text = e.plot.ifBlank { "" }
            (v[3] as TextView).visibility = if (e.plot.isBlank()) View.GONE else View.VISIBLE
            val left = if (at > 30 && total > 0) "  ·  ${((total - at) / 60).coerceAtLeast(1)} min left" else ""
            (v[4] as TextView).text = (if (e.minutes > 0) "${e.minutes} min" else "") + left
            h.itemView.contentDescription = "Episode ${e.number}: ${e.title}"
            h.itemView.setOnClickListener { play(e) }
            h.itemView.setOnLongClickListener {
                GlassDialog(this@EpisodesActivity).title("${e.number}.  ${e.title.ifBlank { "Episode ${e.number}" }}")
                    .button("Play", GlassDialog.Style.BOLD) { play(e) }
                    .button("Download for Later") { GlassUi.toast(this@EpisodesActivity, Downloads.start(applicationContext, url, e.title, name, e.season, e.number, e.image ?: image)) }
                    .button("Download the Rest of Season ${e.season}") {
                        val msgs = all.filter { it.season == e.season && it.number >= e.number }.map { x ->
                            Downloads.start(applicationContext, Xtream.episodeUrl(applicationContext, x), x.title, name, x.season, x.number, x.image ?: image) }
                        GlassUi.toast(this@EpisodesActivity, msgs.lastOrNull() ?: "Nothing to download")
                    }
                    .button("Cancel").show()
                true
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_IMAGE = "image"
    }
}
