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

// Part of the Watch screen (WatchActivity), kept in its own file to keep each piece small.

// ---------- Your Apps: each service's whole library ----------

/** Every service as a tile; opens that service's page. */
internal fun WatchActivity.appsRow() {
    val dodgy: List<Any> = if (Xtream.isSetUp(this)) listOf("dodgy") else emptyList()
    val list: List<Any> = dodgy + installed + providers.filter { it.installed == null }
    if (list.isEmpty()) return
    val r = row("apps", "Your Apps", height = 120f)
    r.list.adapter = CardAdapter(list, { p -> serviceCard(p) }) { h, item ->
        if (item == "dodgy") {
            val expired = Xtream.isExpired(this)
            h.label.text = if (expired) "Dodgy Box · Expired" else "Dodgy Box"
            h.root.contentDescription = "Dodgy Box"
            h.image.setImageResource(R.drawable.ic_lucide_film)
            h.image.setColorFilter(0xFFFFFFFF.toInt())
            h.image.setPadding(dp(this, 8f), dp(this, 8f), dp(this, 8f), dp(this, 8f))
            h.image.background = GradientDrawable().apply { cornerRadius = dp(this@appsRow, 10f).toFloat(); setColor(0xFFE50914.toInt()) }
            h.root.alpha = if (expired) 0.6f else 1f
            h.root.setOnClickListener { openDodgyBoxPage("MOVIE") }
            focusEffect(h.root) { showPlainHero("YOUR BOX", "Dodgy Box", if (expired) "The subscription has run out - renew it to watch again." else "Every film, series and channel on your Dodgy Box - it all plays right here.") }
        } else bindProvider(h, item as WatchCatalog.Provider)
    }
    rows.addView(r.view)
}

internal fun WatchActivity.bindProvider(h: Holder, p: WatchCatalog.Provider) {
    run {
        h.image.colorFilter = null; h.image.background = null; h.image.setPadding(0, 0, 0, 0); h.root.alpha = 1f
        h.label.text = p.displayName
        h.root.contentDescription = p.displayName
        ImageLoader.load(h.image, p.icon, ImageLoader.ICON)
        h.root.setOnClickListener { openProviderPage(p) }
        focusEffect(h.root) { showPlainHero(if (p.installed != null) "YOUR APP" else "GET THE APP", p.displayName, "Every film and series on ${p.displayName}, by category.") }
    }
}

