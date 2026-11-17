package com.morgan.minimallauncher

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

// Part of the Watch screen: the tvOS 26 glass pop-ups - the menu when you hold OK on a title, and the sidebar.

/** A glass list item: icon and words, white with dark text when highlighted (tvOS). */
private fun WatchActivity.menuItem(label: String, icon: Int?, onClick: () -> Unit): View {
    val c = this
    val row = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = GlassUi.rowBackground(c, 14f)
        setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
        isFocusable = true; isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }
    if (icon != null) row.addView(ImageView(c).apply {
        setImageResource(icon); imageTintList = GlassUi.primaryText(); isDuplicateParentStateEnabled = true
    }, LinearLayout.LayoutParams(dp(c, 18f), dp(c, 18f)).apply { marginEnd = dp(c, 14f) })
    row.addView(GlassUi.rowText(c, label, 15f, R.font.inter_semibold, GlassUi.primaryText()), LinearLayout.LayoutParams(0, -2, 1f))
    GlassUi.focusLift(row, 1.03f)
    return row
}

private fun WatchActivity.glassBox(radius: Float) = LayerDrawable(arrayOf(
    GradientDrawable().apply { cornerRadius = dp(this, radius).toFloat(); setColor(0xCC1C1C1E.toInt()) },
    GlassUi.panel(this, radius, 0x1C)))

/** Closes whichever glass pop-up is open. */
internal fun WatchActivity.closePopMenu(): Boolean {
    val m = popMenu ?: return false
    popMenu = null
    m.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(m) }.start()
    popReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
    popReturn = null
    return true
}

/** Keys while a pop-up is open: Back (or Right on the sidebar) closes it; Left/Right can't wander off behind it. */
internal fun WatchActivity.popMenuKey(event: KeyEvent): Boolean? {
    val m = popMenu ?: return null
    if (event.keyCode == KeyEvent.KEYCODE_BACK || (m.tag == "sidebar" && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
        if (event.action == KeyEvent.ACTION_DOWN) closePopMenu(); return true
    }
    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true
    return null
}

/** Hold OK on a title: Play / Go to Film / My List / More Ways to Watch, beside the card (Apple TV app). */
internal fun WatchActivity.titleMenu(t: WatchCatalog.Title, anchor: View) {
    if (popMenu != null || detail != null) return
    val c = this
    val overlay = FrameLayout(c).apply { setBackgroundColor(0x40000000); alpha = 0f; isClickable = true; tag = "menu" }
    val menu = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBox(20f)
        setPadding(dp(c, 8f), dp(c, 8f), dp(c, 8f), dp(c, 8f))
    }
    val dodgy = Xtream.isSetUp(c) && !Xtream.isExpired(c)
    val items = listOf(
        menuItem("Play", R.drawable.ic_lucide_play) { closePopMenu(); if (dodgy) watchOnDodgyBox(t) else moreWays(t) },
        menuItem(if (t.type == "SHOW") "Go to Show" else "Go to Film", R.drawable.ic_lucide_more) { closePopMenu(); openDetail(t, anchor) },
        menuItem(if (WatchCatalog.inMyList(c, t)) "Remove from My List" else "Add to My List", R.drawable.ic_lucide_heart) {
            val added = WatchCatalog.toggleMyList(c, t); closePopMenu()
            GlassUi.toast(this, if (added) "Added to My List" else "Removed from My List")
        },
        menuItem("More Ways to Watch", R.drawable.ic_lucide_monitor) { closePopMenu(); moreWays(t) },
    )
    items.forEach { menu.addView(it, LinearLayout.LayoutParams(-1, dp(c, 48f)).apply { topMargin = dp(c, 2f) }) }
    // Beside the card, on whichever side has room.
    val a = IntArray(2); anchor.getLocationInWindow(a)
    val r = IntArray(2); root.getLocationInWindow(r)
    val w = dp(c, 270f)
    val x = (a[0] - r[0] + anchor.width + dp(c, 16f)).let { if (it + w > root.width - dp(c, 24f)) a[0] - r[0] - w - dp(c, 16f) else it }.coerceAtLeast(dp(c, 24f))
    val y = (a[1] - r[1]).coerceIn(dp(c, 24f), (root.height - dp(c, 240f)).coerceAtLeast(dp(c, 24f)))
    overlay.addView(menu, FrameLayout.LayoutParams(w, -2).apply { leftMargin = x; topMargin = y })
    popReturn = anchor
    popMenu = overlay
    root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    menu.scaleX = 0.92f; menu.scaleY = 0.92f
    overlay.animate().alpha(1f).setDuration(180).start()
    menu.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.enter).start()
    items.first().requestFocus()
}

/** The tvOS 26 sidebar: the same sections as the tabs, in a glass panel down the left. */
internal fun WatchActivity.openSidebar() {
    if (popMenu != null || detail != null) return
    val c = this
    val overlay = FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x99000000.toInt(), 0x00000000))
        alpha = 0f; isClickable = true; tag = "sidebar"
    }
    val panel = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBox(26f)
        setPadding(dp(c, 10f), dp(c, 14f), dp(c, 10f), dp(c, 14f))
    }
    panel.addView(GlassUi.text(c, ProfileManager.getActiveProfile(c), 16f, R.font.inter_semibold).apply { setPadding(dp(c, 16f), 0, 0, dp(c, 10f)) })
    val sections = listOf(
        Triple("search", "Search", R.drawable.ic_lucide_search), Triple("home", "Home", R.drawable.ic_lucide_home),
        Triple("films", "Films", R.drawable.ic_lucide_film), Triple("shows", "Shows", R.drawable.ic_lucide_monitor),
        Triple("live", "Live TV", R.drawable.ic_lucide_play), Triple("sport", "Sport", R.drawable.ic_lucide_sparkle),
        Triple("list", "My Stuff", R.drawable.ic_lucide_heart))
    var current: View? = null
    for ((id, label, icon) in sections) {
        val item = menuItem(label, icon) {
            closePopMenu()
            // Exactly what choosing the tab does (Live TV still opens the guide).
            nav.findViewWithTag<View>(id)?.let { it.requestFocus(); it.performClick() }
        }
        panel.addView(item, LinearLayout.LayoutParams(-1, dp(c, 46f)).apply { topMargin = dp(c, 2f) })
        if (id == tab) current = item
    }
    overlay.addView(panel, FrameLayout.LayoutParams(dp(c, 250f), -2, Gravity.START or Gravity.TOP).apply { setMargins(dp(c, 22f), dp(c, 22f), 0, 0) })
    popReturn = currentFocus
    popMenu = overlay
    root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    panel.translationX = -dp(c, 60f).toFloat()
    overlay.animate().alpha(1f).setDuration(200).start()
    panel.animate().translationX(0f).setDuration(260).setInterpolator(Motion.enter).start()
    (current ?: panel.getChildAt(1))?.requestFocus()
}
