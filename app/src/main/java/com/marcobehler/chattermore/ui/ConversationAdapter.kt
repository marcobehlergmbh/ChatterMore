package com.marcobehler.chattermore.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.ConversationKind
import com.marcobehler.chattermore.model.ConversationMutation
import com.marcobehler.chattermore.model.ConversationSummary
import com.marcobehler.chattermore.network.MattermostImageLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ConversationAdapter(
    private val imageLoader: MattermostImageLoader,
    private val onClick: (ConversationSummary) -> Unit,
) : RecyclerView.Adapter<ConversationAdapter.Holder>() {
    private val items = mutableListOf<ConversationSummary>()
    private val positions = mutableMapOf<String, Int>()
    private val zoneId = ZoneId.systemDefault()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d").withZone(zoneId)

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = stableHash(items[position].id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            summary = items[position],
            showSection = position == 0 || items[position - 1].kind != items[position].kind,
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
            holder.bindState(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    fun replace(next: List<ConversationSummary>) {
        val previous = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == next[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]
        })
        items.clear()
        items.addAll(next)
        rebuildPositions()
        diff.dispatchUpdatesTo(this)
    }

    fun apply(mutation: ConversationMutation) {
        when (mutation) {
            is ConversationMutation.Replace -> replace(mutation.conversations)
            is ConversationMutation.Update -> {
                val index = positions[mutation.conversation.id] ?: return
                val previous = items[index]
                items[index] = mutation.conversation
                if (
                    previous.title == mutation.conversation.title &&
                    previous.kind == mutation.conversation.kind &&
                    previous.peerUserId == mutation.conversation.peerUserId
                ) {
                    notifyItemChanged(index, PAYLOAD_STATE)
                } else {
                    notifyItemChanged(index)
                }
            }
        }
    }

    private fun rebuildPositions() {
        positions.clear()
        items.forEachIndexed { index, summary -> positions[summary.id] = index }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val section = view.findViewById<TextView>(R.id.conversationSection)
        private val avatar = view.findViewById<ImageView>(R.id.conversationAvatar)
        private val channelIcon = view.findViewById<TextView>(R.id.conversationChannelIcon)
        private val title = view.findViewById<TextView>(R.id.conversationTitle)
        private val time = view.findViewById<TextView>(R.id.conversationTime)
        private val unread = view.findViewById<View>(R.id.conversationUnread)
        private var avatarRequest: MattermostImageLoader.ImageRequest? = null
        private var boundId: String? = null

        fun bind(summary: ConversationSummary, showSection: Boolean) {
            section.isVisible = showSection
            section.setText(
                if (summary.kind == ConversationKind.CHANNEL) {
                    R.string.channels
                } else {
                    R.string.direct_messages
                },
            )
            title.text = summary.title
            itemView.setOnClickListener { onClick(summary) }
            bindIcon(summary)
            bindState(summary)
        }

        fun bindState(summary: ConversationSummary) {
            title.setTypeface(
                null,
                if (summary.unread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
            unread.isVisible = summary.unread
            time.text = lastActivity(summary.lastPostAt)
            time.isVisible = summary.lastPostAt > 0
            itemView.contentDescription = buildString {
                append(summary.title)
                if (summary.unread) append(", ").append(itemView.context.getString(R.string.unread))
            }
        }

        fun recycle() {
            avatarRequest?.cancel()
            avatarRequest = null
            boundId = null
            avatar.setImageResource(R.drawable.ic_person)
        }

        private fun bindIcon(summary: ConversationSummary) {
            avatarRequest?.cancel()
            avatarRequest = null
            boundId = summary.id
            val isDirect = summary.kind == ConversationKind.DIRECT_MESSAGE
            avatar.isVisible = isDirect
            channelIcon.isVisible = !isDirect
            if (!isDirect) return
            avatar.setImageResource(R.drawable.ic_person)
            val userId = summary.peerUserId ?: return
            avatarRequest = imageLoader.loadAvatar(userId) { bitmap ->
                if (boundId != summary.id) return@loadAvatar
                if (bitmap == null) {
                    avatar.setImageResource(R.drawable.ic_person)
                } else {
                    avatar.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun lastActivity(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val instant = Instant.ofEpochMilli(timestamp)
        return if (instant.atZone(zoneId).toLocalDate() == LocalDate.now(zoneId)) {
            timeFormatter.format(instant)
        } else {
            dateFormatter.format(instant)
        }
    }

    private companion object {
        const val PAYLOAD_STATE = "state"

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
