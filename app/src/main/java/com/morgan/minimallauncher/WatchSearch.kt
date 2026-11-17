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

internal fun WatchActivity.recentKey() = ProfileManager.scope(this, "recent_searches")

internal fun WatchActivity.recentSearches() = Prefs.string(this, recentKey())?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

internal fun WatchActivity.rememberSearch(q: String) = Prefs.setString(this, recentKey(), (listOf(q) + recentSearches().filterNot { it.equals(q, true) }).take(8).joinToString("\n"))

internal fun WatchActivity.showSearchBar(focus: Boolean) {
    val c = this
    val bar = searchBar ?: LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        val field = android.widget.EditText(c).apply {
            hint = "Films, shows, people, channels…"
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x80FFFFFF.toInt()); textSize = 20f
            typeface = GlassUi.font(c, R.font.inter_semibold)
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(c, 20f), dp(c, 12f), dp(c, 20f), dp(c, 12f))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(c, 14f).toFloat(); setColor(0x40FFFFFF); setStroke(dp(c, 2f), 0xFFFFFFFF.toInt()) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(c, 14f).toFloat(); setColor(0x26FFFFFF) })
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    handler.removeCallbacks(typedSearch)
                    if (!hasFocus()) return
                    if (e.isNullOrBlank()) { lastSearch = null; freshRows(); ++buildToken; showRecentSearches() }
                    else handler.postDelayed(typedSearch, 650)          // results as you type
                }
            })
            setOnEditorActionListener { v, _, _ ->
                val q = v.text.toString().trim()
                if (q.isNotBlank()) { handler.removeCallbacks(typedSearch); rememberSearch(q); search(q) }
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        searchField = field
        addView(field, LinearLayout.LayoutParams(dp(c, 440f), -2))
        addView(pillButton("🎤  Speak") {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search films, shows and TV"))
            } catch (e: ActivityNotFoundException) { GlassUi.toast(this@showSearchBar, "Voice search isn’t available") }
        }, LinearLayout.LayoutParams(-2, dp(c, 44f)).apply { marginStart = dp(c, 12f) })
        root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(c, 56f), dp(c, 84f), 0, 0) })
        searchBar = this
    }
    bar.visibility = View.VISIBLE
    info.alpha = 0f
    if (focus) searchField?.let { f ->
        f.requestFocus()
        f.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f, 0) }
    }

internal fun WatchActivity.hideSearchBar() {
    searchBar?.visibility = View.GONE
    if (!heroCollapsed) info.alpha = 1f

internal fun WatchActivity.showRecentSearches() {
    val recent = recentSearches()
    if (recent.isEmpty()) return
    val c = this
    val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; tag = "recent" }
    box.addView(GlassUi.text(c, "Recent Searches", 15f, R.font.inter_semibold), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 56f); topMargin = dp(c, 10f) })
    val pills = LinearLayout(c).apply { setPadding(dp(c, 56f), dp(c, 10f), dp(c, 56f), 0); clipChildren = false; clipToPadding = false }
    for (q in recent) pills.addView(pillButton(q) { searchField?.setText(q); search(q) }, LinearLayout.LayoutParams(-2, dp(c, 38f)).apply { marginEnd = dp(c, 10f) })
    box.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(pills) })
    rows.addView(box)

// ---------- Search ----------

/** OK on Search: type straight into the search bar (results appear as you type). */
internal fun WatchActivity.askSearch() { showSearchTab(); showSearchBar(focus = true) }

internal fun WatchActivity.search(query: String, focusResults: Boolean = true) {
    lastSearch = query
    if (focusResults) rememberSearch(query)
    showSearchBar(focus = false)
    searchField?.let { f -> if (!f.hasFocus() && f.text.toString() != query) f.setText(query) }
    if (detail != null) closeDetail()
    tab = "search"
    for (i in 0 until nav.childCount) nav.getChildAt(i).isSelected = nav.getChildAt(i).tag == "search"
    freshRows()
    scroll.scrollTo(0, 0)
    val token = newBuild()
    showPlainHero("SEARCH", "“$query”", "")
    dodgySearchRows(token, query)
    titleRow(token, "results", if (Xtream.isSetUp(this)) "On Your Other Apps" else "Films & Shows") { done ->
        WatchCatalog.titles(this, search = query, first = 30) { l -> done(if (Kids.isOn(this)) l?.filter { t -> t.genres.any { g -> Kids.isKidsCategory(g) } } else l) }
    }
    LiveGuide.load(this) { channels ->
        if (token != buildToken || channels == null) return@load
        val q = query.lowercase().trim()
        val words = q.split(Regex("\\s+")).filter { it.length > 1 }
        // Every word, in any order ("doctor who", "who doctor"); exact phrase first.
        val hits = channels.flatMap { ch -> ch.upcoming().filter { p -> val t = p.title.lowercase(); t.contains(q) || words.isNotEmpty() && words.all { it in t } }.map { ch to it } }
            .sortedBy { (_, p) -> if (p.title.lowercase().startsWith(q)) 0 else if (p.title.lowercase().contains(q)) 1 else 2 }.take(20)
        if (hits.isEmpty()) return@load
        val r = row("ontv", "On TV", height = 150f)
        r.list.adapter = CardAdapter(hits, { p -> wideCard(p) }) { h, (ch, prog) -> bindProgramme(h, ch, prog) }
        rows.addView(r.view)
    }
    if (focusResults) {
        rows.post { firstItem()?.requestFocus() }
        handler.postDelayed({ if (tab == "search" && rows.findFocus() == null) firstItem()?.requestFocus() }, 1500)
    }