/** A service tile: its logo on a dark glass card, name underneath. */
internal fun WatchActivity.serviceCard(parent: ViewGroup): Holder {
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

// ---------- Dodgy Box library (everything here really plays) ----------

internal fun WatchActivity.libTitle(name: String, type: String, image: String?) = WatchCatalog.Title(
    "dodgy:$type:${TextMatch.vodKey(name)}", type, Xtream.clean(name),
    Regex("\\b(19|20)\\d{2}\\b").find(name)?.value?.toIntOrNull() ?: 0, image, image, "", null, emptyList(), emptyList())

internal fun WatchActivity.openLibraryFilm(v: Xtream.Vod) {
    if (Xtream.isExpired(this)) { Xtream.showExpired(this); return }
    playFilm(libTitle(v.name, "MOVIE", v.icon), Xtream.vodUrl(applicationContext, v))
}

internal fun WatchActivity.openLibrarySeries(s: Xtream.Series) {
    if (Xtream.isExpired(this)) { Xtream.showExpired(this); return }
    val t = libTitle(s.name, "SHOW", s.cover)
    val dismiss = showFinding(t)
    val app = applicationContext
    Bg.run {
        val eps = try { Xtream.episodes(app, s) } catch (e: Exception) { emptyList() }
        runOnUiThread {
            dismiss()
            if (isDestroyed) return@runOnUiThread
            if (eps.isEmpty()) GlassUi.toast(this, "${t.name} has no episodes on Dodgy Box right now") else chooseEpisode(t, eps)
        }
    }
}

internal fun WatchActivity.bindVod(h: Holder, v: Xtream.Vod) {
    val name = Xtream.clean(v.name)
    h.label.text = name
    h.root.contentDescription = name
    ImageLoader.load(h.image, v.icon)
    h.root.setOnClickListener { openLibraryFilm(v) }
    h.root.setOnLongClickListener {
        GlassDialog(this).title(name)
            .button("Watch", GlassDialog.Style.BOLD) { openLibraryFilm(v) }
            .button("Download for Later") { GlassUi.toast(this, Downloads.start(this, Xtream.vodUrl(applicationContext, v), name, image = v.icon)) }
            .button("Cancel").show()
        true
    }
    focusEffect(h.root) { showPlainHero("DODGY BOX  ·  FILM", name, "OK to watch  ·  hold OK to download for later"); setHeroImage(v.icon) }
}

internal fun WatchActivity.bindSeries(h: Holder, s: Xtream.Series) {
    val name = Xtream.clean(s.name)
    h.label.text = name
    h.root.contentDescription = name
    ImageLoader.load(h.image, s.cover)
    h.root.setOnClickListener { openLibrarySeries(s) }
    focusEffect(h.root) {
        showPlainHero("DODGY BOX  ·  SERIES", name, "Press OK for the episodes."); setHeroImage(s.cover)
        val app = applicationContext
        Thread { try { Xtream.episodes(app, s) } catch (_: Exception) {} }.apply { priority = Thread.MIN_PRIORITY }.start()   // ready for OK
    }
}

/** On a Kids profile: only what's in children's categories (call off the main thread). */
internal fun WatchActivity.kidsFilms(l: List<Xtream.Vod>): List<Xtream.Vod> {
    if (!Kids.isOn(this)) return l
    val names = Xtream.vodCategories(applicationContext)
    return l.filter { Kids.isKidsCategory(names[it.category].orEmpty()) }
}

internal fun WatchActivity.kidsSeries(l: List<Xtream.Series>): List<Xtream.Series> {
    if (!Kids.isOn(this)) return l
    val names = Xtream.seriesCategories(applicationContext)
    return l.filter { Kids.isKidsCategory(names[it.category].orEmpty()) }
}

/** A row of Dodgy Box films or series. */
internal fun <T> WatchActivity.libRow(key: String, title: String, items: List<T>, bind: (Holder, T) -> Unit): Row {
    val r = row(key, title, height = 196f)
    r.list.adapter = CardAdapter(items, { p -> posterCard(p) }, bind = bind)
    return r
}

/** "New on Dodgy Box" rows for the Home tab (loaded in the background, then slotted in). */
internal fun WatchActivity.dodgyHomeRows(token: Int) {
    if (!Xtream.isSetUp(this) || Xtream.isExpired(this)) return
    val host = rows
    val app = applicationContext
    Bg.run {
        val films = kidsFilms(try { Xtream.newestFilms(app, if (Kids.isOn(app)) 600 else 30) } catch (e: Exception) { emptyList() }).take(30)
        val shows = kidsSeries(try { Xtream.newestSeries(app, if (Kids.isOn(app)) 600 else 30) } catch (e: Exception) { emptyList() }).take(30)
        runOnUiThread {
            if (containerToken[host] != token || isDestroyed) return@runOnUiThread
            // Just under Your Apps.
            var at = (0 until host.childCount).indexOfFirst { host.getChildAt(it).tag == "apps" }.let { if (it < 0) host.childCount else it + 1 }
            if (films.isNotEmpty()) host.addView(libRow("dodgy_films", "New Films on Dodgy Box", films) { h, v -> bindVod(h, v) }.view, at++)
            if (shows.isNotEmpty()) host.addView(libRow("dodgy_series", "New Series on Dodgy Box", shows) { h, v -> bindSeries(h, v) }.view, at)
        }
    }
}

/** Dodgy Box's own page, like a streaming app: Films / Series, new first, then every category. */
internal fun WatchActivity.openDodgyBoxPage(type: String = providerType) {
    if (Xtream.isExpired(this)) { Xtream.showExpired(this); return }
    if (collectionFrom == null) collectionFrom = tab
    tab = "collection"
    providerType = type
    freshRows()
    scroll.scrollTo(0, 0)
    val token = newBuild()
    showPlainHero("DODGY BOX", "Dodgy Box", "Everything on your Dodgy Box - it all plays right here.")
    val heads = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; tag = "heads" }
    var selected: View? = null
    for ((id, label) in listOf("MOVIE" to "Films", "SHOW" to "Series", "LIVE" to "Live TV")) {
        val b = pillButton(label, primary = id == type) {
            if (id == "LIVE") startActivity(Intent(this, LiveGuideActivity::class.java))
            else if (id != providerType) openDodgyBoxPage(id)
        }
        heads.addView(b, LinearLayout.LayoutParams(-2, dp(this, 38f)).apply { marginEnd = dp(this@openDodgyBoxPage, 12f) })
        if (id == type) selected = b
    }
    rows.addView(heads, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@openDodgyBoxPage, 56f); topMargin = dp(this@openDodgyBoxPage, 8f) })
    selected?.requestFocus()
    // Continue Watching from Dodgy Box, of this kind.
    ResumeStore.forTab(this, if (type == "MOVIE") "films" else "shows").takeIf { it.isNotEmpty() }?.let { items ->
        val r = row("resume_db", "Continue Watching", height = 150f)
        r.list.adapter = CardAdapter(items, { p -> wideCard(p) }) { h, item -> bindResume(h, item) }
        rows.addView(r.view)
    }
    val loading = GlassUi.text(this, "Loading Dodgy Box…", 13f, R.font.inter_medium, 0x99FFFFFF.toInt())
    rows.addView(loading, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@openDodgyBoxPage, 56f); topMargin = dp(this@openDodgyBoxPage, 14f) })
    val host = rows
    val app = applicationContext
    Bg.run {
        val newest: List<Any>; val groups: List<Pair<String, List<Any>>>
        try {
            val kids = Kids.isOn(app)
            if (type == "MOVIE") { newest = kidsFilms(Xtream.newestFilms(app, if (kids) 600 else 40)).take(40); groups = Xtream.filmsByCategory(app).filter { !kids || Kids.isKidsCategory(it.first) } }
            else { newest = kidsSeries(Xtream.newestSeries(app, if (kids) 600 else 40)).take(40); groups = Xtream.seriesByCategory(app).filter { !kids || Kids.isKidsCategory(it.first) } }
        } catch (e: Exception) {
            runOnUiThread { if (containerToken[host] == token) loading.text = "Couldn’t reach Dodgy Box - check the internet and VPN" }
            return@run
        }
        runOnUiThread {
            if (containerToken[host] != token || isDestroyed) return@runOnUiThread
            host.removeView(loading)
            val what = if (type == "MOVIE") "Films" else "Series"
            fun add(key: String, title: String, list: List<Any>) {
                val r = libRow(key, title, list.take(60)) { h, x -> if (x is Xtream.Vod) bindVod(h, x) else if (x is Xtream.Series) bindSeries(h, x) }
                host.addView(r.view)
            }
            if (newest.isNotEmpty()) add("db_new", "New $what", newest)
            // The rest fill in a few at a time so the page stays smooth.
            var i = 0
            fun more() {
                if (containerToken[host] != token || isDestroyed) return
                val end = minOf(i + 4, groups.size)
                while (i < end) { val (name, list) = groups[i]; add("db_c$i", "${name}  ·  ${list.size}", list); i++ }
                if (i < groups.size) handler.postDelayed({ more() }, 120)
            }
            more()
            if (newest.isEmpty() && groups.isEmpty()) host.addView(GlassUi.text(this, "Nothing here yet.", 13f, R.font.inter_medium, 0x99FFFFFF.toInt()),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@openDodgyBoxPage, 56f) })
        }
    }
}

