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
import android.text.format.DateFormat
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp
import java.util.Date

/**
 * "Watch": everything on all your apps in one tvOS-style place.
 *
 *  Home     - Continue Watching, My List, Top 10, "Because you watched…",
 *             and a row for every service (Netflix, Disney+, Prime Video,
 *             Paramount+, Apple TV+, NOW, iPlayer, ITVX…), installed or not.
 *  Films / Shows - popular, trending and genre rows.
 *  Live TV  - a free Freeview guide (on now / next / later) that plays in
 *             TiviMate or the broadcaster's app.
 *  My List  - titles you saved, per profile.
 *  Search   - type or speak; finds films, shows and what's on TV.
 *
 * Selecting a title opens a full detail page: cast, seasons, where to
 * watch (with each service's logo), trailer, My List and More Like This.
 */
/** Loads a row of titles and hands them to the callback. */
typealias TitleLoader = ((List<WatchCatalog.Title>?) -> Unit) -> Unit

class WatchActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var heroA: ImageView
    private lateinit var heroB: ImageView
    private var heroShowingA = false
    private var heroUrl: String? = null
    private lateinit var eyebrow: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroText: TextView
    private lateinit var heroWhere: TextView
    private lateinit var info: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout
    private var detail: FrameLayout? = null
    private var detailReturn: View? = null

    private var tab = "home"
    private var providers: List<WatchCatalog.Provider> = emptyList()
    private var installed: List<WatchCatalog.Provider> = emptyList()
    private var featured: List<WatchCatalog.Title> = emptyList()
    private var featuredIndex = 0
    private var pendingHero: Runnable? = null
    private var lastSearch: String? = null
    private var buildToken = 0

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { search(it) }
    }

    // ---------- Setup ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }

        val heroLayer = FrameLayout(c)
        heroA = heroImage(); heroB = heroImage()
        heroLayer.addView(heroA, FrameLayout.LayoutParams(-1, -1))
        heroLayer.addView(heroB, FrameLayout.LayoutParams(-1, -1))
        root.addView(heroLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0xA6000000.toInt(), 0x26000000, 0x00000000))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF000000.toInt(), 0xF2000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(c, 330f), Gravity.BOTTOM))
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(c, 90f), Gravity.TOP))

        // Title details.
        info = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        eyebrow = GlassUi.text(c, "", 10.5f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f }
        heroTitle = GlassUi.text(c, "Watch", 32f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroMeta = GlassUi.text(c, "", 12.5f, R.font.inter_medium, 0xB3FFFFFF.toInt())
        heroText = GlassUi.text(c, "", 13.5f, R.font.inter_regular, 0xD9FFFFFF.toInt()).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f)
        }
        heroWhere = GlassUi.text(c, "", 12.5f, R.font.inter_semibold)
        info.addView(eyebrow)
        info.addView(heroTitle, lp(4f))
        info.addView(heroMeta, lp(6f))
        info.addView(heroText, lp(10f))
        info.addView(heroWhere, lp(10f))
        root.addView(info, FrameLayout.LayoutParams(dp(c, 480f), -2).apply { setMargins(dp(c, 56f), dp(c, 72f), 0, 0) })

        // Tabs.
        nav = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        for ((id, label) in listOf("home" to "Home", "films" to "Films", "shows" to "Shows", "live" to "Live TV", "list" to "My List", "search" to "Search")) {
            nav.addView(tabButton(id, label), LinearLayout.LayoutParams(-2, dp(c, 30f)).apply { marginEnd = dp(c, 4f) })
        }
        root.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(c, 22f) })

        // Rows.
        rows = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, dp(c, 320f))
        }
        scroll = RowScroll(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            addView(rows)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(c, 262f) })

        setContentView(root)
        UiSounds.attach(this)

        // Glide each row to the top of the rows area as focus reaches it.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            if (new == null) return@addOnGlobalFocusChangeListener
            if (detail != null) { detailFocusMoved(new); return@addOnGlobalFocusChangeListener }
            val row = rowOf(new) ?: return@addOnGlobalFocusChangeListener
            val guideRow = row.getTag(R.id.blur_radius) == "guide"
            scroll.smoothScrollTo(0, (row.top - if (guideRow) dp(this, 64f) else 0).coerceAtLeast(0))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    detail != null -> closeDetail()
                    collectionFrom != null -> closeCollection()
                    rowOf(currentFocus ?: nav) != null -> { scroll.smoothScrollTo(0, 0); (nav.getChildAt(tabIndex()) ?: nav).requestFocus() }
                    else -> finish()
                }
            }
        })

        eyebrow.text = "WATCH"
        heroMeta.text = "Everything on all your apps"
        heroWhere.text = "Loading…"
        Thread {
            providers = try { WatchCatalog.featuredProviders(this) } catch (e: Exception) { emptyList() }
            installed = providers.filter { it.installed != null }
            handler.post {
                if (!isDestroyed) {
                    heroWhere.text = ""
                    showTab(intent.getStringExtra(EXTRA_TAB) ?: "home", focusContent = true)
                    handler.postDelayed({ if (!isDestroyed) prefetch() }, 2500)
                }
            }
        }.start()
    }

    /** Back from TiviMate brings this screen back: keep where you were, or open the asked-for tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val wanted = intent.getStringExtra(EXTRA_TAB) ?: return
        if (wanted != tab && providers.isNotEmpty()) showTab(wanted, focusContent = true)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(rotateFeatured, FEATURE_MS)
        if (tab == "home" && rows.childCount > 0 && detail == null) refreshPersonalRows()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(rotateFeatured)
    }

    // ---------- Tabs ----------

    private fun tabButton(id: String, label: String): View {
        val c = this
        return GlassUi.rowText(c, label, 13.5f, R.font.inter_semibold, android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(GlassUi.INK, 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt()))).apply {
            isDuplicateParentStateEnabled = false
            gravity = Gravity.CENTER
            setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            background = GlassUi.rowBackground(c, 15f)
            isFocusable = true; isClickable = true
            tag = id
            setOnClickListener { if (id == "search") askSearch() else if (id != tab) showTab(id, focusContent = true) else firstItem()?.requestFocus() }
            // tvOS: moving onto a tab shows it (after a short pause, so passing over doesn't load everything).
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f)
                    .setInterpolator(DecelerateInterpolator(2f)).setDuration(200).start()
                pendingTab?.let { handler.removeCallbacks(it) }
                if (has && id != tab) {
                    val r = Runnable {
                        if (!v.isFocused) return@Runnable
                        if (id == "search") showSearchTab() else showTab(id, focusContent = false)
                    }
                    pendingTab = r
                    handler.postDelayed(r, 350)
                }
            }
        }
    }

    private var pendingTab: Runnable? = null

    /** The Search tab: last results, or a hint to press OK. */
    private fun showSearchTab() {
        val q = lastSearch
        if (q != null) { search(q, focusResults = false); return }
        tab = "search"
        for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "search"
        rows.removeAllViews()
        ++buildToken
        showPlainHero("SEARCH", "Find anything", "Press OK to search films, shows and what’s on TV, by typing or by voice.")
    }

    private fun tabIndex() = (0 until nav.childCount).firstOrNull { nav.getChildAt(it).tag == tab } ?: 0

    private fun showTab(id: String, focusContent: Boolean) {
        tab = id
        collectionFrom = null
        focusFirstLoaded = false
        for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == id
        rows.removeAllViews()
        scroll.scrollTo(0, 0)
        val token = ++buildToken
        when (id) {
            "home" -> buildHome(token)
            "films" -> buildCategory(token, "MOVIE")
            "shows" -> buildCategory(token, "SHOW")
            "live" -> buildLive(token)
            "list" -> buildList()
            "search" -> lastSearch?.let { search(it) }
        }
        if (focusContent) rows.post { firstItem()?.requestFocus() }
    }

    // ---------- Home ----------

    private fun shorts() = (installed.ifEmpty { providers }).map { it.short }

    private fun buildHome(token: Int) {
        refreshPersonalRows()
        titleRow(token, "top10", "Top 10 on Your Apps", ranked = true,
            more = { openCollection("Trending on Your Apps", catalogue(shorts(), sort = "TRENDING")) }) { done ->
            WatchCatalog.titles(this, sort = "TRENDING", packages = shorts(), first = 10) { list ->
                if (list != null && featured.isEmpty()) { featured = list.take(6); if (rowOf(currentFocus ?: nav) == null) showTitleHero(featured[0], now = true) }
                done(list)
            }
        }
        appsRow()
        WatchCatalog.history(this).firstOrNull()?.let { last ->
            val row = titleRow(token, "because", "Because You Watched ${last.name}") { done ->
                WatchCatalog.detail(this, last) { d -> done(d?.similar) }
            }
            row.view.tag = "because"
        }
        for (p in providers) {
            val title = if (p.installed != null) "Popular on ${p.displayName}" else "${p.displayName} · Get the App"
            titleRow(token, "p_${p.short}", title, icon = p.icon,
                more = { openProviderPage(p) }) { done ->
                WatchCatalog.titles(this, packages = listOf(p.short), done = done)
            }
        }
    }

    /** Continue Watching and My List: refreshed whenever you come back. */
    private fun refreshPersonalRows() {
        if (tab != "home") return
        listOf("history", "mylist").forEach { key -> findRow(key)?.let { rows.removeView(it) } }
        val history = WatchCatalog.history(this)
        val myList = WatchCatalog.myList(this)
        var at = 0
        if (history.isNotEmpty()) { rows.addView(staticTitleRow("history", "Continue Watching", history).view, at++) }
        if (myList.isNotEmpty()) { rows.addView(staticTitleRow("mylist", "My List", myList).view, at) }
    }

    // ---------- Films / Shows ----------

    private val genreList = listOf("act" to "Action", "cmy" to "Comedy", "drm" to "Drama", "trl" to "Thrillers", "crm" to "Crime",
        "scf" to "Sci-Fi", "fml" to "Family", "doc" to "Documentaries")

    /** The rows of the Films / Shows tabs: (key, title, loader). */
    private fun categoryLoads(type: String): List<Triple<String, String, TitleLoader>> {
        val what = if (type == "MOVIE") "Films" else "Shows"
        val list = mutableListOf<Triple<String, String, TitleLoader>>()
        list += Triple<String, String, TitleLoader>("pop", "All $what") { d -> WatchCatalog.titles(this, packages = shorts(), types = listOf(type), first = 60, done = d) }
        list += Triple<String, String, TitleLoader>("trend", "Trending $what") { d -> WatchCatalog.titles(this, sort = "TRENDING", packages = shorts(), types = listOf(type), done = d) }
        for ((g, name) in genreList) {
            list += Triple<String, String, TitleLoader>("g_$g", name) { d -> WatchCatalog.titles(this, packages = shorts(), types = listOf(type), genres = listOf(g), done = d) }
        }
        return list
    }

    // ---------- See More: a whole section, sorted into categories ----------

    private val allGenres = genreList + listOf("hrr" to "Horror", "rma" to "Romance", "ani" to "Animation", "fnt" to "Fantasy",
        "rly" to "Reality TV", "hst" to "History", "spt" to "Sport", "war" to "War")

    private var collectionFrom: String? = null
    private var focusFirstLoaded = false

    /**
     * Rows for a See More page: all the films, all the series, then each category
     * (or, for one category, the services that have it).
     */
    private fun catalogue(packages: List<String>, types: List<String> = emptyList(), sort: String = "POPULAR", genre: String? = null):
        List<Triple<String, String, TitleLoader>> {
        val g = listOfNotNull(genre)
        val list = mutableListOf<Triple<String, String, TitleLoader>>()
        if (types.isEmpty() || "MOVIE" in types) list += Triple<String, String, TitleLoader>("c_films", if (genre == null) "Films" else "Popular") { d ->
            WatchCatalog.titles(this, sort = sort, packages = packages, types = listOf("MOVIE"), genres = g, first = 80, done = d) }
        if (types.isEmpty() || "SHOW" in types) list += Triple<String, String, TitleLoader>("c_shows", if (genre == null) "Series" else "Popular Series") { d ->
            WatchCatalog.titles(this, sort = sort, packages = packages, types = listOf("SHOW"), genres = g, first = 80, done = d) }
        if (genre == null) {
            for ((code, name) in allGenres) list += Triple<String, String, TitleLoader>("c_g_$code", name) { d ->
                WatchCatalog.titles(this, sort = sort, packages = packages, types = types, genres = listOf(code), first = 60, done = d) }
        } else {
            for (p in installed.ifEmpty { providers }.filter { it.short in packages }) list += Triple<String, String, TitleLoader>("c_p_${p.short}", "On ${p.displayName}") { d ->
                WatchCatalog.titles(this, packages = listOf(p.short), types = types, genres = g, first = 40, done = d) }
        }
        return list
    }

    private fun openCollection(title: String, sections: List<Triple<String, String, TitleLoader>>) {
        if (collectionFrom == null) collectionFrom = tab
        tab = "collection"
        rows.removeAllViews()
        scroll.scrollTo(0, 0)
        val token = ++buildToken
        showPlainHero("SEE ALL", title, "Everything, by category.")
        focusFirstLoaded = true
        for ((key, name, load) in sections) titleRow(token, key, name, quietFail = true, load = load)
    }

    private fun closeCollection() {
        val back = collectionFrom ?: return
        collectionFrom = null
        showTab(back, focusContent = true)
    }

    // ---------- Your Apps: each service's whole library ----------

    /** Every service as a tile; opens that service's page. */
    private fun appsRow() {
        val list = installed + providers.filter { it.installed == null }
        if (list.isEmpty()) return
        val r = row("apps", "Your Apps", height = 120f)
        r.list.adapter = CardAdapter(list, { p -> serviceCard(p) }) { h, p ->
            h.label.text = p.displayName
            h.root.contentDescription = p.displayName
            ImageLoader.load(h.image, p.icon, ImageLoader.ICON)
            h.root.setOnClickListener { openProviderPage(p) }
            focusEffect(h.root) { showPlainHero(if (p.installed != null) "YOUR APP" else "GET THE APP", p.displayName, "Every film and series on ${p.displayName}, by category.") }
        }
        rows.addView(r.view)
    }

    /** A service tile: its logo on a dark glass card, name underneath. */
    private fun serviceCard(parent: ViewGroup): Holder {
        val c = parent.context
        val h = card(parent, 150f, 90f)
        (h.root.background as? GradientDrawable)?.setColor(0xFF1C1C1E.toInt())
        h.image.scaleType = ImageView.ScaleType.FIT_CENTER
        (h.image.layoutParams as FrameLayout.LayoutParams).apply { width = dp(c, 46f); height = dp(c, 46f); gravity = Gravity.CENTER; topMargin = -dp(c, 10f) }
        h.image.clipToOutline = true
        h.image.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(c, 10f).toFloat()) }
        }
        h.label.apply {
            (layoutParams as FrameLayout.LayoutParams).apply { width = -1; height = -2; gravity = Gravity.BOTTOM; bottomMargin = dp(c, 8f) }
            textSize = 11f; maxLines = 1; setTextColor(0xD9FFFFFF.toInt())
        }
        h.root.removeView(h.label); h.root.addView(h.label)
        return h
    }

    private var providerType = "MOVIE"

    /**
     * A service's own page: Films / Series at the top, then everything it has of that
     * type - all of it, then by category. Rows keep loading as you move along them.
     */
    private fun openProviderPage(p: WatchCatalog.Provider, type: String = providerType) {
        if (collectionFrom == null) collectionFrom = tab
        tab = "collection"
        providerType = type
        rows.removeAllViews()
        scroll.scrollTo(0, 0)
        val token = ++buildToken
        showPlainHero(if (p.installed != null) "YOUR APP" else "GET THE APP", p.displayName,
            "Everything on ${p.displayName}" + if (p.installed == null) ". Choose a title to get the app." else ", by category.")
        // Films | Series
        val heads = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; tag = "heads" }
        var selected: View? = null
        for ((id, label) in listOf("MOVIE" to "Films", "SHOW" to "Series")) {
            val b = pillButton(label, primary = id == type) { if (id != providerType) openProviderPage(p, id) }
            heads.addView(b, LinearLayout.LayoutParams(-2, dp(this, 38f)).apply { marginEnd = dp(this@WatchActivity, 12f) })
            if (id == type) selected = b
        }
        rows.addView(heads, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@WatchActivity, 56f); topMargin = dp(this@WatchActivity, 8f) })
        selected?.requestFocus()
        val what = if (type == "MOVIE") "Films" else "Series"
        pagedRow(token, "all", "All $what", listOf(p.short), listOf(type), emptyList())
        for ((code, name) in allGenres) pagedRow(token, "g_$code", name, listOf(p.short), listOf(type), listOf(code))
    }

    /** A row of a whole library that fetches the next 100 as you near its end. */
    private fun pagedRow(token: Int, key: String, title: String, packages: List<String>, types: List<String>, genres: List<String>) {
        val r = row(key, title, height = 196f)
        val items = mutableListOf<WatchCatalog.Title>()
        val adapter = CardAdapter(items.toList(), { p -> posterCard(p) }) { h, t -> bindTitle(h, t) }
        r.list.adapter = adapter
        rows.addView(r.view)
        var cursor: String? = null
        var loading = false
        var finished = false
        fun load() {
            if (loading || finished) return
            loading = true
            WatchCatalog.page(this, packages, types, genres, after = cursor) { list, next, total ->
                loading = false
                if (token != buildToken || isDestroyed) return@page
                if (list == null) { if (items.isEmpty()) rows.removeView(r.view); finished = true; return@page }
                if (list.isEmpty() && items.isEmpty()) { rows.removeView(r.view); finished = true; return@page }
                items += list
                adapter.update(items.toList())
                cursor = next
                if (next == null) finished = true
                // Show how much there is: "Comedy · 412".
                ((r.view.getChildAt(0) as? LinearLayout)?.let { hdr -> (0 until hdr.childCount).map { hdr.getChildAt(it) }.lastOrNull { it is TextView } } as? TextView)
                    ?.text = if (total > 0) "$title  ·  $total" else title
            }
        }
        r.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = (rv.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: return
                if (last >= items.size - 12) load()
            }
        })
        load()
    }

    private fun buildCategory(token: Int, type: String) {
        val what = if (type == "MOVIE") "Films" else "Series"
        for ((key, title, load) in categoryLoads(type)) {
            val more: () -> Unit = if (key.startsWith("g_")) {
                { openCollection("$title $what", catalogue(shorts(), types = listOf(type), genre = key.removePrefix("g_"))) }
            } else {
                { openCollection("All $what", catalogue(shorts(), types = listOf(type), sort = if (key == "trend") "TRENDING" else "POPULAR")) }
            }
            titleRow(token, key, title, quietFail = key.startsWith("g_"), more = more, load = load)
        }
    }

    /** Quietly load the other tabs (and their first posters) so switching is instant. */
    private fun prefetch() {
        val warm: (List<WatchCatalog.Title>?) -> Unit = { list -> list?.take(8)?.forEach { t -> t.poster?.let { ImageLoader.prefetch(this, it) } } }
        for (type in listOf("MOVIE", "SHOW")) categoryLoads(type).forEach { (_, _, load) -> load(warm) }
        LiveGuide.load(this) {}
    }

    // ---------- Live TV ----------

    private fun buildLive(token: Int) {
        // Just the TV guide (the live TV apps are on the home screen).
        val loading = GlassUi.text(this, "Loading the TV guide…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
        rows.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@WatchActivity, 56f), dp(this@WatchActivity, 20f), 0, 0) })
        LiveGuide.load(this) { channels ->
            if (token != buildToken || isDestroyed) return@load
            rows.removeView(loading)
            if (channels.isNullOrEmpty()) {
                rows.addView(GlassUi.text(this, "The TV guide couldn’t be loaded. Check the internet connection.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                    LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@WatchActivity, 56f), dp(this@WatchActivity, 20f), 0, 0) })
                return@load
            }
            // "On Now": big picture cards for the main channels.
            val onNow = channels.take(24).mapNotNull { ch -> ch.now()?.let { ch to it } }
            if (onNow.isNotEmpty()) {
                val r = row("onnow", "On Now", height = 150f)
                r.list.adapter = CardAdapter(onNow, { p -> wideCard(p) }) { h, (ch, prog) -> bindProgramme(h, ch, prog) }
                rows.addView(r.view)
            }
            rows.addView(GlassUi.text(this, "Guide", 15f, R.font.inter_semibold),
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@WatchActivity, 56f), dp(this@WatchActivity, 16f), 0, dp(this@WatchActivity, 4f)) })
            for (ch in channels.take(150)) { try { rows.addView(guideRow(ch)) } catch (e: Exception) {} }
            if (rows.findFocus() == null) rows.post { firstItem()?.requestFocus() }
        }
    }

    private fun guideRow(ch: LiveGuide.Channel): View {
        val c = this
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 56f), dp(c, 4f), dp(c, 40f), dp(c, 4f))
            clipChildren = false; clipToPadding = false
            setTag(R.id.blur_radius, "guide")
        }
        val logo = FrameLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0x1AFFFFFF) }
        }
        val logoImg = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        logo.addView(logoImg, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(c, 8f), dp(c, 6f), dp(c, 8f), dp(c, 6f)) })
        if (ch.logo != null) ImageLoader.load(logoImg, ch.logo, ImageLoader.ICON)
        else logo.addView(GlassUi.text(c, ch.name, 10f, R.font.inter_semibold).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        row.addView(logo, LinearLayout.LayoutParams(dp(c, 88f), dp(c, 46f)))
        val progs = ch.upcoming().take(3)
        progs.forEachIndexed { i, p ->
            row.addView(guideBlock(ch, p, i == 0), LinearLayout.LayoutParams(dp(c, if (i == 0) 290f else 205f), dp(c, 46f)).apply { marginStart = dp(c, 8f) })
        }
        return row
    }

    private fun guideBlock(ch: LiveGuide.Channel, p: LiveGuide.Programme, first: Boolean): View {
        val c = this
        val block = FrameLayout(c).apply {
            background = GlassUi.rowBackground(c, 10f, if (first && p.isOn()) 0x2EFFFFFF else 0x14FFFFFF)
            isFocusable = true; isClickable = true
            contentDescription = "${p.title}, ${ch.name}"
            setOnClickListener { playOrAsk(ch, p) }
            setOnLongClickListener { programmeDialog(ch, p); true }
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        col.addView(GlassUi.rowText(c, p.title, 12.5f, R.font.inter_semibold, GlassUi.primaryText()))
        col.addView(GlassUi.rowText(c, timeRange(p) + if (p.isOn()) "  ·  On now" else "", 10.5f, R.font.inter_medium, GlassUi.secondaryText()), lp(2f))
        block.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dp(c, 12f), 0, dp(c, 10f), 0) })
        if (p.isOn()) {
            val bar = View(c).apply { setBackgroundColor(0xCCFFFFFF.toInt()) }
            block.addView(bar, FrameLayout.LayoutParams((dp(c, if (first) 290f else 205f) * p.progress()).toInt(), dp(c, 2f), Gravity.BOTTOM))
        }
        block.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                .setInterpolator(Motion.focusEase).setDuration(Motion.FOCUS).start()
            if (has) showProgrammeHero(ch, p)
        }
        return block
    }

    private fun bindProgramme(h: Holder, ch: LiveGuide.Channel, p: LiveGuide.Programme) {
        h.label.text = p.title
        h.sub?.text = "${ch.name}  ·  ${timeRange(p)}"
        h.progress?.let { bar -> bar.post { bar.layoutParams.width = ((h.root.width) * p.progress()).toInt(); bar.requestLayout() } }
        ImageLoader.load(h.image, p.image ?: ch.logo, ImageLoader.CARD)
        h.root.contentDescription = "${p.title} on ${ch.name}"
        h.root.setOnClickListener { playOrAsk(ch, p) }
        h.root.setOnLongClickListener { programmeDialog(ch, p); true }
        focusEffect(h.root) { showProgrammeHero(ch, p) }
    }

    /** On now: straight to that channel in TiviMate. Later (or no TiviMate): the options. Hold OK for options any time. */
    private fun playOrAsk(ch: LiveGuide.Channel, p: LiveGuide.Programme) {
        if (p.isOn() && LiveGuide.hasTiviMate(this)) LiveGuide.openTiviMate(this, ch) else programmeDialog(ch, p)
    }

    private fun programmeDialog(ch: LiveGuide.Channel, p: LiveGuide.Programme) {
        val d = GlassDialog(this).title(p.title)
        d.message(listOf("${ch.name}  ·  ${timeRange(p)}" + (if (p.episode.isNotBlank()) "  ·  ${p.episode}" else ""), p.desc.take(300))
            .filter { it.isNotBlank() }.joinToString("\n\n"))
        if (LiveGuide.hasTiviMate(this)) {
            d.button("Watch on Dodgy Box", GlassDialog.Style.BOLD) { LiveGuide.openTiviMate(this, ch) }
            when {
                p.isOn() -> d.button("Record on Dodgy Box") { TiviRecorder.recordNow(this, ch) }
                TiviRecorder.isScheduled(this, ch, p) -> d.button("Cancel This Recording", GlassDialog.Style.DESTRUCTIVE) {
                    TiviRecorder.list(this).filter { it.channel == ch.name && it.start == p.start }.forEach { TiviRecorder.cancel(this, it.id) }
                    GlassUi.toast(this, "Recording cancelled")
                }
                p.start > System.currentTimeMillis() -> d.button("Record This Episode") {
                    TiviRecorder.schedule(this, ch, p)
                    GlassUi.toast(this, "TiviMate will record ${p.title} - keep the TV on")
                }
            }
            // Series link: every episode on this channel, as the guide shows them.
            if (TiviRecorder.isSeries(this, ch, p)) {
                d.button("Cancel Series Recording", GlassDialog.Style.DESTRUCTIVE) {
                    TiviRecorder.removeSeries(this, ch, p)
                    GlassUi.toast(this, "Stopped recording ${p.title}")
                }
            } else {
                d.button("Record Series") {
                    LiveGuide.load(this) { channels ->
                        val n = TiviRecorder.addSeries(this, ch, p, channels ?: emptyList())
                        GlassUi.toast(this, "Recording every ${p.title} on ${ch.name}" +
                            if (n > 0) " - $n episode${if (n == 1) "" else "s"} scheduled" else " - new episodes are added as the guide updates")
                    }
                    if (p.isOn()) TiviRecorder.recordNow(this, ch)
                }
            }
        }
        LiveGuide.broadcasterApp(this, ch)?.let { (pkg, name) ->
            d.button("Watch in $name") { LiveGuide.openApp(this, pkg) }
        }
        d.button("Find on Demand") { search(p.title) }
        d.button("Cancel")
        d.show()
    }

    // ---------- My List ----------

    private fun buildList() {
        val list = WatchCatalog.myList(this)
        if (list.isEmpty()) {
            showPlainHero("MY LIST", "Nothing saved yet", "Open any film or show and choose “Add to My List”.")
            rows.addView(GlassUi.text(this, "Your saved films and shows appear here.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(this@WatchActivity, 56f), dp(this@WatchActivity, 20f), 0, 0) })
            return
        }
        list.chunked(7).forEachIndexed { i, chunk -> rows.addView(staticTitleRow("list$i", if (i == 0) "My List" else "", chunk).view) }
    }

    // ---------- Search ----------

    private fun askSearch() {
        val d = GlassDialog(this).title("Search").message("Films, shows and what’s on TV.").input("Title or person", lastSearch.orEmpty())
        d.button("Search", GlassDialog.Style.BOLD) { if (d.inputText.isNotBlank()) search(d.inputText) }
        d.button("Speak Instead") {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search films, shows and TV"))
            } catch (e: ActivityNotFoundException) { GlassUi.toast(this, "Voice search isn’t available") }
        }
        d.button("Cancel")
        d.show()
    }

    private fun search(query: String, focusResults: Boolean = true) {
        lastSearch = query
        if (detail != null) closeDetail()
        tab = "search"
        for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "search"
        rows.removeAllViews()
        scroll.scrollTo(0, 0)
        val token = ++buildToken
        showPlainHero("SEARCH", "“$query”", "")
        titleRow(token, "results", "Films & Shows") { done -> WatchCatalog.titles(this, search = query, first = 30, done = done) }
        LiveGuide.load(this) { channels ->
            if (token != buildToken || channels == null) return@load
            val q = query.lowercase()
            val hits = channels.flatMap { ch -> ch.upcoming().filter { it.title.lowercase().contains(q) }.map { ch to it } }.take(20)
            if (hits.isEmpty()) return@load
            val r = row("ontv", "On TV", height = 150f)
            r.list.adapter = CardAdapter(hits, { p -> wideCard(p) }) { h, (ch, prog) -> bindProgramme(h, ch, prog) }
            rows.addView(r.view)
        }
        if (focusResults) {
            rows.post { firstItem()?.requestFocus() }
            handler.postDelayed({ if (tab == "search" && rows.findFocus() == null) firstItem()?.requestFocus() }, 1500)
        }
    }

    // ---------- Detail page ----------

    private var detailScroll: ScrollView? = null
    private var detailMore: View? = null

    private fun openDetail(t: WatchCatalog.Title, from: View?) {
        val c = this
        if (detail == null) detailReturn = from
        detail?.let { root.removeView(it) }
        // The browse screen fades away underneath, so nothing shows through.
        for (v in listOf<View>(info, nav, scroll)) v.animate().alpha(0f).setDuration(200).withEndAction { if (detail != null) v.visibility = View.INVISIBLE }.start()
        val page = FrameLayout(c).apply { isClickable = true; alpha = 0f }
        page.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF7000000.toInt(), 0xD9000000.toInt(), 0x66000000, 0x1A000000))
        }, FrameLayout.LayoutParams(-1, -1))
        page.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF000000.toInt(), 0xB3000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(c, 300f), Gravity.BOTTOM))
        detail = page
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.animate().alpha(1f).setDuration(280).start()
        heroUrl = null
        setHeroImage(t.backdrop ?: t.poster)

        // One column that glides down to "More Like This".
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(c, 46f), 0, dp(c, 60f))
        }
        val sv = RowScroll(c).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            addView(col)
        }
        detailScroll = sv
        page.addView(sv, FrameLayout.LayoutParams(-1, -1))
        fun inset(v: View, top: Float, w: Int = -2) = col.addView(v, LinearLayout.LayoutParams(w, -2).apply {
            marginStart = dp(c, 56f); marginEnd = dp(c, 40f); topMargin = dp(c, top)
        })

        val main = t.bestOffer ?: t.offers.firstOrNull()
        inset(GlassUi.text(c, listOfNotNull(main?.provider?.displayName?.uppercase(), if (t.type == "SHOW") "SERIES" else "FILM").joinToString("  ·  "),
            10.5f, R.font.inter_semibold, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f }, 0f)
        inset(GlassUi.text(c, t.name, 38f, R.font.inter_semibold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, 4f, dp(c, 620f))
        val meta = GlassUi.text(c, metaLine(t, null), 12.5f, R.font.inter_medium, 0xCCFFFFFF.toInt())
        inset(meta, 8f)
        inset(GlassUi.text(c, t.description, 13.5f, R.font.inter_regular, 0xE6FFFFFF.toInt()).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f)
        }, 12f, dp(c, 540f))
        val people = GlassUi.text(c, "", 11.5f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        inset(people, 8f, dp(c, 540f))

        // Buttons.
        val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val primary = if (main == null) null else {
            val label = when {
                main.provider.installed == null -> "Get ${main.provider.displayName}"
                main.kind == "RENT" -> "Rent on ${main.provider.displayName}"
                main.kind == "BUY" -> "Buy on ${main.provider.displayName}"
                else -> "▶  Watch on ${main.provider.displayName}"
            }
            pillButton(label, primary = true) { useOffer(t, main) }
        }
        primary?.let { buttons.addView(it, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) }) }
        if (primary == null) {
            buttons.addView(pillButton("Search Google TV", primary = true) { WatchCatalog.searchGoogleTv(this, t.name) },
                LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
        }
        // TiviMate's own films and series (from the TV provider), played the same way as live TV.
        if (LiveGuide.hasTiviMate(c)) {
            buttons.addView(pillButton("Watch on Dodgy Box") { watchOnDodgyBox(t) },
                LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
        }
        val trailerButton = pillButton("Trailer") {}
        trailerButton.visibility = View.GONE
        buttons.addView(trailerButton, LinearLayout.LayoutParams(-2, dp(c, 40f)).apply { marginEnd = dp(c, 10f) })
        val listButton = pillButton(if (WatchCatalog.inMyList(c, t)) "✓  In My List" else "+  My List") {}
        listButton.setOnClickListener {
            val added = WatchCatalog.toggleMyList(c, t)
            (listButton as TextView).text = if (added) "✓  In My List" else "+  My List"
            GlassUi.toast(this, if (added) "Added to My List" else "Removed from My List")
        }
        buttons.addView(listButton, LinearLayout.LayoutParams(-2, dp(c, 40f)))
        inset(buttons, 20f)

        // Where to watch: every service with this title.
        if (t.offers.isNotEmpty()) {
            inset(GlassUi.text(c, "WHERE TO WATCH", 10f, R.font.inter_semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f }, 24f)
            val chips = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
            for (o in t.offers.take(5)) chips.addView(offerChip(t, o), LinearLayout.LayoutParams(-2, dp(c, 44f)).apply { marginEnd = dp(c, 10f) })
            inset(chips, 8f)
        }

        // More Like This (filled in when the details arrive).
        val more = row("more", "More Like This", height = 196f)
        more.view.visibility = View.GONE
        col.addView(more.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 18f) })
        detailMore = more.view

        (primary ?: listButton).requestFocus()
        WatchCatalog.detail(this, t) { d ->
            if (detail !== page || d == null) return@detail
            meta.text = metaLine(t, d)
            people.text = listOfNotNull(
                d.cast.takeIf { it.isNotEmpty() }?.let { "Starring " + it.joinToString(", ") },
                d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by " + it.joinToString(", ") },
            ).joinToString("   ·   ")
            d.trailer?.let { id ->
                trailerButton.visibility = View.VISIBLE
                trailerButton.setOnClickListener { WatchCatalog.playTrailer(this, id) }
            }
            if (d.similar.isNotEmpty()) {
                more.list.adapter = CardAdapter(d.similar, { p -> posterCard(p) }) { h, s -> bindTitle(h, s, inDetail = true) }
                more.view.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Play a film or show from the provider directly (signed in), or through TiviMate's
     * search otherwise. Shows offer their episodes to choose from.
     */
    private fun watchOnDodgyBox(t: WatchCatalog.Title) {
        val show = t.type == "SHOW"
        if (!Xtream.isSetUp(this)) { TiviTuner.playVod(this, t.name, t.year, show); return }
        GlassUi.toast(this, "Finding ${t.name} on Dodgy Box…")
        val app = applicationContext
        Thread {
            var film: Xtream.Vod? = null
            var series: Xtream.Series? = null
            var episodes: List<Xtream.Episode> = emptyList()
            try {
                if (show) { series = Xtream.findSeries(app, t.name, t.year); series?.let { episodes = Xtream.episodes(app, it) } }
                else film = Xtream.findVod(app, t.name, t.year)
            } catch (e: Exception) {}
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val f = film
                when {
                    f != null -> PlayerActivity.play(this, Xtream.vodUrl(app, f), t.name, live = false, subtitle = t.year.takeIf { it > 0 }?.toString(), eyebrow = "FILM")
                    series != null && episodes.isNotEmpty() -> chooseEpisode(t, episodes)
                    else -> {
                        GlassUi.toast(this, "${t.name} isn’t in your Dodgy Box library - trying TiviMate")
                        TiviTuner.playVod(this, t.name, t.year, show)
                    }
                }
            }
        }.start()
    }

    private fun chooseEpisode(t: WatchCatalog.Title, episodes: List<Xtream.Episode>) {
        val app = applicationContext
        val d = GlassDialog(this).title(t.name).message("${episodes.size} episodes on Dodgy Box").scrolling()
        for (e in episodes) {
            val name = e.title.takeIf { it.isNotBlank() && !it.contains(t.name, ignoreCase = true) }?.let { " · $it" }.orEmpty()
            d.button("S${e.season} E${e.number}$name") {
                PlayerActivity.play(this, Xtream.episodeUrl(app, e), t.name, live = false, subtitle = "Season ${e.season}, Episode ${e.number}", eyebrow = "SERIES")
            }
        }
        d.button("Cancel")
        d.show()
    }

    /** Inside the detail page: glide down to More Like This, or back up to the buttons. */
    private fun detailFocusMoved(v: View) {
        val sv = detailScroll ?: return
        val more = detailMore ?: return
        var p: Any? = v.parent
        var inMore = false
        while (p is View) { if (p === more) { inMore = true; break }; p = p.parent }
        sv.smoothScrollTo(0, if (inMore) (more.top - dp(this, 24f)).coerceAtLeast(0) else 0)
    }

    private fun closeDetail() {
        val page = detail ?: return
        detail = null
        detailScroll = null
        detailMore = null
        for (v in listOf<View>(info, nav, scroll)) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(240).start() }
        page.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(page) }.start()
        heroUrl = null
        detailReturn?.takeIf { it.isAttachedToWindow }?.requestFocus() ?: firstItem()?.requestFocus()
        detailReturn = null
    }

    private fun metaLine(t: WatchCatalog.Title, d: WatchCatalog.Detail?) = listOfNotNull(
        t.year.takeIf { it > 0 }?.toString(),
        d?.seasons?.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" },
        d?.age?.takeIf { it.isNotBlank() },
        d?.runtime?.takeIf { it > 0 }?.let { if (it >= 60 && t.type == "MOVIE") "${it / 60}h ${it % 60}m" else "$it min" },
        t.imdb?.let { "IMDb %.1f".format(it) },
        t.genres.take(3).joinToString(", ").ifEmpty { null },
    ).joinToString("  ·  ")

    /** Watch / rent / buy, or offer to install the app. */
    private fun useOffer(t: WatchCatalog.Title, o: WatchCatalog.Offer) {
        if (o.provider.installed != null) { WatchCatalog.open(this, t, o); return }
        GlassDialog(this).title("Get ${o.provider.displayName}")
            .message("${t.name} is on ${o.provider.displayName}, which isn’t installed on this TV yet.")
            .button("Get the App", GlassDialog.Style.BOLD) { WatchCatalog.getApp(this, o.provider) }
            .button("Cancel")
            .show()
    }

    private fun offerChip(t: WatchCatalog.Title, o: WatchCatalog.Offer): View {
        val c = this
        val chip = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.rowBackground(c, 12f, 0x1FFFFFFF)
            setPadding(dp(c, 6f), 0, dp(c, 14f), 0)
            isFocusable = true; isClickable = true
            contentDescription = o.provider.displayName
            setOnClickListener { useOffer(t, o) }
        }
        val icon = ImageView(c).apply {
            background = GradientDrawable().apply { cornerRadius = dp(c, 8f).toFloat(); setColor(0x33FFFFFF) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        ImageLoader.load(icon, o.provider.icon, ImageLoader.ICON)
        chip.addView(icon, LinearLayout.LayoutParams(dp(c, 32f), dp(c, 32f)))
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        text.addView(GlassUi.rowText(c, o.provider.displayName, 12f, R.font.inter_semibold, GlassUi.primaryText()))
        text.addView(GlassUi.rowText(c, when {
            o.provider.installed == null -> "Not installed"
            o.kind == "FLATRATE" -> "Included"
            o.kind == "FREE" || o.kind == "ADS" -> "Free"
            o.kind == "RENT" -> "Rent"
            o.kind == "BUY" -> "Buy"
            else -> "Available"
        }, 10f, R.font.inter_medium, GlassUi.secondaryText()), lp(1f))
        chip.addView(text, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 9f) })
        GlassUi.focusLift(chip, 1.05f)
        return chip
    }

    private fun pillButton(label: String, primary: Boolean = false, onClick: () -> Unit): View {
        val c = this
        return GlassUi.rowText(c, label, 13.5f, R.font.inter_semibold, GlassUi.primaryText()).apply {
            isDuplicateParentStateEnabled = false
            gravity = Gravity.CENTER
            setPadding(dp(c, 20f), 0, dp(c, 20f), 0)
            background = GlassUi.rowBackground(c, 20f, if (primary) 0x40FFFFFF else 0x1FFFFFFF)
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            GlassUi.focusLift(this, 1.05f)
        }
    }

    // ---------- Hero ----------

    private val rotateFeatured = object : Runnable {
        override fun run() {
            if (tab == "home" && detail == null && featured.isNotEmpty() && rowOf(currentFocus ?: nav) == null) {
                featuredIndex = (featuredIndex + 1) % featured.size
                showTitleHero(featured[featuredIndex], now = true)
            }
            handler.postDelayed(this, FEATURE_MS)
        }
    }

    private fun showTitleHero(t: WatchCatalog.Title, now: Boolean = false) = setHero(now) {
        val on = t.offers.filter { it.provider.installed != null }.map { it.provider.displayName }.distinct()
        eyebrow.text = listOfNotNull(on.firstOrNull()?.uppercase(), if (t.type == "SHOW") "SERIES" else "FILM").joinToString("  ·  ")
        heroTitle.text = t.name
        heroMeta.text = metaLine(t, null)
        heroText.text = t.description
        heroWhere.text = when {
            on.isNotEmpty() -> "On " + on.joinToString(", ")
            t.offers.isNotEmpty() -> "On ${t.offers.first().provider.displayName} (not installed)"
            else -> ""
        }
        setHeroImage(t.backdrop ?: t.poster)
    }

    private fun showProgrammeHero(ch: LiveGuide.Channel, p: LiveGuide.Programme) = setHero(false) {
        eyebrow.text = (if (p.isOn()) "ON NOW  ·  " else "LATER  ·  ") + ch.name.uppercase()
        heroTitle.text = p.title
        heroMeta.text = listOf(timeRange(p), p.episode).filter { it.isNotBlank() }.joinToString("  ·  ")
        heroText.text = p.desc
        heroWhere.text = if (LiveGuide.hasTiviMate(this) && p.isOn()) "Press OK to watch on Dodgy Box · hold OK for more" else if (LiveGuide.hasTiviMate(this)) "Hold OK for options" else ""
        setHeroImage(p.image)
    }

    private fun showPlainHero(top: String, title: String, text: String) = setHero(true) {
        eyebrow.text = top
        heroTitle.text = title
        heroMeta.text = ""
        heroText.text = text
        heroWhere.text = ""
    }

    /** Debounced, so whizzing along a row doesn't flicker through every title. */
    private fun setHero(now: Boolean, update: () -> Unit) {
        pendingHero?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (detail == null || now) update() }
        pendingHero = r
        if (now) r.run() else handler.postDelayed(r, 260)
    }

    private fun setHeroImage(url: String?) {
        if (url == heroUrl) return
        heroUrl = url
        val incoming = if (heroShowingA) heroB else heroA
        val outgoing = if (heroShowingA) heroA else heroB
        heroShowingA = !heroShowingA
        if (url == null) { outgoing.animate().alpha(0f).setDuration(500).start(); return }
        ImageLoader.load(incoming, url, ImageLoader.HERO, fade = false) { bmp ->
            if (bmp == null || heroUrl != url) return@load
            incoming.bringToFront()
            incoming.alpha = 0f
            incoming.scaleX = 1.05f; incoming.scaleY = 1.05f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.HERO).setInterpolator(Motion.enter).start()
            outgoing.animate().alpha(0f).setDuration(Motion.HERO).setInterpolator(Motion.standard).start()
        }
    }

    // ---------- Rows ----------

    private class Row(val view: LinearLayout, val list: RecyclerView)

    private fun row(key: String, title: String, height: Float = 196f, icon: String? = null): Row {
        val c = this
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; tag = key }
        if (title.isNotEmpty()) {
            val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) {
                val iv = ImageView(c).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(c, 5f).toFloat(); setColor(0x33FFFFFF) }
                    clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
                }
                ImageLoader.load(iv, icon, ImageLoader.ICON)
                header.addView(iv, LinearLayout.LayoutParams(dp(c, 20f), dp(c, 20f)).apply { marginEnd = dp(c, 9f) })
            }
            header.addView(GlassUi.text(c, title, 15f, R.font.inter_semibold))
            box.addView(header, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, 10f) })
        }
        val list = EdgeStopRecyclerView(c).apply {
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
        return Row(box, list)
    }

    /** A row of titles that loads itself; disappears if empty. */
    private fun titleRow(
        token: Int, key: String, title: String, icon: String? = null, ranked: Boolean = false, quietFail: Boolean = false,
        more: (() -> Unit)? = null,
        load: ((List<WatchCatalog.Title>?) -> Unit) -> Unit,
    ): Row {
        val r = row(key, title, icon = icon, height = 196f)
        val moreCard: ((ViewGroup) -> Holder)? = if (more != null) { p -> seeMoreCard(p) } else null
        val adapter = CardAdapter(emptyList<WatchCatalog.Title>(), { p -> posterCard(p, ranked) }, more, moreCard) { h, t -> bindTitle(h, t) }
        r.list.adapter = adapter
        rows.addView(r.view)
        load { titles ->
            if (token != buildToken || isDestroyed) return@load
            when {
                titles.isNullOrEmpty() && (quietFail || titles != null) -> rows.removeView(r.view)
                titles == null -> r.view.addView(GlassUi.text(this, "Couldn’t load - check the internet connection", 12f, R.font.inter_regular, 0x99FFFFFF.toInt()),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@WatchActivity, 56f) })
                else -> {
                    adapter.update(titles)
                    // A collection page puts the highlight on its first title as soon as it arrives.
                    if (focusFirstLoaded) { focusFirstLoaded = false; r.list.post { r.list.getChildAt(0)?.requestFocus() } }
                }
            }
        }
        return r
    }

    private fun staticTitleRow(key: String, title: String, titles: List<WatchCatalog.Title>): Row {
        val r = row(key, title)
        r.list.adapter = CardAdapter(titles, { p -> posterCard(p) }) { h, t -> bindTitle(h, t) }
        return r
    }

    private fun findRow(key: String): View? = (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == key }

    private fun rowOf(v: View): View? {
        var p: Any? = v.parent
        var last: View = v
        while (p is View) {
            if (p === rows) return last
            last = p
            p = p.parent
        }
        return null
    }

    private fun firstItem(): View? {
        for (i in 0 until rows.childCount) {
            val r = rows.getChildAt(i) as? ViewGroup ?: continue
            if (r.getTag(R.id.blur_radius) == "guide") return r.getChildAt(1)
            val list = (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView ?: continue
            list.getChildAt(0)?.let { return it }
        }
        return null
    }

    /** A row that stops at its first and last card: Left/Right never jumps to another row. */
    private class EdgeStopRecyclerView(c: Context) : RecyclerView(c) {
        override fun focusSearch(focused: View, direction: Int): View? {
            val found = super.focusSearch(focused, direction)
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return found
            var p: View? = found
            while (p != null) { if (p === this) return found; p = p.parent as? View }
            return focused
        }
    }

    /** ScrollView that leaves scrolling to us (smooth row-by-row glide). */
    private class RowScroll(c: Context) : ScrollView(c) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
    }

    // ---------- Cards ----------

    private class Holder(val root: FrameLayout, val image: ImageView, val label: TextView, val sub: TextView? = null, val progress: View? = null) :
        RecyclerView.ViewHolder(root)

    private class CardAdapter<T>(
        private var items: List<T>,
        private val create: (ViewGroup) -> Holder,
        /** "See More" at the end of the row (opens everything in that section). */
        private val more: (() -> Unit)? = null,
        private val moreCard: ((ViewGroup) -> Holder)? = null,
        private val bind: (Holder, T) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {
        fun update(list: List<T>) { items = list; notifyDataSetChanged() }
        private fun hasMore() = more != null && moreCard != null && items.isNotEmpty()
        override fun getItemCount() = items.size + if (hasMore()) 1 else 0
        override fun getItemViewType(position: Int) = if (position >= items.size) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = if (viewType == 1) moreCard!!(parent) else create(parent)
        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position >= items.size) { holder.root.setOnClickListener { more?.invoke() }; return }
            holder.root.getTag(R.id.blur_anim)?.let { (it as? TextView)?.text = "${position + 1}" }
            bind(holder, items[position])
        }
    }

    /** The last card of a row: "See More". */
    private fun seeMoreCard(parent: ViewGroup): Holder {
        val c = parent.context
        val h = card(parent, 104f, 156f)
        (h.root.background as? GradientDrawable)?.setColor(0x26FFFFFF)
        h.label.text = "See More\n›"
        h.label.textSize = 13f
        h.label.typeface = GlassUi.font(c, R.font.inter_semibold)
        h.label.setTextColor(0xFFFFFFFF.toInt())
        h.label.setLineSpacing(dp(c, 4f).toFloat(), 1f)
        h.root.contentDescription = "See More"
        focusEffect(h.root) { }
        return h
    }

    private fun card(parent: ViewGroup, w: Float, h: Float, gap: Float = 14f): Holder {
        val c = parent.context
        val root = FrameLayout(c).apply {
            layoutParams = RecyclerView.LayoutParams(dp(c, w), dp(c, h)).apply { marginEnd = dp(c, gap) }
            background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0xFF1E1D22.toInt()) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = ContextCompat.getDrawable(c, R.drawable.tile_bezel)
            isFocusable = true; isClickable = true
        }
        val label = GlassUi.text(c, "", 11f, R.font.inter_medium, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(dp(c, 8f), 0, dp(c, 8f), 0); maxLines = 3
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        return Holder(root, image, label)
    }

    /** Poster; Top 10 posters get a big rank number in the corner. */
    private fun posterCard(parent: ViewGroup, ranked: Boolean = false): Holder {
        val h = card(parent, 104f, 156f, gap = if (ranked) 22f else 14f)
        if (ranked) {
            val c = parent.context
            val n = GlassUi.text(c, "", 44f, R.font.inter_semibold).apply {
                GlassUi.flex(this, 800, 60)
                setShadowLayer(dp(c, 8f).toFloat(), 0f, 0f, 0xE6000000.toInt())
                includeFontPadding = false
            }
            h.root.addView(n, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(c, 6f), 0, 0, dp(c, 2f)) })
            h.root.setTag(R.id.blur_anim, n)
        }
        return h
    }

    /** 16:9 picture card with title, channel/time and a progress line. */
    private fun wideCard(parent: ViewGroup): Holder {
        val base = card(parent, 200f, 112f)
        val c = parent.context
        base.root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(c, 70f), Gravity.BOTTOM))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val title = GlassUi.text(c, "", 12.5f, R.font.inter_semibold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = GlassUi.text(c, "", 10f, R.font.inter_medium, 0xCCFFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(title); col.addView(sub, lp(2f))
        base.root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(c, 10f), 0, dp(c, 10f), dp(c, 9f)) })
        val bar = View(c).apply { setBackgroundColor(0xE6FFFFFF.toInt()) }
        base.root.addView(bar, FrameLayout.LayoutParams(0, dp(c, 3f), Gravity.BOTTOM))
        base.label.visibility = View.GONE
        return Holder(base.root, base.image, title, sub, bar)
    }

    private fun bindTitle(h: Holder, t: WatchCatalog.Title, inDetail: Boolean = false) {
        h.label.text = t.name
        h.root.contentDescription = t.name
        ImageLoader.load(h.image, t.poster)
        h.root.setOnClickListener { openDetail(t, h.root) }
        focusEffect(h.root) { if (!inDetail) showTitleHero(t) }
    }

    private fun bindApp(h: Holder, app: AppEntry) {
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
            val pad = dp(this, 22f)
            h.image.setPadding(pad, pad, pad, pad)
            h.image.setImageBitmap(TileRenderer.forApp(app, dp(this, 48f)))
        }
    }

    private fun focusEffect(v: View, onFocus: () -> Unit) {
        Motion.focusable(v, 1.1f, 14f, onFocus)
    }

    // ---------- Bits ----------

    private fun timeRange(p: LiveGuide.Programme): String {
        val f = DateFormat.getTimeFormat(this)
        return "${f.format(Date(p.start))} – ${f.format(Date(p.stop))}"
    }

    private fun heroImage() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WatchActivity, top) }

    companion object {
        private const val FEATURE_MS = 8000L
        const val EXTRA_TAB = "tab"
    }
}
