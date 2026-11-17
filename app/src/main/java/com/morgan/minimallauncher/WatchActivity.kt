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
import android.animation.ValueAnimator
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
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

    internal val handler = Handler(Looper.getMainLooper())
    internal lateinit var root: FrameLayout
    internal lateinit var heroA: ImageView
    internal lateinit var heroB: ImageView
    internal var heroShowingA = false
    internal var heroUrl: String? = null
    internal lateinit var eyebrow: TextView
    internal lateinit var heroTitle: TextView
    internal lateinit var heroMeta: TextView
    internal lateinit var heroText: TextView
    internal lateinit var heroWhere: TextView
    internal lateinit var info: LinearLayout
    internal lateinit var nav: LinearLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var rows: LinearLayout
    internal var detail: FrameLayout? = null
    internal var detailReturn: View? = null

    internal var tab = "home"
    internal var providers: List<WatchCatalog.Provider> = emptyList()
    internal var installed: List<WatchCatalog.Provider> = emptyList()
    internal var featured: List<WatchCatalog.Title> = emptyList()
    internal var featuredIndex = 0
    internal var pendingHero: Runnable? = null
    internal var lastSearch: String? = null
    internal var buildToken = 0
    // Each tab keeps its own rows, so switching back is instant instead of rebuilding everything.
    internal val tabViews = HashMap<String, Pair<LinearLayout, Long>>()
    internal val containerToken = java.util.WeakHashMap<LinearLayout, Int>()
    internal fun cacheTtl(id: String) = when (id) { "home", "films", "shows" -> 30 * 60_000L; "list" -> 0L; else -> 0L }
    internal fun newBuild(): Int { val t = ++buildToken; containerToken[rows] = t; shownTitles.clear(); return t }
    /** Titles already in a row on this page, so the next rows don't repeat them. */
    internal val shownTitles = HashSet<String>()

    internal fun makeRows() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        setPadding(0, 0, 0, dp(this@WatchActivity, 320f))
    }
    internal fun swapRows(c: LinearLayout) {
        (c.parent as? ViewGroup)?.removeView(c)
        scroll.removeAllViews()
        scroll.addView(c)
        rows = c
        // A new page settles in rather than snapping (Apple's soft cross-fade).
        c.alpha = 0f; c.translationY = dp(this, 14f).toFloat()
        c.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(Motion.focusEase).start()
    }

    /** Films/Shows build their rows a few at a time as you scroll down to them (not all at once), per page. */
    internal val moreRowsFor = java.util.WeakHashMap<LinearLayout, () -> Unit>()
    internal fun freshRows() = swapRows(makeRows())

    internal val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { search(it) }
    }

    // ---------- Setup ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Activation.guard(this)) return
        Xtream.warmUp(this)
        val c = this
        root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }

        val heroLayer = FrameLayout(c)
        heroA = heroImage(); heroB = heroImage()
        heroLayer.addView(heroA, FrameLayout.LayoutParams(-1, -1))
        heroLayer.addView(heroB, FrameLayout.LayoutParams(-1, -1))
        trailerHost = FrameLayout(c).apply { alpha = 0f }
        heroLayer.addView(trailerHost, FrameLayout.LayoutParams(-1, -1))
        root.addView(heroLayer, FrameLayout.LayoutParams(-1, -1))
        heroDim = View(c).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        root.addView(heroDim, FrameLayout.LayoutParams(-1, -1))
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
        for ((id, label) in listOf("home" to "Home", "films" to "Films", "shows" to "Shows", "live" to "Live TV", "sport" to "Sport", "list" to "My Stuff", "search" to "Search")) {
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
        // Rows never draw above their own area (scrolled-away rows were showing over the tabs at the top).
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val pad = dp(c, 400f)
            v.clipBounds = android.graphics.Rect(-pad, -dp(c, 14f), v.width + pad, v.height + pad)
        }

        setContentView(root)
        UiSounds.attach(this)

        // Glide each row to the top of the rows area as focus reaches it.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (new == null) return@addOnGlobalFocusChangeListener
            if (detail != null) { detailFocusMoved(new); return@addOnGlobalFocusChangeListener }
            // Coming up out of the rows onto the tabs: land on the tab you're in, not whichever is nearest.
            if (new.parent === nav && old != null && rowOf(old) != null && new.tag != tab) {
                (0 until nav.childCount).map { nav.getChildAt(it) }.firstOrNull { it.tag == tab }?.let { cur -> new.post { cur.requestFocus() } }
            }
            if (new.parent === nav) setHeroCollapsed(false)
            val row = rowOf(new) ?: return@addOnGlobalFocusChangeListener
            if (rows.indexOfChild(row) >= rows.childCount - 3) moreRowsFor[rows]?.invoke()
            setHeroCollapsed(firstContentRow() !== row)
            val guideRow = row.getTag(R.id.blur_radius) == "guide"
            if (guideRow) (row.getTag(R.id.blur_anim) as? Runnable)?.run()
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
        Bg.run {
            providers = try { WatchCatalog.featuredProviders(this) } catch (e: Exception) { emptyList() }
            installed = providers.filter { it.installed != null }
            handler.post {
                if (!isDestroyed) {
                    heroWhere.text = ""
                    val q = intent.getStringExtra(EXTRA_QUERY)
                    builtFor = ProfileManager.activeKey(this)
                    if (q != null) search(q) else showTab(intent.getStringExtra(EXTRA_TAB) ?: "home", focusContent = true)
                    // Like Netflix: "Who's watching?" when Watch opens, if more than one person uses this TV (at most every 4 hours).
                    val mins = (System.currentTimeMillis() / 60_000L).toInt()
                    if (WATCH_WHO && q == null && ProfileManager.getProfiles(this).size > 1 && Prefs.bool(this, "watch_who", true) &&
                        mins - Prefs.int(this, "watch_who_at", 0) > 240) {
                        Prefs.setInt(this, "watch_who_at", mins)
                        startActivity(Intent(this, WhoActivity::class.java))
                    }
                    handler.postDelayed({ if (!isDestroyed) prefetch() }, 2500)
                }
            }
        }
    }

    /** Back from TiviMate brings this screen back: keep where you were, or open the asked-for tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_QUERY)?.let { if (providers.isNotEmpty()) { search(it); return } }
        val wanted = intent.getStringExtra(EXTRA_TAB) ?: return
        if (wanted != tab && providers.isNotEmpty()) showTab(wanted, focusContent = true)
    }

    internal var builtFor: String? = null

    override fun onResume() {
        super.onResume()
        // Someone else picked their profile: rebuild everything for them.
        ProfileManager.activeKey(this).let { k -> if (builtFor != null && k != builtFor && providers.isNotEmpty()) { builtFor = k; tabViews.clear(); showTab(tab, focusContent = true) } }
        Sync.tick(this)
        // Marked expired? Check with Dodgy Box straight away - if it's been renewed, redraw without "Expired".
        Xtream.recheckIfExpired(this) { still -> if (!still && !isDestroyed && providers.isNotEmpty()) { tabViews.clear(); showTab(tab, focusContent = false) } }
        handler.postDelayed(rotateFeatured, FEATURE_MS)
        armSaver()
        if (heroCollapsed || searchBar?.visibility == View.VISIBLE) info.alpha = 0f
        Coach.resume(this, "watch") { coachTarget(it) }
        if (tab == "home" && rows.childCount > 0 && detail == null) { refreshPersonalRows(); refreshResumeRow() }
        else if (rows.childCount > 0 && detail == null) refreshResumeRow()
        // The player may still be saving where you got to: look again a moment later.
        handler.postDelayed({ if (!isFinishing && rows.childCount > 0 && detail == null) refreshResumeRow() }, 900)
    }

    internal fun coachTarget(name: String): View? = when {
        name == "nav" -> nav.findViewWithTag<View>(tab) ?: nav.getChildAt(0)
        name == "rows" -> firstItem() ?: rows
        name.startsWith("tab:") -> nav.findViewWithTag(name.removePrefix("tab:"))
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Screensaver up: any button puts it away (OK on a show you're watching carries on with it).
        saver?.let { sv ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val item = sv.getTag(R.id.card_info) as? ResumeStore.Item
                hideSaver()
                if (item != null && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) openResume(item)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) armSaver()
        rail?.let {
            if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val back = railReturn
                closeRail()
                (back?.takeIf { it.isAttachedToWindow } ?: firstItem())?.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        // Down from the tabs: the first title of the first row, not whichever card happens to sit under the tab.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && rail == null && detail == null &&
            currentFocus?.parent === nav && tab != "live" && tab != "search") {
            val list = firstContentRow()?.let { rowList(it) }
            val lm = list?.layoutManager as? LinearLayoutManager
            if (list != null && lm != null && list.childCount > 0) {
                val first = lm.findViewByPosition(0)
                if (first != null && first.isFocusable) { first.requestFocus(); return true }
                lm.scrollToPositionWithOffset(0, 0)
                list.post { lm.findViewByPosition(0)?.requestFocus() }
                return true
            }
        }
        // Up/Down between rows: land on the first card showing in the new row (like Apple TV), never a card half off screen.
        if (event.action == KeyEvent.ACTION_DOWN && rail == null && detail == null && (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            val f = currentFocus
            val rv = f?.parent as? RecyclerView
            val row = f?.let { rowOf(it) }
            if (rv != null && row != null) {
                val i = rows.indexOfChild(row)
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                var j = i + step
                while (j in 0 until rows.childCount && (rows.getChildAt(j).visibility != View.VISIBLE || listOf(rows.getChildAt(j)).none { rowList(it) != null || (it as? ViewGroup)?.getFocusables(View.FOCUS_DOWN)?.isNotEmpty() == true })) j += step
                val target = rows.getChildAt(j)
                val list = target?.let { rowList(it) }
                if (list != null && list.childCount > 0) {
                    val lm = list.layoutManager as? LinearLayoutManager
                    val first = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 } ?: lm?.findFirstVisibleItemPosition() ?: 0
                    val card = lm?.findViewByPosition(first) ?: list.getChildAt(0)
                    if (card != null && card.isFocusable) { card.requestFocus(); return true }
                }
            }
        }
        // Holding Left/Right along a row: the highlight stays in that row. At the end it stops (it used to slip into
        // another row when the remote repeated faster than the row could lay out its next cards).
        if (event.action == KeyEvent.ACTION_DOWN && rail == null && (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            val f = currentFocus
            val rv = f?.parent as? RecyclerView
            if (f != null && rv != null) {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else View.FOCUS_LEFT
                val next = android.view.FocusFinder.getInstance().findNextFocus(rv, f, dir)
                if (next == null) {
                    val sign = if (dir == View.FOCUS_RIGHT) 1 else -1
                    // Left at the very start of a Films/Shows row opens the categories (below); otherwise stay put.
                    val openRail = sign < 0 && (tab == "films" || tab == "shows") && detail == null && rv.getChildAdapterPosition(f) == 0
                    if (!openRail) {
                        if (rv.canScrollHorizontally(sign)) rv.smoothScrollBy(sign * dp(this, 160f), 0)
                        return true
                    }
                }
                if (event.repeatCount > 0 && rv.scrollState != RecyclerView.SCROLL_STATE_IDLE && next != null && next.parent !== rv) return true
            }
        }
        if ((tab == "films" || tab == "shows") && rail == null && detail == null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val f = currentFocus
            val rv = f?.parent as? RecyclerView
            if (f != null && rv != null && rowOf(f) != null && rv.getChildAdapterPosition(f) == 0) { openRail(); return true }
        }
        if (Coach.onKey(this, event) { coachTarget(it) }) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------- Categories rail down the left (Films / Shows), like Apple TV's sidebar ----------

    internal var rail: FrameLayout? = null

    internal var railReturn: View? = null

    // ---------- Screensaver of your shows (Apple's Top Shelf idea) ----------

    internal var saver: FrameLayout? = null
    internal val SAVER_MS = 3 * 60_000L
    internal val showSaver = Runnable { startSaver() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)          // nothing scheduled runs against a closed screen
        try { trailerView?.destroy() } catch (e: Exception) {}
        trailerView = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(showSaver)
        saver?.let { root.removeView(it) }; saver = null
        stopTrailer()
        handler.removeCallbacks(rotateFeatured)
    }

    // ---------- Tabs ----------

    /**
     * Rows that finish loading late only take the highlight if nothing else has it. Never while you're
     * moving along the tabs at the top (that's what made the highlight jump into a row mid-glide).
     */
    internal fun focusContentIfIdle() {
        rows.post {
            val f = currentFocus
            val onTabs = f != null && (f === nav || f.parent === nav)
            if (!onTabs && rows.findFocus() == null && (f == null || f === scroll || f === rows)) firstItem()?.requestFocus()
        }
    }

    internal fun tabButton(id: String, label: String): View {
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
            setOnClickListener {
                when {
                    id == "live" -> openGuide()
                    id == "search" -> askSearch()
                    id != tab -> showTab(id, focusContent = true)
                    else -> firstItem()?.requestFocus()
                }
            }
            if (id == "live") setOnKeyListener { _, k, e ->
                if (e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_DOWN) { openGuide(); true } else false
            }
            // tvOS: moving onto a tab shows it (after a short pause, so passing over doesn't load everything).
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f)
                    .setInterpolator(DecelerateInterpolator(2f)).setDuration(200).start()
                pendingTab?.let { handler.removeCallbacks(it) }
                if (has && id != tab) {
                    val r = Runnable {
                        if (!v.isFocused) return@Runnable
                        when (id) { "search" -> showSearchTab(); "live" -> showLiveTab(); else -> showTab(id, focusContent = false) }
                    }
                    pendingTab = r
                    handler.postDelayed(r, 200)
                }
            }
        }
    }

    internal var pendingTab: Runnable? = null

    // ---------- Hero that steps aside (Apple TV): once you're down in the rows they get the whole screen ----------

    internal var heroCollapsed = false
    internal var heroAnim: android.animation.ValueAnimator? = null

    internal lateinit var heroDim: View

    // ---------- Search as you type (Apple TV) ----------

    internal var searchBar: LinearLayout? = null
    internal var searchField: android.widget.EditText? = null
    internal val typedSearch = Runnable { searchField?.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { search(it, focusResults = false) } }
    internal fun tabIndex() = (0 until nav.childCount).firstOrNull { nav.getChildAt(it).tag == tab } ?: 0

    internal fun showTab(id: String, focusContent: Boolean) {
        setHeroCollapsed(false)
        if (id != "search") hideSearchBar()
        tab = id
        collectionFrom = null
        focusFirstLoaded = false
        for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == id
        ++buildToken                      // anything still loading for a search or "See All" page is dropped
        scroll.scrollTo(0, 0)
        val ttl = cacheTtl(id)
        val cached = tabViews[id]?.takeIf { ttl > 0 && System.currentTimeMillis() - it.second < ttl }
        if (cached != null) {
            swapRows(cached.first)
            if (id == "home") refreshPersonalRows()
            refreshResumeRow()
            if (focusContent) rows.post { firstItem()?.requestFocus() }
            return
        }
        freshRows()
        val token = newBuild()
        if (ttl > 0) tabViews[id] = rows to System.currentTimeMillis()
        when (id) {
            "home" -> buildHome(token)
            "films" -> buildCategory(token, "MOVIE")
            "shows" -> buildCategory(token, "SHOW")
            "live" -> buildLive(token)
            "list" -> buildList()
            "sport" -> buildSport(token)
            "tonight" -> buildTonight(token)
            "search" -> lastSearch?.let { search(it) }
        }
        refreshResumeRow()
        if (focusContent) rows.post { firstItem()?.requestFocus() }
    }

    // ---------- Films / Shows ----------

    /** Every category, in the order shown down the side rail (Apple TV style). */
    internal val railGenres = listOf("act" to "Action", "cmy" to "Comedy", "drm" to "Drama", "trl" to "Thriller", "hrr" to "Horror", "scf" to "Sci-Fi",
        "crm" to "Crime", "rma" to "Romance", "fnt" to "Fantasy", "ani" to "Animation", "fml" to "Family", "doc" to "Documentary",
        "hst" to "History", "war" to "War", "wsn" to "Western", "msc" to "Music", "spt" to "Sport", "rly" to "Reality TV")

    internal val genreList = listOf("act" to "Action", "cmy" to "Comedy", "drm" to "Drama", "trl" to "Thrillers", "crm" to "Crime",
        "scf" to "Sci-Fi", "fml" to "Family", "doc" to "Documentaries")

    // ---------- See More: a whole section, sorted into categories ----------

    internal val allGenres = genreList + listOf("hrr" to "Horror", "rma" to "Romance", "ani" to "Animation", "fnt" to "Fantasy",
        "rly" to "Reality TV", "hst" to "History", "spt" to "Sport", "war" to "War")

    internal var collectionFrom: String? = null
    internal var focusFirstLoaded = false

    internal var providerType = "MOVIE"

    // ---------- Live TV ----------

    // ---------- Live TV straight from the provider ----------

    internal var liveGroup: String? = null

    /** The names the main UK channels go by, in the order they're shown on the front page. */
    internal val popularNames = listOf("BBC One", "BBC Two", "ITV1", "Channel 4", "Channel 5", "ITV2", "BBC Three", "E4", "Sky News", "BBC News",
        "Dave", "ITV3", "ITV4", "Film4", "More4", "Drama", "5 USA", "CBBC", "CBeebies", "BBC Four")

    // ---------- Tonight ----------

    internal val BIG_CHANNELS = listOf("bbc one", "bbc two", "itv1", "channel 4", "5", "sky showcase", "sky max", "sky atlantic", "sky cinema premiere",
        "sky cinema hits", "film4", "e4", "more4", "dave", "itv2", "bbc three", "bbc four", "sky sports main event", "sky sports premier league", "tnt sports 1")
    internal val NOT_FILM = Regex("(?i)\\b(news|weather|live|football|darts|snooker|racing|rugby|cricket|golf|boxing|f1|grand prix|teleshopping|programmes resume|close)\\b")
    internal val SPORT = Regex("(?i)(\\bv\\b|\\bvs\\b|live:|premier league|champions league|football|darts|boxing|grand prix|rugby|snooker|cricket|golf)")

    internal val matchDay = java.text.SimpleDateFormat("EEE d MMM  ·  h:mma", java.util.Locale.UK)

    // ---------- Detail page ----------

    internal var detailScroll: ScrollView? = null
    internal var detailMore: View? = null

    internal var lastEpisodes: List<Xtream.Episode> = emptyList()

    // ---------- Hero ----------

    internal val rotateFeatured = object : Runnable {
        override fun run() {
            if (tab == "home" && detail == null && featured.isNotEmpty() && rowOf(currentFocus ?: nav) == null) {
                featuredIndex = (featuredIndex + 1) % featured.size
                showTitleHero(featured[featuredIndex], now = true)
                queueTrailer(null)
            }
            handler.postDelayed(this, FEATURE_MS)
        }
    }

    // ---------- Trailers behind the hero (Netflix-style) ----------

    internal lateinit var trailerHost: FrameLayout
    internal val trailerHostReady get() = ::trailerHost.isInitialized
    internal var trailerView: android.webkit.WebView? = null
    internal var trailerFor: String? = null
    internal val trailerJob = Runnable { startTrailer() }
    internal var trailerTitle: WatchCatalog.Title? = null

    internal var prefetchFor: String? = null

    // ---------- Rows ----------

    internal class Row(val view: LinearLayout, val list: RecyclerView)

    /** A row that stops at its first and last card: Left/Right never jumps to another row. */
    internal class EdgeStopRecyclerView(c: Context) : RecyclerView(c) {
        override fun focusSearch(focused: View, direction: Int): View? {
            val found = super.focusSearch(focused, direction)
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return found
            var p: View? = found
            while (p != null) { if (p === this) return found; p = p.parent as? View }
            return focused
        }
    }

    /** ScrollView that leaves scrolling to us (smooth row-by-row glide). */
    internal class RowScroll(c: Context) : ScrollView(c) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
    }

    // ---------- Cards ----------

    internal class Holder(val root: FrameLayout, val image: ImageView, val label: TextView, val sub: TextView? = null, val progress: View? = null) :
        RecyclerView.ViewHolder(root)

    internal class CardAdapter<T>(
        private var items: List<T>,
        private val create: (ViewGroup) -> Holder,
        /** "See More" at the end of the row (opens everything in that section). */
        private val more: (() -> Unit)? = null,
        private val moreCard: ((ViewGroup) -> Holder)? = null,
        private val bind: (Holder, T) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {
        fun update(list: List<T>) { items = list; notifyDataSetChanged() }
        /** Adds to the end without disturbing what's on screen (keeps the highlight where it is). */
        fun append(more: List<T>) {
            if (more.isEmpty()) return
            val at = items.size
            items = items + more
            notifyItemRangeInserted(at, more.size)
        }
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

    /** Grey cards that breathe gently while a row's titles load (so the page never looks empty or jumps). */
    internal inner class SkeletonAdapter(private val w: Float, private val h: Float, private val count: Int = 7) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val root = FrameLayout(c).apply {
                layoutParams = RecyclerView.LayoutParams(dp(c, w), dp(c, h)).apply { marginEnd = dp(c, 14f) }
                background = GradientDrawable().apply { cornerRadius = dp(c, 10f).toFloat(); setColor(0x1AFFFFFF) }
                isFocusable = false
            }
            return Holder(root, ImageView(c), TextView(c))
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.root.alpha = 0.5f
            holder.root.animate().cancel()
            holder.root.animate().alpha(1f).setStartDelay((position * 90L) % 500).setDuration(700).withEndAction {
                holder.root.animate().alpha(0.5f).setDuration(700).withEndAction { if (holder.root.isAttachedToWindow) onBindViewHolder(holder, position) }.start()
            }.start()
        }
        override fun onViewRecycled(holder: Holder) { holder.root.animate().cancel() }
    }

    /** Titles in a Top 10 row (for the "TOP 10" badge). */
    internal val top10Ids = HashSet<String>()

    // ---------- Bits ----------

    internal fun timeRange(p: LiveGuide.Programme): String {
        val f = DateFormat.getTimeFormat(this)
        return "${f.format(Date(p.start))} – ${f.format(Date(p.stop))}"
    }

    internal fun heroImage() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    internal fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@WatchActivity, top) }

    companion object {
        internal const val FEATURE_MS = 8000L
        /** "Who's watching?" when Watch opens: off (the profile is picked at home). */
        internal const val WATCH_WHO = false
        const val EXTRA_TAB = "tab"
        const val EXTRA_QUERY = "query"
    }
}
