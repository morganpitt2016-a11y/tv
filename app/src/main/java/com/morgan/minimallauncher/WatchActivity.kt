package com.morgan.minimallauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * "Watch": one place for everything on all your apps. Big artwork of
 * the selected title at the top, rails of films and shows below (live TV
 * apps, what you opened recently, trending and popular on each service
 * you have), search by typing or voice, and one press opens the title in
 * the right app.
 */
class WatchActivity : AppCompatActivity() {

    private lateinit var heroA: ImageView
    private lateinit var heroB: ImageView
    private var heroShowingA = false
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroText: TextView
    private lateinit var heroWhere: TextView
    private lateinit var scroll: ScrollView
    private lateinit var railsBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHero: Runnable? = null
    private var installed: List<WatchCatalog.Provider> = emptyList()

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { search(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }

        // Hero artwork, fading between titles.
        heroA = hero(); heroB = hero()
        // Own layer, so swapping which artwork is on top never covers the text.
        val heroLayer = FrameLayout(c)
        heroLayer.addView(heroA, FrameLayout.LayoutParams(-1, -1))
        heroLayer.addView(heroB, FrameLayout.LayoutParams(-1, -1))
        root.addView(heroLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0xB3000000.toInt(), 0x33000000, 0x00000000))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF000000.toInt(), 0xE6000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(c, 330f), Gravity.BOTTOM))

        // Title details.
        val info = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = GlassUi.text(c, "Watch", 30f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroMeta = GlassUi.text(c, "Everything on your apps", 13f, R.font.inter_medium, 0xB3FFFFFF.toInt())
        heroText = GlassUi.text(c, "", 13.5f, R.font.inter_regular, 0xD9FFFFFF.toInt()).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f)
        }
        heroWhere = GlassUi.text(c, "", 12.5f, R.font.inter_semibold, 0xFFFFFFFF.toInt())
        info.addView(heroTitle)
        info.addView(heroMeta, lp(6f))
        info.addView(heroText, lp(10f))
        info.addView(heroWhere, lp(10f))
        root.addView(info, FrameLayout.LayoutParams(dp(c, 470f), -2).apply { setMargins(dp(c, 56f), dp(c, 40f), 0, 0) })

        // Search button (top right).
        val searchButton = FrameLayout(c).apply {
            setBackgroundResource(R.drawable.glass_circle_bg)
            isFocusable = true; isClickable = true
            contentDescription = "Search"
            setOnClickListener { askSearch() }
            addView(ImageView(c).apply {
                setImageResource(R.drawable.ic_lucide_search)
                imageTintList = androidx.core.content.ContextCompat.getColorStateList(c, R.color.icon_on_glass)
                isDuplicateParentStateEnabled = true
            }, FrameLayout.LayoutParams(dp(c, 18f), dp(c, 18f), Gravity.CENTER))
        }
        GlassUi.focusLift(searchButton, 1.08f)
        root.addView(searchButton, FrameLayout.LayoutParams(dp(c, 43f), dp(c, 43f), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(c, 34f), dp(c, 44f), 0)
        })

        // Rails.
        railsBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, dp(c, 300f)) // lets the last rail scroll up to the top
        }
        scroll = RailScroll(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            addView(railsBox)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(c, 252f) })

        setContentView(root)
        UiSounds.attach(this)

        // Focus in a rail: glide that rail to the top of the rails area.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            val rail = new?.let { railOf(it) } ?: return@addOnGlobalFocusChangeListener
            scroll.smoothScrollTo(0, rail.top)
        }

        heroWhere.text = "Loading…"
        Thread {
            installed = try { WatchCatalog.installedProviders(this) } catch (e: Exception) { emptyList() }
            handler.post { if (!isDestroyed) buildRails() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Refresh "Continue Watching" after coming back from an app.
        if (railsBox.childCount > 0) refreshHistoryRail()
    }

    // ---------- Rails ----------

    private class Rail(val view: LinearLayout, val list: RecyclerView)

    private val rails = mutableMapOf<String, Rail>()

    private fun buildRails() {
        railsBox.removeAllViews()
        rails.clear()
        heroWhere.text = if (installed.isEmpty()) "" else "Your apps: " + installed.joinToString(", ") { it.name }

        val live = WatchCatalog.LIVE_APPS.mapNotNull { pkg -> AppListHelper.loadLaunchableTvApps(this).firstOrNull { it.packageName == pkg } }
        if (live.isNotEmpty()) addAppRail("Live TV", live)
        refreshHistoryRail()

        val shorts = installed.map { it.short }
        addTitleRail("trending", if (shorts.isEmpty()) "Trending" else "Trending on Your Apps") { done ->
            WatchCatalog.titles(this, sort = "TRENDING", packages = shorts, done = done)
        }
        for (p in installed) {
            addTitleRail("p_${p.short}", "Popular on ${p.name}") { done ->
                WatchCatalog.titles(this, packages = listOf(p.short), done = done)
            }
        }
        addTitleRail("films", "Films") { done -> WatchCatalog.titles(this, packages = shorts, types = listOf("MOVIE"), done = done) }
        addTitleRail("shows", "Shows") { done -> WatchCatalog.titles(this, packages = shorts, types = listOf("SHOW"), done = done) }
        railsBox.post { firstFocusable()?.requestFocus() }
    }

    private fun refreshHistoryRail() {
        val history = WatchCatalog.history(this)
        val existing = rails["history"]
        if (history.isEmpty()) { existing?.let { railsBox.removeView(it.view); rails.remove("history") }; return }
        if (existing != null) { (existing.list.adapter as? TitleAdapter)?.update(history); return }
        val rail = makeRail("Continue Watching")
        rail.list.adapter = TitleAdapter(history)
        rails["history"] = rail
        val liveIndex = if (rails.containsKey("live")) 1 else 0
        railsBox.addView(rail.view, liveIndex)
    }

    private fun addAppRail(title: String, apps: List<AppEntry>) {
        val rail = makeRail(title, height = 120f)
        rail.list.adapter = AppTileAdapter(apps)
        rails["live"] = rail
        railsBox.addView(rail.view)
    }

    private fun addTitleRail(key: String, title: String, load: ((List<WatchCatalog.Title>?) -> Unit) -> Unit) {
        val rail = makeRail(title)
        val adapter = TitleAdapter(emptyList())
        rail.list.adapter = adapter
        rails[key] = rail
        railsBox.addView(rail.view)
        load { titles ->
            if (isDestroyed) return@load
            when {
                titles == null -> showNote(rail, "Couldn’t load - check the internet connection")
                titles.isEmpty() -> { railsBox.removeView(rail.view); rails.remove(key) }
                else -> adapter.update(titles)
            }
        }
    }

    private fun showNote(rail: Rail, text: String) {
        rail.view.addView(GlassUi.text(this, text, 12f, R.font.inter_regular, 0x99FFFFFF.toInt()), lp(4f).apply { marginStart = dp(this@WatchActivity, 56f) })
    }

    private fun makeRail(title: String, height: Float = 196f): Rail {
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        box.addView(GlassUi.text(c, title, 15f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(c, 56f); topMargin = dp(c, 10f)
        })
        val list = RecyclerView(c).apply {
            layoutManager = object : LinearLayoutManager(c, HORIZONTAL, false) {
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                    val dx = child.left - dp(c, 56f)
                    if (dx != 0) parent.smoothScrollBy(dx, 0, DecelerateInterpolator(2f))
                    return true
                }
            }
            clipChildren = false; clipToPadding = false
            setPadding(dp(c, 56f), dp(c, 12f), dp(c, 56f), dp(c, 8f))
            itemAnimator = null
        }
        box.addView(list, LinearLayout.LayoutParams(-1, dp(c, height)))
        return Rail(box, list)
    }

    private fun railOf(v: View): View? {
        var p: Any? = v.parent
        var last: View = v
        while (p is View) {
            if (p === railsBox) return last
            last = p
            p = p.parent
        }
        return null
    }

    private fun firstFocusable(): View? {
        for (i in 0 until railsBox.childCount) {
            val list = (railsBox.getChildAt(i) as? ViewGroup)?.getChildAt(1) as? RecyclerView ?: continue
            list.getChildAt(0)?.let { return it }
        }
        return null
    }

    /** ScrollView that leaves scrolling to us (smooth rail-by-rail glide). */
    private class RailScroll(c: Context) : ScrollView(c) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
    }

    // ---------- Hero ----------

    private fun showHero(t: WatchCatalog.Title) {
        pendingHero?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            heroTitle.text = t.name
            heroMeta.text = listOfNotNull(
                t.year.takeIf { it > 0 }?.toString(),
                if (t.type == "SHOW") "Series" else "Film",
                t.imdb?.let { "IMDb %.1f".format(it) },
                t.genres.take(3).joinToString(", ").ifEmpty { null },
            ).joinToString("  ·  ")
            heroText.text = t.description
            val on = t.offers.filter { it.provider.installed != null }.map { it.provider.name }.distinct()
            heroWhere.text = if (on.isNotEmpty()) "On " + on.joinToString(", ") else
                t.offers.firstOrNull()?.let { "On ${it.provider.name} (not installed)" } ?: ""
            val incoming = if (heroShowingA) heroB else heroA
            val outgoing = if (heroShowingA) heroA else heroB
            heroShowingA = !heroShowingA
            ImageLoader.load(incoming, t.backdrop ?: t.poster, fade = false) { bmp ->
                if (bmp == null) return@load
                incoming.bringToFront()
                incoming.alpha = 0f
                incoming.scaleX = 1.04f; incoming.scaleY = 1.04f
                incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(DecelerateInterpolator()).start()
                outgoing.animate().alpha(0f).setDuration(700).start()
            }
        }
        pendingHero = r
        handler.postDelayed(r, 280)
    }

    private fun showAppHero(app: AppEntry) {
        pendingHero?.let { handler.removeCallbacks(it) }
        heroTitle.text = app.label
        heroMeta.text = "Live TV"
        heroText.text = if (app.packageName == "ar.tvplayer.tv") "Your channels and TV guide in TiviMate." else "Live channels and catch-up."
        heroWhere.text = ""
    }

    // ---------- Opening ----------

    private fun choose(t: WatchCatalog.Title) {
        val mine = t.offers.filter { it.provider.installed != null }
        val others = t.offers.filter { it.provider.installed == null }.map { it.provider.name }.distinct()
        val d = GlassDialog(this).title(if (t.year > 0) "${t.name} (${t.year})" else t.name)
        val msg = buildString {
            append(t.description.take(260))
            if (others.isNotEmpty()) append("\n\nAlso on: ").append(others.take(6).joinToString(", "))
            if (mine.isEmpty()) append("\n\nNot on any app installed on this TV.")
        }
        if (msg.isNotBlank()) d.message(msg)
        mine.forEachIndexed { i, offer ->
            val how = when (offer.kind) { "FLATRATE", "FREE", "ADS" -> "Watch on"; "RENT" -> "Rent on"; "BUY" -> "Buy on"; else -> "Open in" }
            d.button("$how ${offer.provider.name}", if (i == 0) GlassDialog.Style.BOLD else GlassDialog.Style.NORMAL) {
                WatchCatalog.open(this, t, offer)
            }
        }
        d.button("Search Google TV") { WatchCatalog.searchGoogleTv(this, t.name) }
        d.button("Cancel")
        d.show()
    }

    // ---------- Search ----------

    private fun askSearch() {
        val d = GlassDialog(this).title("Search").message("Films and shows on all your apps.").input("Title, actor…")
        d.button("Search", GlassDialog.Style.BOLD) { if (d.inputText.isNotBlank()) search(d.inputText) }
        d.button("Speak Instead") {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search films and shows"))
            } catch (e: ActivityNotFoundException) { GlassUi.toast(this, "Voice search isn’t available") }
        }
        d.button("Cancel")
        d.show()
    }

    private fun search(query: String) {
        rails.remove("search")?.let { railsBox.removeView(it.view) }
        val rail = makeRail("Results for “$query”")
        val adapter = TitleAdapter(emptyList())
        rail.list.adapter = adapter
        rails["search"] = rail
        railsBox.addView(rail.view, 0)
        scroll.smoothScrollTo(0, 0)
        WatchCatalog.titles(this, search = query, first = 30) { titles ->
            when {
                titles == null -> showNote(rail, "Couldn’t search - check the internet connection")
                titles.isEmpty() -> showNote(rail, "Nothing found")
                else -> {
                    adapter.update(titles)
                    rail.list.post { rail.list.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }

    // ---------- Adapters ----------

    private class Holder(val root: FrameLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    private fun card(parent: ViewGroup, w: Float, h: Float): Holder {
        val c = parent.context
        val root = FrameLayout(c).apply {
            layoutParams = RecyclerView.LayoutParams(dp(c, w), dp(c, h)).apply { marginEnd = dp(c, 14f) }
            background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF1E1D22.toInt()) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.tile_bezel)
            isFocusable = true; isClickable = true
        }
        // Name shown until the artwork arrives.
        val label = GlassUi.text(c, "", 11f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(dp(c, 8f), 0, dp(c, 8f), 0); maxLines = 3
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        return Holder(root, image, label)
    }

    private fun focusEffect(v: View, onFocus: () -> Unit) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f)
                .translationZ(if (has) dp(this, 14f).toFloat() else 0f)
                .setInterpolator(DecelerateInterpolator(2f)).setDuration(240).start()
            GlassUi.spotlight(view, has)
            if (has) onFocus()
        }
    }

    private inner class TitleAdapter(private var items: List<WatchCatalog.Title>) : RecyclerView.Adapter<Holder>() {
        fun update(list: List<WatchCatalog.Title>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = card(parent, 104f, 156f)
        override fun onBindViewHolder(h: Holder, position: Int) {
            val t = items[position]
            h.label.text = t.name
            h.root.contentDescription = t.name
            ImageLoader.load(h.image, t.poster)
            h.root.setOnClickListener { choose(t) }
            focusEffect(h.root) { showHero(t) }
        }
    }

    private inner class AppTileAdapter(private val apps: List<AppEntry>) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = apps.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = card(parent, 160f, 90f)
        override fun onBindViewHolder(h: Holder, position: Int) {
            val app = apps[position]
            h.label.text = app.label
            h.root.contentDescription = "Open ${app.label}"
            val banner = try {
                packageManager.getApplicationBanner(app.packageName) ?: app.launchIntent?.let { packageManager.getActivityBanner(it) }
            } catch (e: PackageManager.NameNotFoundException) { null }
            if (banner != null) {
                h.image.scaleType = ImageView.ScaleType.CENTER_CROP
                h.image.setImageDrawable(banner)
            } else {
                h.image.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = dp(this@WatchActivity, 22f)
                h.image.setPadding(pad, pad, pad, pad)
                h.image.setImageBitmap(TileRenderer.render(app.icon, dp(this@WatchActivity, 48f)))
            }
            h.root.setOnClickListener { AppActions.launch(this@WatchActivity, app, h.root) }
            focusEffect(h.root) { showAppHero(app) }
        }
    }

    // ---------- Bits ----------

    private fun hero() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WatchActivity, top) }
}
