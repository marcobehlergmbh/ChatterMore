package com.marcobehler.chattermore.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.MessageSearchResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SearchResultAdapter(
    private val onResultClick: (MessageSearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultAdapter.ResultViewHolder>() {
    private val results = mutableListOf<MessageSearchResult>()
    private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = results.size

    override fun getItemId(position: Int): Long = stableHash(results[position].postId)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder =
        ResultViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_search_result,
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(results[position])
    }

    fun replace(newResults: List<MessageSearchResult>) {
        if (results == newResults) return
        val previousSize = results.size
        val isAppend = newResults.size > previousSize &&
            newResults.subList(0, previousSize) == results
        if (isAppend) {
            results.addAll(newResults.subList(previousSize, newResults.size))
            notifyItemRangeInserted(previousSize, newResults.size - previousSize)
        } else {
            results.clear()
            if (previousSize > 0) notifyItemRangeRemoved(0, previousSize)
            results.addAll(newResults)
            if (newResults.isNotEmpty()) notifyItemRangeInserted(0, newResults.size)
        }
    }

    inner class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val author = view.findViewById<TextView>(R.id.searchResultAuthor)
        private val time = view.findViewById<TextView>(R.id.searchResultTime)
        private val body = view.findViewById<TextView>(R.id.searchResultBody)
        private val reply = view.findViewById<TextView>(R.id.searchResultReply)
        private val highlightText = ContextCompat.getColor(view.context, R.color.text_primary)
        private val highlightBackground =
            ContextCompat.getColor(view.context, R.color.search_match_background)
        private var boundResult: MessageSearchResult? = null

        init {
            itemView.setOnClickListener { boundResult?.let(onResultClick) }
        }

        fun bind(result: MessageSearchResult) {
            boundResult = result
            author.text = result.author
            time.text = timeFormatter.format(Instant.ofEpochMilli(result.createAt))
            val visibleMessage = result.message.ifBlank {
                if (result.fileCount > 0) {
                    itemView.resources.getQuantityString(
                        R.plurals.search_attachment_count,
                        result.fileCount,
                        result.fileCount,
                    )
                } else {
                    itemView.context.getString(R.string.unsupported_message)
                }
            }
            body.text = SpannableString(visibleMessage).apply {
                MessageSearchHighlighter.ranges(visibleMessage, result.matchTerms).forEach { range ->
                    setSpan(
                        BackgroundColorSpan(highlightBackground),
                        range.start,
                        range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        ForegroundColorSpan(highlightText),
                        range.start,
                        range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        range.start,
                        range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            reply.isVisible = result.rootId.isNotBlank()
        }
    }

    private companion object {
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
