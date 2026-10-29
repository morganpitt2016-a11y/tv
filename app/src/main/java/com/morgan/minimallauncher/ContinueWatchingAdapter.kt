package com.morgan.minimallauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContinueWatchingAdapter(
    private val entries: List<AppEntry>,
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.Holder>() {

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.cwIcon)
        val title: TextView = view.findViewById(R.id.cwTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_continue_watching, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.icon.setImageDrawable(entry.icon)
        holder.title.text = entry.label
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount() = entries.size
}