/** Search results from the Dodgy Box library (shown first, because they play straight away). */
internal fun WatchActivity.dodgySearchRows(token: Int, query: String) {
    if (!Xtream.isSetUp(this)) return
    val host = rows
    val app = applicationContext
    Bg.run {
        val found = try { Xtream.searchLibrary(app, query) } catch (e: Exception) { emptyList<Xtream.Vod>() to emptyList() }
        val films = kidsFilms(found.first); val shows = kidsSeries(found.second)
        runOnUiThread {
            if (containerToken[host] != token || isDestroyed) return@runOnUiThread
            var at = 0
            if (films.isNotEmpty()) host.addView(libRow("s_films", "Films on Dodgy Box", films) { h, v -> bindVod(h, v) }.view, at++)
            if (shows.isNotEmpty()) host.addView(libRow("s_series", "Series on Dodgy Box", shows) { h, v -> bindSeries(h, v) }.view, at)
            if (films.isNotEmpty() || shows.isNotEmpty()) focusContentIfIdle()
        }
    }
}

/**
 * A service's own page: Films / Series at the top, then everything it has of that
 * type - all of it, then by category. Rows keep loading as you move along them.
 */
internal fun WatchActivity.openProviderPage(p: WatchCatalog.Provider, type: String = providerType) {
    if (collectionFrom == null) collectionFrom = tab
    tab = "collection"
    providerType = type
    freshRows()
    scroll.scrollTo(0, 0)
    val token = newBuild()
    showPlainHero(if (p.installed != null) "YOUR APP" else "GET THE APP", p.displayName,
        "Everything on ${p.displayName}" + if (p.installed == null) ". Choose a title to get the app." else ", by category.")
    // Films | Series
    val heads = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; tag = "heads" }
    var selected: View? = null
    for ((id, label) in listOf("MOVIE" to "Films", "SHOW" to "Series")) {
        val b = pillButton(label, primary = id == type) { if (id != providerType) openProviderPage(p, id) }
        heads.addView(b, LinearLayout.LayoutParams(-2, dp(this, 38f)).apply { marginEnd = dp(this@openProviderPage, 12f) })
        if (id == type) selected = b
    }
    rows.addView(heads, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@openProviderPage, 56f); topMargin = dp(this@openProviderPage, 8f) })
    selected?.requestFocus()
    val what = if (type == "MOVIE") "Films" else "Series"
    pagedRow(token, "all", "All $what", listOf(p.short), listOf(type), emptyList())
    for ((code, name) in allGenres) pagedRow(token, "g_$code", name, listOf(p.short), listOf(type), listOf(code))
}

