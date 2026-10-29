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
        // Rounded-square tile look: clip to whatever background drawable we
        // set below (its <corners radius> becomes the clip outline).
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        when (val item = items[position]) {
            is DockItem.AppTile -> {
                holder.icon.setImageDrawable(item.entry.icon)
                holder.itemView.setBackgroundResource(R.drawable.dock_tile_bg_app)
                holder.itemView.setOnClickListener { onAppClick(item.entry) }
            }
            is DockItem.DeviceTile -> {
                holder.icon.setImageResource(if (item.device.isOn) R.drawable.ic_bulb_on else R.drawable.ic_bulb_off)
                holder.itemView.setBackgroundResource(
                    if (item.device.isOn) R.drawable.dock_tile_bg_device_on else R.drawable.dock_tile_bg_system
                )
                holder.itemView.setOnClickListener { onDeviceToggle(item.device) }
            }
            DockItem.MoreAppsTile -> {
                holder.icon.setImageResource(R.drawable.ic_more_apps)
                holder.itemView.setBackgroundResource(R.drawable.dock_tile_bg_system)
                holder.itemView.setOnClickListener { onMoreAppsClick() }
            }
            DockItem.SettingsTile -> {
                holder.icon.setImageResource(R.drawable.ic_settings)
                holder.itemView.setBackgroundResource(R.drawable.dock_tile_bg_system)
                holder.itemView.setOnClickListener { onSettingsClick() }
            }
        }
    }

    override fun getItemCount() = items.size
}
