package app.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Shows channels as rows, or as poster cards when [poster] is true. */
class ChannelAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val progressOf: (Channel) -> Int,
    private val onClick: (position: Int) -> Unit,
    private val onLongClick: (position: Int) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(Diff) {

    var poster = false

    object Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.url == b.url && a.name == b.name
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val group: TextView = view.findViewById(R.id.group)
        val star: TextView = view.findViewById(R.id.star)
        val progress: ProgressBar = view.findViewById(R.id.watch_progress)

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

    override fun getItemViewType(position: Int) = if (poster) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_poster else R.layout.item_channel
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = getItem(position)
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.star.visibility = if (isFavorite(ch)) View.VISIBLE else View.GONE

        val pr = progressOf(ch)
        if (pr in 1..99) {
            holder.progress.progress = pr
            holder.progress.visibility = View.VISIBLE
        } else {
            holder.progress.visibility = View.GONE
        }

        val url = ch.logo?.trim()?.let { if (it.startsWith("//")) "http:$it" else it }
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            holder.logo.setImageResource(R.drawable.ic_tv)
        } else {
            holder.logo.load(url) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
                crossfade(true)
                addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            }
        }
    }
}
