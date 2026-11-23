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

// ---------- Home ----------

internal fun WatchActivity.shorts() = (installed.ifEmpty { providers }).map { it.short }

internal fun WatchActivity.buildHome(token: Int) {
    refreshPersonalRows()
    if (!Kids.isOn(this)) buildTonight(token, onHome = true)      // Tonight lives on Home now
    if (Kids.isOn(this)) {
        dodgyHomeRows(token)
        for (type in listOf("MOVIE", "SHOW")) categoryLoads(type).forEach { (key, title, load) -> titleRow(token, "h_${type}_$key", title, quietFail = true, load = load) }
        return
    }
    titleRow(token, "top10", "Top 10 on Your Apps", ranked = true,
        more = { openCollection("Trending on Your Apps", catalogue(shorts(), sort = "TRENDING")) }) { done ->
        WatchCatalog.titles(this, sort = "TRENDING", packages = shorts(), first = 10) { list ->
            if (list != null && featured.isEmpty()) { featured = list.take(6); if (rowOf(currentFocus ?: nav) == null) showTitleHero(featured[0], now = true) }
            done(list)
        }
    }
    appsRow()
    dodgyHomeRows(token)
    for ((type, label) in listOf("MOVIE" to "Films", "SHOW" to "Shows")) {
        titleRow(token, "top10_$type", "Top 10 $label Today", ranked = true, quietFail = true) { done ->
            WatchCatalog.titles(this, sort = "TRENDING", packages = shorts(), types = listOf(type), first = 10, done = done)
        }
    }
    // Collections, changing with the time of year.
    val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
    val seasonal = when (month) { 9 -> "Halloween" to "halloween"; 10, 11 -> "Christmas Films" to "christmas"; 1 -> "Love Stories" to "love"; else -> null }
    seasonal?.let { (label, q) ->
        titleRow(token, "col_season", label, quietFail = true) { done -> WatchCatalog.titles(this, search = q, first = 30, types = listOf("MOVIE"), done = done) }
    }
    titleRow(token, "col_acclaimed", "Award-Worthy · IMDb 8+", quietFail = true) { done ->
        WatchCatalog.titles(this, packages = shorts(), first = 60) { l -> done(l?.filter { (it.imdb ?: 0.0) >= 8.0 }?.sortedByDescending { it.imdb }) }
    }
    WatchCatalog.history(this).firstOrNull()?.let { last ->
        val row = titleRow(token, "because", "Because You Watched ${last.name}") { done ->
            WatchCatalog.detail(this, last) { d -> done(d?.similar) }
        }
        row.view.tag = "because"
    }
    // Rated two thumbs up: more like it.
    Ratings.loved(this).firstOrNull()?.let { loved ->
        titleRow(token, "loved", "Because You Loved $loved", quietFail = true) { done ->
            WatchCatalog.titles(this, search = loved, first = 1) { l ->
                val t = l?.firstOrNull() ?: return@titles done(null)
                WatchCatalog.detail(this, t) { d -> done(d?.similar) }
            }
        }
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
internal fun WatchActivity.refreshPersonalRows() {
    if (tab != "home") return
    listOf("history", "mylist", "recordings").forEach { key -> findRow(key)?.let { rows.removeView(it) } }
    val history = WatchCatalog.history(this)
    val myList = WatchCatalog.myList(this)
    var at = 0
    if (history.isNotEmpty()) { rows.addView(staticTitleRow("history", "Recently Viewed", history).view, at++) }
    if (myList.isNotEmpty()) { rows.addView(staticTitleRow("mylist", "My List", myList).view, at++) }
    findRow("recordings")?.let { rows.removeView(it) }
    recordingsRow("recordings")?.let { rows.addView(it, at) }
}

/** The rows of the Films / Shows tabs: (key, title, loader). */
internal fun WatchActivity.categoryLoads(type: String): List<Triple<String, String, TitleLoader>> {
    val what = if (type == "MOVIE") "Films" else "Shows"
    val list = mutableListOf<Triple<String, String, TitleLoader>>()
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    // The best rated of a big popular list; and what's newest (this year and last), most popular first.
    fun best(l: List<WatchCatalog.Title>?) = l?.filter { (it.imdb ?: 0.0) >= 6.8 }?.sortedByDescending { it.imdb }?.take(30)
    fun newest(l: List<WatchCatalog.Title>?) = l?.filter { it.year >= year - 1 }?.take(30)
    fun row(key: String, title: String, genres: List<String>, sort: String = "POPULAR", pick: (List<WatchCatalog.Title>?) -> List<WatchCatalog.Title>?) =
        list.add(Triple<String, String, TitleLoader>(key, title) { d ->
            WatchCatalog.titles(this, sort = sort, packages = shorts(), types = listOf(type), genres = genres, first = 60) { l -> d(pick(l)) }
        })
    if (Kids.isOn(this)) {
        // Kids profile: family and animation only.
        row("k_fml", "Family $what", listOf("fml")) { it?.take(40) }
        row("k_ani", "Animated $what", listOf("ani")) { it?.take(40) }
        row("k_new", "New Family $what", listOf("fml"), "TRENDING") { newest(it) }
        row("k_best", "Best Family $what", listOf("fml")) { best(it) }
        return list
    }
    row("trend", "Trending $what", emptyList(), "TRENDING") { it?.take(30) }
    row("new", "New $what", emptyList(), "TRENDING") { newest(it) }
    row("best", "Best Rated $what", emptyList()) { best(it) }
    list += Triple<String, String, TitleLoader>("pop", "All $what") { d -> WatchCatalog.titles(this, packages = shorts(), types = listOf(type), first = 60, done = d) }
    for ((g, name) in railGenres) row("g_$g", name, listOf(g)) { it?.take(40) }
    // A few extras for the big genres.
    for ((g, name) in genreList.take(4)) row("gb_$g", "Best $name", listOf(g)) { best(it) }
    return list
}

/**
 * Rows for a See More page: all the films, all the series, then each category
 * (or, for one category, the services that have it).
 */
internal fun WatchActivity.catalogue(packages: List<String>, types: List<String> = emptyList(), sort: String = "POPULAR", genre: String? = null):
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

internal fun WatchActivity.openCollection(title: String, sections: List<Triple<String, String, TitleLoader>>) {
    if (collectionFrom == null) collectionFrom = tab
    tab = "collection"
    freshRows()
    scroll.scrollTo(0, 0)
    val token = newBuild()
    showPlainHero("SEE ALL", title, "Everything, by category.")
    focusFirstLoaded = true
    Diag.send(this, "watch", "See More opened \"$title\" with ${sections.size} sections, apps=${shorts().joinToString(",")}")
    for ((key, name, load) in sections) titleRow(token, key, name, quietFail = true) { done ->
        load { l -> if (key == sections.first().first) Diag.send(this, "watch", "See More \"$title\" first section: ${l?.size ?: "failed"} titles"); done(l) }
    }
}

internal fun WatchActivity.closeCollection() {
    val back = collectionFrom ?: return
    collectionFrom = null
    showTab(back, focusContent = true)
}
