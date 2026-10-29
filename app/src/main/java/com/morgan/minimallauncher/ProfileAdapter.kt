package com.morgan.minimallauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProfileAdapter(
    private val profiles: List<String>,
    private val activeProfile: String,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile_pill, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val name = profiles[position]
        holder.text.text = name
        holder.text.alpha = if (name == activeProfile) 1f else 0.55f
        holder.text.setOnClickListener { onSelect(name) }
    }

    override fun getItemCount() = profiles.size
}
