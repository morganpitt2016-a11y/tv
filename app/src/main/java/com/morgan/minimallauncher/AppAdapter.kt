package com.morgan.minimallauncher

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.morgan.minimallauncher.GlassUi.dp

/**
 * tvOS-style app grid: landscape glass tiles using each app's 16:9 TV
 * banner (or its icon on a glass tile), with the name underneath; the
 * focused tile grows and its name brightens.
 */
class AppAdapter(
    private val apps: List<AppEntry>,
    private val onLaunch: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    class Holder(val root: LinearLayout, val tile: FrameLayout, val image: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(0, dp(c, 10f), 0, dp(c, 10f))
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isClickable = true
        }
        val tile = FrameLayout(c).apply {
            background = GlassUi.panel(c, 14f, 0x22)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(c, 14f).toFloat())
                }
            }
            clipToOutline = true
            isDuplicateParentStateEnabled = true
            foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(c, 14f).toFloat(); setStroke(dp(c, 2f), 0xE6FFFFFF.toInt())
                })
            }
        }
        val image = ImageView(c)
        tile.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(tile, LinearLayout.LayoutParams(dp(c, 150f), dp(c, 84f)))
        val label = GlassUi.rowText(c, "", 12f, R.font.inter_medium, GlassUi.primaryText(GlassUi.WHITE, 0x99FFFFFF.toInt())).apply {
            gravity = Gravity.CENTER
        }
        root.addView(label, LinearLayout.LayoutParams(dp(c, 150f), -2).apply { topMargin = dp(c, 8f) })
        root.setOnFocusChangeListener { _, has ->
            tile.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f)
                .translationZ(if (has) dp(c, 12f).toFloat() else 0f).setDuration(150).start()
            label.animate().translationY(if (has) dp(c, 5f).toFloat() else 0f).setDuration(150).start()
        }
        return Holder(root, tile, image, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        val c = holder.root.context
        val banner = try {
            c.packageManager.getApplicationBanner(app.packageName)
                ?: app.launchIntent?.let { c.packageManager.getActivityBanner(it) }
        } catch (e: PackageManager.NameNotFoundException) { null }
        if (banner != null) {
            holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.image.setPadding(0, 0, 0, 0)
            holder.image.setImageDrawable(banner)
        } else {
            // No TV banner: the app's icon as a rounded square on the glass tile.
            holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(c, 18f)
            holder.image.setPadding(pad, pad, pad, pad)
            holder.image.setImageBitmap(TileRenderer.render(app.icon, dp(c, 48f)))
        }
        holder.label.text = app.label
        holder.root.contentDescription = "Open ${app.label}"
        holder.root.setOnClickListener { onLaunch(app) }
    }

    override fun getItemCount() = apps.size
}
