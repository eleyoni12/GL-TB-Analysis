package app.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lightweight horizontal "chips" (sections / categories). Much cheaper than Material Chip views. */
class ChipAdapter(private val onClick: (key: String) -> Unit) : RecyclerView.Adapter<ChipAdapter.VH>() {

    class Item(val key: String, val label: String)

    private var items: List<Item> = emptyList()
    private var selected = ""

    fun submit(list: List<Item>, selectedKey: String) {
        items = list
        selected = selectedKey
        notifyDataSetChanged()
    }

    fun select(key: String) {
        val old = items.indexOfFirst { it.key == selected }
        val new = items.indexOfFirst { it.key == key }
        selected = key
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        init {
            tv.background = AppTheme.chipSelector(tv.resources.displayMetrics.density)
            tv.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p].key)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val sel = it.key == selected
        holder.tv.text = it.label
        holder.tv.isSelected = sel
        holder.tv.setTextColor(if (sel) AppTheme.onAccent else AppTheme.text)
    }
}
