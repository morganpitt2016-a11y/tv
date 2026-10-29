package com.morgan.minimallauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
        val label: TextView = view.findViewById(R.id.tileLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dock_tile, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        when (val item = items[position]) {
            is DockItem.AppTile -> {
                holder.icon.setImageDrawable(item.entry.icon)
                holder.label.text = item.entry.label
                holder.itemView.alpha = 1f
                holder.itemView.setOnClickListener { onAppClick(item.entry) }
            }
            is DockItem.DeviceTile -> {
                holder.icon.setImageResource(if (item.device.isOn) R.drawable.ic_bulb_on else R.drawable.ic_bulb_off)
                holder.label.text = item.device.name
                holder.itemView.alpha = if (item.device.isOn) 1f else 0.6f
                holder.itemView.setOnClickListener { onDeviceToggle(item.device) }
            }
            DockItem.MoreAppsTile -> {
                holder.icon.setImageResource(R.drawable.ic_more_apps)
                holder.label.text = "All Apps"
                holder.itemView.alpha = 1f
                holder.itemView.setOnClickListener { onMoreAppsClick() }
            }
            DockItem.SettingsTile -> {
                holder.icon.setImageResource(R.drawable.ic_settings)
                holder.label.text = "Settings"
                holder.itemView.alpha = 1f
                holder.itemView.setOnClickListener { onSettingsClick() }
            }
        }
    }

    override fun getItemCount() = items.size
}
