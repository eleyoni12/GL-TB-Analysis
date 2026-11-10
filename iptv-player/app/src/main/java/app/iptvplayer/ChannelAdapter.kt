package app.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val onClick: (position: Int) -> Unit,
    private val onLongClick: (position: Int) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.url == b.url && a.name == b.name
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val group: TextView = view.findViewById(R.id.group)
        val star: TextView = view.findViewById(R.id.star)

        init {
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(p)
            }
            view.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onLongClick(p)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = getItem(position)
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.star.visibility = if (isFavorite(ch)) View.VISIBLE else View.GONE
        if (ch.logo.isNullOrBlank()) {
            holder.logo.setImageResource(R.drawable.ic_tv)
        } else {
            holder.logo.load(ch.logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
    }
}

data class GroupItem(val label: String, val count: Int, val key: String)

class GroupAdapter(
    private val onClick: (GroupItem) -> Unit
) : ListAdapter<GroupItem, GroupAdapter.VH>(object : DiffUtil.ItemCallback<GroupItem>() {
    override fun areItemsTheSame(a: GroupItem, b: GroupItem) = a.key == b.key
    override fun areContentsTheSame(a: GroupItem, b: GroupItem) = a == b
}) {
    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val count: TextView = view.findViewById(R.id.group)
        val star: TextView = view.findViewById(R.id.star)

        init {
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(getItem(p))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = getItem(position)
        holder.name.text = g.label
        holder.count.text = holder.itemView.context.getString(R.string.items_count, g.count)
        holder.star.visibility = View.GONE
        holder.logo.setImageResource(R.drawable.ic_tv)
    }
}
