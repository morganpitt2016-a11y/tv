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
 * Pick an episode: seasons down the left, then each episode with its picture, name, a short description and
 * its length. Opens on the episode you were last watching.
 */
class EpisodesActivity : Activity() {

    private lateinit var seasonList: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var heading: TextView
    private var name = ""
    private var image: String? = null
    private var all: List<Xtream.Episode> = emptyList()
    private val adapter = EpisodeAdapter()
    private var season = -1
    private val seasonViews = LinkedHashMap<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        image = intent.getStringExtra(EXTRA_IMAGE)
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        GlassUi.installBackdrop(root)

        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 56f), dp(c, 48f), dp(c, 16f), dp(c, 24f)) }
        left.addView(GlassUi.text(c, name, 26f, R.font.inter_semibold).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        heading = GlassUi.text(c, "Loading episodes…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
        left.addView(heading, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 6f); bottomMargin = dp(c, 22f) })
        seasonList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(c, 8f), dp(c, 4f), dp(c, 8f), dp(c, 8f)) }
        left.addView(android.widget.ScrollView(c).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(seasonList)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = -dp(c, 8f) })
        root.addView(left, FrameLayout.LayoutParams(dp(c, 300f), -1, Gravity.START))

        list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c)
            adapter = this@EpisodesActivity.adapter
            clipChildren = false; clipToPadding = false
            setPadding(dp(c, 12f), dp(c, 40f), dp(c, 56f), dp(c, 60f))
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(list, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(c, 300f) })
        setContentView(root)

        val app = applicationContext
        Bg.run {
            val eps = try { Xtream.findSeries(app, name, 0)?.let { Xtream.episodes(app, it) } } catch (e: Exception) { null }.orEmpty()
            runOnUiThread { if (!isDestroyed) show(eps) }
        }
    }

    private fun show(eps: List<Xtream.Episode>) {
        all = eps.sortedWith(compareBy({ it.season }, { it.number }))
        if (all.isEmpty()) { heading.text = "No episodes found on Dodgy Box"; return }
        val seasons = all.map { it.season }.distinct()
        heading.text = if (seasons.size == 1) "${all.size} episodes" else "${seasons.size} seasons  ·  ${all.size} episodes"
        val last = WatchHistory.last(applicationContext, name)
        for (s in seasons) {
            val v = GlassUi.rowText(this, "Season $s", 16f, R.font.inter_medium, GlassUi.primaryText()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16f), 0, dp(context, 12f), 0)
                background = GlassUi.rowBackground(context, 12f)
                isFocusable = true; isClickable = true
                isDuplicateParentStateEnabled = false
                setTextColor(GlassUi.primaryText())
                setOnClickListener { selectSeason(s); list.getChildAt(0)?.requestFocus() }
                setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.04f else 1f); if (has) selectSeason(s) }
            }
            seasonViews[s] = v
            seasonList.addView(v, LinearLayout.LayoutParams(-1, dp(this, 46f)).apply { bottomMargin = dp(this@EpisodesActivity, 4f) })
        }
        val start = last?.season?.takeIf { it in seasons } ?: seasons.first()
        selectSeason(start)
        // Open on the episode you were up to.
        val idx = adapter.items.indexOfFirst { it.season == last?.season && it.number == last?.episode }.coerceAtLeast(0)
        list.scrollToPosition(idx)
        list.post { (list.findViewHolderForAdapterPosition(idx)?.itemView ?: list.getChildAt(0))?.requestFocus() }
    }

    private fun selectSeason(s: Int) {
        if (s == season) return
        // Never change the list while it's in the middle of laying itself out (moving focus can land here then).
        if (list.isComputingLayout) { list.post { selectSeason(s) }; return }
        season = s
        seasonViews.forEach { (k, v) -> v.alpha = if (k == s) 1f else 0.62f }
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
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GlassUi.rowBackground(c, 16f)
                setPadding(dp(c, 14f), dp(c, 10f), dp(c, 18f), dp(c, 10f))
                isFocusable = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, dp(c, 118f)).apply { bottomMargin = dp(c, 6f) }
                setOnFocusChangeListener { v, has -> Motion.spring(v, if (has) 1.02f else 1f) }
            }
            val thumb = FrameLayout(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF26262B.toInt()) }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(c, 10f).toFloat()) } }
            }
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumb.addView(img, FrameLayout.LayoutParams(-1, -1))
            val bar = View(c).apply { setBackgroundColor(0xE6FFFFFF.toInt()) }
            thumb.addView(bar, FrameLayout.LayoutParams(0, dp(c, 3f), Gravity.BOTTOM))
            row.addView(thumb, LinearLayout.LayoutParams(dp(c, 170f), dp(c, 96f)))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }     // so the text turns dark on the white focused row
            val title = GlassUi.rowText(c, "", 16f, R.font.inter_semibold, GlassUi.primaryText())
            val plot = GlassUi.rowText(c, "", 12.5f, R.font.inter_regular, GlassUi.secondaryText()).apply { maxLines = 3; setLineSpacing(0f, 1.15f) }
            val meta = GlassUi.rowText(c, "", 11.5f, R.font.inter_medium, GlassUi.secondaryText())
            col.addView(title); col.addView(plot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 4f) })
            col.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 4f) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 16f) })
            row.tag = arrayOf(img, bar, title, plot, meta, thumb)
            return object : RecyclerView.ViewHolder(row) {}
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
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_IMAGE = "image"
    }
}
