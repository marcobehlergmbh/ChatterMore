package com.example.mattermostlite.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.TypedValue
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.mattermostlite.R
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FeedAdapter(
    private val onRetry: (String) -> Unit,
) : RecyclerView.Adapter<FeedAdapter.MessageViewHolder>() {
    private val items = mutableListOf<FeedItem>()
    private val positions = mutableMapOf<String, Int>()
    private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
    private val timeLabels = LruCache<Long, String>(2_048)

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = stableHash(items[position].key)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun replace(newItems: List<FeedItem>) {
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        rebuildPositions()
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    fun prepend(newItems: List<FeedItem>) {
        if (newItems.isEmpty()) return
        items.addAll(0, newItems)
        rebuildPositions()
        notifyItemRangeInserted(0, newItems.size)
    }

    fun insert(index: Int, item: FeedItem) {
        val target = index.coerceIn(0, items.size)
        items.add(target, item)
        rebuildPositions(target)
        notifyItemInserted(target)
    }

    fun update(previousKey: String, item: FeedItem) {
        val index = positions[previousKey] ?: return
        items[index] = item
        positions.remove(previousKey)
        positions[item.key] = index
        notifyItemChanged(index, PAYLOAD_CONTENT)
    }

    fun remove(key: String) {
        val index = positions[key] ?: return
        items.removeAt(index)
        positions.remove(key)
        rebuildPositions(index)
        notifyItemRemoved(index)
    }

    fun itemAt(position: Int): FeedItem? = items.getOrNull(position)

    fun positionOf(key: String): Int = positions[key] ?: RecyclerView.NO_POSITION

    private fun rebuildPositions(start: Int = 0) {
        if (start == 0) positions.clear()
        for (index in start until items.size) positions[items[index].key] = index
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val author = view.findViewById<TextView>(R.id.messageAuthor)
        private val time = view.findViewById<TextView>(R.id.messageTime)
        private val body = view.findViewById<TextView>(R.id.messageBody)
        private val state = view.findViewById<TextView>(R.id.messageState)
        private val bodyPaintFlags = body.paintFlags

        fun bind(item: FeedItem) {
            author.text = item.author
            time.text = timeLabels.get(item.createAt) ?: timeFormatter
                .format(Instant.ofEpochMilli(item.createAt))
                .also { timeLabels.put(item.createAt, it) }
            body.text = item.message
            body.paintFlags = bodyPaintFlags
            state.visibility = View.GONE
            itemView.isClickable = false
            itemView.background = null
            itemView.setOnClickListener(null)
            when (item.deliveryState) {
                DeliveryState.SENT,
                DeliveryState.OPTIMISTIC -> Unit
                DeliveryState.UNCONFIRMED ->
                    body.paintFlags = bodyPaintFlags or Paint.UNDERLINE_TEXT_FLAG
                DeliveryState.FAILED -> {
                    state.visibility = View.VISIBLE
                    state.setText(R.string.send_failed)
                    state.setTextColor(ContextCompat.getColor(itemView.context, R.color.error))
                    itemView.isClickable = true
                    val selectable = TypedValue()
                    itemView.context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground,
                        selectable,
                        true,
                    )
                    itemView.setBackgroundResource(selectable.resourceId)
                    itemView.setOnClickListener { item.pendingId?.let(onRetry) }
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_CONTENT = "content"

        fun stableHash(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
