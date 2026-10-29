package com.morgan.minimallauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class DockAdapter(
    private val items: List<DockItem>,
    private val onAppClick: (AppEntry) -> Unit,
    private val onDeviceToggle: (GoveeHelper.Device) -> Unit,
    private val onMoreAppsClick: () -> Unit,
    private val onSettingsClick: () -> Unit
) : RecyclerView.Adapter<DockAdapter.TileHolder>() {

    class TileHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dock_tile, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        val density = holder.itemView.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        when (val item = items[position]) {
            is DockItem.AppTile -> {
                // Real app icons/banners already look like the finished
                // rounded-square badges you see in the reference - no
                // extra backing card, no padding, just the icon.
                holder.icon.setImageDrawable(item.entry.icon)
                holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.icon.setPadding(0, 0, 0, 0)
                holder.itemView.background = null
                holder.itemView.clipToOutline = false
                holder.itemView.setOnClickListener { onAppClick(item.entry) }
            }
            is DockItem.DeviceTile -> {
                holder.icon.setImageResource(if (item.device.isOn) R.drawable.ic_bulb_on else R.drawable.ic_bulb_off)
                holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.icon.setPadding(dp(14), dp(14), dp(14), dp(14))
                holder.itemView.setBackgroundResource(
                    if (item.device.isOn) R.drawable.dock_tile_bg_device_on else R.drawable.dock_tile_bg_system
                )
                holder.itemView.clipToOutline = true
                holder.itemView.outlineProvider = ViewOutlineProvider.BACKGROUND
                holder.itemView.setOnClickListener { onDeviceToggle(item.device) }
            }
            DockItem.MoreAppsTile -> {
                holder.icon.setImageResource(R.drawable.ic_more_apps)
                holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.icon.setPadding(dp(14), dp(14), dp(14), dp(14))
                holder.itemView.setBackgroundResource(R.drawable.dock_tile_bg_system)
                holder.itemView.clipToOutline = true
                holder.itemView.outlineProvider = ViewOutlineProvider.BACKGROUND
                holder.itemView.setOnClickListener { onMoreAppsClick() }
            }
            DockItem.SettingsTile -> {
                holder.icon.setImageResource(R.drawable.ic_settings)
                holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.icon.setPadding(dp(14), dp(14), dp(14), dp(14))
                holder.itemView.setBackgroundResource(R.drawable.dock_tile_bg_system)
                holder.itemView.clipToOutline = true
                holder.itemView.outlineProvider = ViewOutlineProvider.BACKGROUND
                holder.itemView.setOnClickListener { onSettingsClick() }
            }
        }
    }

    override fun getItemCount() = items.size
}
