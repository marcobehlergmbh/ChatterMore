package com.example.mattermostlite.ui

import android.graphics.Paint
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.TextView
import android.util.TypedValue
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.mattermostlite.R
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedImage
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.network.MattermostImageLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class FeedAdapter(
    private val onRetry: (String) -> Unit,
    private val onImageClick: (FeedImage) -> Unit,
    private val imageLoader: MattermostImageLoader,
) : RecyclerView.Adapter<FeedAdapter.MessageViewHolder>() {
    private val items = mutableListOf<FeedItem>()
    private val positions = mutableMapOf<String, Int>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
    private val timeLabels = LruCache<Long, String>(2_048)
    private val linkedMessages = object : LruCache<String, CharSequence>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: CharSequence): Int =
            (key.length + value.length).coerceAtLeast(1)
    }

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
        holder.bind(
            item = items[position],
            isContinuation = MessageGrouping.isContinuation(items.getOrNull(position - 1), items[position]),
        )
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
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
        if (newItems.size < items.size) notifyItemChanged(newItems.size, PAYLOAD_GROUPING)
    }

    fun insert(index: Int, item: FeedItem) {
        val target = index.coerceIn(0, items.size)
        items.add(target, item)
        rebuildPositions(target)
        notifyItemInserted(target)
        if (target + 1 < items.size) notifyItemChanged(target + 1, PAYLOAD_GROUPING)
    }

    fun update(previousKey: String, item: FeedItem) {
        val index = positions[previousKey] ?: return
        items[index] = item
        positions.remove(previousKey)
        positions[item.key] = index
        notifyItemRangeChanged(index, minOf(2, items.size - index), PAYLOAD_CONTENT)
    }

    fun remove(key: String) {
        val index = positions[key] ?: return
        items.removeAt(index)
        positions.remove(key)
        rebuildPositions(index)
        notifyItemRemoved(index)
        if (index < items.size) notifyItemChanged(index, PAYLOAD_GROUPING)
    }

    fun itemAt(position: Int): FeedItem? = items.getOrNull(position)

    fun positionOf(key: String): Int = positions[key] ?: RecyclerView.NO_POSITION

    private fun rebuildPositions(start: Int = 0) {
        if (start == 0) positions.clear()
        for (index in start until items.size) positions[items[index].key] = index
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val header = view.findViewById<View>(R.id.messageHeader)
        private val author = view.findViewById<TextView>(R.id.messageAuthor)
        private val time = view.findViewById<TextView>(R.id.messageTime)
        private val body = view.findViewById<TextView>(R.id.messageBody)
        private val imageStub = view.findViewById<ViewStub>(R.id.messageImagesStub)
        private var images: MessageImageGridView? = null
        private val state = view.findViewById<TextView>(R.id.messageState)
        private val bodyPaintFlags = body.paintFlags
        private val horizontalPadding = view.paddingLeft
        private val regularVerticalPadding = (7f * view.resources.displayMetrics.density).roundToInt()
        private val groupedVerticalPadding = (2f * view.resources.displayMetrics.density).roundToInt()
        private val regularMinHeight = (56f * view.resources.displayMetrics.density).roundToInt()
        private var boundAsContinuation: Boolean? = null

        init {
            body.movementMethod = LinkMovementMethod.getInstance()
            body.linksClickable = true
        }

        fun bind(item: FeedItem, isContinuation: Boolean) {
            if (boundAsContinuation != isContinuation) {
                boundAsContinuation = isContinuation
                header.visibility = if (isContinuation) View.GONE else View.VISIBLE
                val verticalPadding = if (isContinuation) groupedVerticalPadding else regularVerticalPadding
                itemView.minimumHeight = if (isContinuation) 0 else regularMinHeight
                itemView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }
            if (!isContinuation) {
                author.text = item.author
                val minute = item.createAt / MessageGrouping.MINUTE_MS
                time.text = timeLabels.get(minute) ?: timeFormatter
                    .format(Instant.ofEpochMilli(minute * MessageGrouping.MINUTE_MS))
                    .also { timeLabels.put(minute, it) }
            }
            body.text = linkedMessage(item.message)
            body.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
            if (item.images.isEmpty()) {
                images?.apply {
                    visibility = View.GONE
                    clearImages()
                }
            } else {
                val imageView = images ?: (imageStub.inflate() as MessageImageGridView).also {
                    images = it
                    it.onImageClick = onImageClick
                }
                imageView.visibility = View.VISIBLE
                imageView.setImages(imageLoader, item.images)
            }
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

        fun recycle() {
            images?.clearImages()
        }
    }

    private companion object {
        const val LINK_CACHE_CHARACTERS = 256 * 1_024
        const val PAYLOAD_CONTENT = "content"
        const val PAYLOAD_GROUPING = "grouping"

        fun stableHash(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }

    private fun linkedMessage(message: String): CharSequence {
        linkedMessages.get(message)?.let { return it }
        val parsed = MessageLinkParser.parse(message)
        if (parsed !== message) {
            linkedMessages.put(message, parsed)
        }
        return parsed
    }
}