/** A row of a whole library that fetches the next 100 as you near its end. */
internal fun WatchActivity.pagedRow(token: Int, key: String, title: String, packages: List<String>, types: List<String>, genres: List<String>) {
    val r = row(key, title, height = 196f)
    val items = mutableListOf<WatchCatalog.Title>()
    val adapter = CardAdapter(items.toList(), { p -> posterCard(p) }) { h, t -> bindTitle(h, t) }
    r.list.adapter = SkeletonAdapter(104f, 156f)
    val host = rows
    host.addView(r.view)
    var cursor: String? = null
    var loading = false
    var finished = false
    fun load() {
        if (loading || finished) return
        loading = true
        WatchCatalog.page(this, packages, types, genres, after = cursor) { list, next, total ->
            loading = false
            if (containerToken[host] != token || isDestroyed) return@page
            if (list == null) { if (items.isEmpty()) host.removeView(r.view); finished = true; return@page }
            if (list.isEmpty() && items.isEmpty()) { host.removeView(r.view); finished = true; return@page }
            val first = items.isEmpty()
            items += list
            if (first) { r.list.adapter = adapter; adapter.update(items.toList()) } else adapter.append(list)
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

internal fun WatchActivity.buildCategory(token: Int, type: String) {
    val what = if (type == "MOVIE") "Films" else "Series"
    val all = categoryLoads(type) + listOfNotNull(if (Xtream.isSetUp(this)) Triple("soon", "Not on Dodgy Box Yet · Remind Me", comingSoon(type)) else null)
    val host = rows
    fun add(from: Int, to: Int) {
        if (containerToken[host] != token || isDestroyed) return
        val saved = rows; rows = host           // rows may point at another tab by now
        for (i in from until minOf(to, all.size)) {
            val (key, title, load) = all[i]
            val more: () -> Unit = if (key.startsWith("g")) {
                { openCollection("$title $what", catalogue(shorts(), types = listOf(type), genre = key.substringAfter("_"))) }
            } else {
                { openCollection("All $what", catalogue(shorts(), types = listOf(type), sort = if (key == "trend") "TRENDING" else "POPULAR")) }
            }
            titleRow(token, key, title, quietFail = key.startsWith("g"), more = more, load = load)
        }
        rows = saved
        // The rest wait until you scroll near them - building every row at once made the page stutter.
        if (to < all.size) moreRowsFor[host] = { moreRowsFor.remove(host); add(to, to + 3) } else moreRowsFor.remove(host)
    }
    add(0, 5)                                    // the first screenful straight away
}

/** Popular new titles that aren't on Dodgy Box yet - OK on one, then Remind Me. */
internal fun WatchActivity.comingSoon(type: String): TitleLoader = { done ->
    val app = applicationContext
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    WatchCatalog.titles(this, packages = shorts(), types = listOf(type), first = 40) { l ->
        val fresh = l?.filter { it.year >= year - 1 }.orEmpty()
        Bg.run {
            val missing = fresh.filterNot { DodgyAlerts.available(app, it.name, it.year, it.type) }.take(15)
            runOnUiThread { if (!isDestroyed) done(missing) }
        }
    }
}

/** Quietly load the other tabs (and their first posters) so switching is instant. */
internal fun WatchActivity.prefetch() {
    val warm: (List<WatchCatalog.Title>?) -> Unit = { list -> list?.take(8)?.forEach { t -> t.poster?.let { ImageLoader.prefetch(this, it) } } }
    // Just the first few rows of Films and Shows (the rest load as you scroll there) - fetching
    // every row at once fought with scrolling for the first few seconds.
    for (type in listOf("MOVIE", "SHOW")) categoryLoads(type).take(4).forEach { (_, _, load) -> load(warm) }
    LiveGuide.load(this) {}
}
