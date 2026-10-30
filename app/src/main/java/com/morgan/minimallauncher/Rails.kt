package com.morgan.minimallauncher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * Horizontal "cinematic" content rails (Favourites, Recommended, Recently
 * Watched) below the dock: large 16:9 artwork, no cards, the name fading
 * in under the focused tile, and the row gliding so the focused tile
 * stays at the left keyline.
 */
object Rails {

    class Item(val app: AppEntry)

    fun build(
        c: Context,
        container: LinearLayout,
        rails: List<Pair<String, List<AppEntry>>>,
        onOpen: (AppEntry, View) -> Unit,
        onMenu: (AppEntry, View) -> Unit,
    ) {
        container.removeAllViews()
        for ((title, apps) in rails) {
            if (apps.isEmpty()) continue
            container.addView(GlassUi.text(c, title, 15f, R.font.inter_semibold).apply {
                setShadowLayer(dp(c, 6f).toFloat(), 0f, 0f, 0x59000000)
            }, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(c, 42f)
                topMargin = dp(c, if (container.childCount == 0) 0f else 18f)
            })
            val list = RecyclerView(c).apply {
                layoutManager = KeylineLayoutManager(c, dp(c, 42f))
                adapter = Adapter(apps, onOpen, onMenu)
                clipChildren = false
                clipToPadding = false
                setPadding(dp(c, 42f), dp(c, 12f), dp(c, 42f), dp(c, 6f))
                itemAnimator = null
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            container.addView(list, LinearLayout.LayoutParams(-1, dp(c, 150f)))
        }
    }

    /** Keeps the focused tile at the left keyline with a smooth glide. */
    private class KeylineLayoutManager(c: Context, private val keyline: Int) :
        LinearLayoutManager(c, HORIZONTAL, false) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
        ): Boolean {
            val dx = child.left - keyline
            if (dx != 0) parent.smoothScrollBy(dx, 0, DecelerateInterpolator(2f))
            return true
        }
    }

    private class Holder(val root: LinearLayout, val art: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    private class Adapter(
        val apps: List<AppEntry>,
        val onOpen: (AppEntry, View) -> Unit,
        val onMenu: (AppEntry, View) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                isFocusable = true
                isClickable = true
                isLongClickable = true
            }
            val frame = FrameLayout(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(c, 12f).toFloat(); setColor(0xFF26252A.toInt()) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                foreground = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.tile_bezel)
                isDuplicateParentStateEnabled = true
            }
            val art = ImageView(c)
            frame.addView(art, FrameLayout.LayoutParams(-1, -1))
            root.addView(frame, LinearLayout.LayoutParams(dp(c, 176f), dp(c, 99f)))
            val label = GlassUi.text(c, "", 11.5f, R.font.inter_medium).apply {
                alpha = 0f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START
            }
            root.addView(label, LinearLayout.LayoutParams(dp(c, 176f), -2).apply { topMargin = dp(c, 9f) })
            root.setOnFocusChangeListener { _, has ->
                val ease = DecelerateInterpolator(2f)
                frame.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                    .translationZ(if (has) dp(c, 14f).toFloat() else 0f).setInterpolator(ease).setDuration(240).start()
                GlassUi.spotlight(frame, has)
                label.animate().alpha(if (has) 1f else 0f).translationY(if (has) dp(c, 4f).toFloat() else 0f)
                    .setInterpolator(ease).setDuration(240).start()
            }
            return Holder(root, art, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            val c = holder.root.context
            val banner = try {
                c.packageManager.getApplicationBanner(app.packageName)
                    ?: app.launchIntent?.let { c.packageManager.getActivityBanner(it) }
            } catch (e: PackageManager.NameNotFoundException) { null }
            if (banner != null) {
                holder.art.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.art.setPadding(0, 0, 0, 0)
                holder.art.setImageDrawable(banner)
            } else {
                holder.art.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = dp(c, 22f)
                holder.art.setPadding(pad, pad, pad, pad)
                holder.art.setImageBitmap(TileRenderer.render(app.icon, dp(c, 56f)))
            }
            holder.label.text = app.label
            holder.root.contentDescription = "Open ${app.label}"
            holder.root.setOnClickListener { onOpen(app, holder.art) }
            holder.root.setOnLongClickListener { onMenu(app, holder.art); true }
        }

        override fun getItemCount() = apps.size
    }
}
