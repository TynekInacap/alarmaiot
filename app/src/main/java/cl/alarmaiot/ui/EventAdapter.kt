package cl.alarmaiot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.alarmaiot.data.EventRecord
import cl.alarmaiot.databinding.ItemEventBinding

class EventAdapter : RecyclerView.Adapter<EventAdapter.Holder>() {

    class Holder(val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<EventRecord> = emptyList()

    fun submit(list: List<EventRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.binding, items[position])

    companion object {
        fun bind(b: ItemEventBinding, e: EventRecord) {
            val ctx = b.root.context
            b.dot.tint(ctx.categoryColor(e.category))
            b.detail.text = e.detail
            b.meta.text = buildString {
                append(formatTime(e.ts))
                append(" · ").append(e.category.label)
                append(" · ").append(e.source)
                e.username?.let { append(" · ").append(it) }
            }
        }
    }
}
