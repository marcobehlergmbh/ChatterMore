package com.example.mattermostlite.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.TextView
import android.widget.ImageView
import android.util.TypedValue
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.mattermostlite.R
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedImage
import com.example.mattermostlite.model.FeedFile
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.network.MattermostImageLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FeedAdapter(
    private val onRetry: (String) -> Unit,
    private val onImageClick: (FeedImage) -> Unit,
    private val onFileClick: (FeedFile) -> Unit,
    private val onYouTubeClick: (YouTubeVideo) -> Unit,
    private val onMessageLongPress: (FeedItem) -> Boolean,
    private val imageLoader: MattermostImageLoader,
    private val sunoController: SunoPlaybackController,
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
    private val youtubeMessages = object : LruCache<String, CachedYouTube>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: CachedYouTube): Int = key.length.coerceAtLeast(1)
    }
    private val sunoMessages = object : LruCache<String, CachedSuno>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: CachedSuno): Int = key.length.coerceAtLeast(1)
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
        val item = items[position]
        val next = items.getOrNull(position + 1)
        holder.bind(
            item = item,
            isContinuation = MessageGrouping.isContinuation(items.getOrNull(position - 1), item),
            isGroupEnd = next == null || !MessageGrouping.isContinuation(item, next),
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
        if (target > 0) notifyItemChanged(target - 1, PAYLOAD_GROUPING)
        if (target + 1 < items.size) notifyItemChanged(target + 1, PAYLOAD_GROUPING)
    }

    fun update(previousKey: String, item: FeedItem) {
        val index = positions[previousKey] ?: return
        items[index] = item
        positions.remove(previousKey)
        positions[item.key] = index
        val changedStart = (index - 1).coerceAtLeast(0)
        notifyItemRangeChanged(
            changedStart,
            minOf(3, items.size - changedStart),
            PAYLOAD_CONTENT,
        )
    }

    fun remove(key: String) {
        val index = positions[key] ?: return
        items.removeAt(index)
        positions.remove(key)
        rebuildPositions(index)
        notifyItemRemoved(index)
        if (index > 0) notifyItemChanged(index - 1, PAYLOAD_GROUPING)
        if (index < items.size) notifyItemChanged(index, PAYLOAD_GROUPING)
    }

    fun itemAt(position: Int): FeedItem? = items.getOrNull(position)

    fun positionOf(key: String): Int = positions[key] ?: RecyclerView.NO_POSITION

    fun positionOfServerId(serverId: String): Int =
        items.indexOfFirst { it.serverId == serverId }

    private fun rebuildPositions(start: Int = 0) {
        if (start == 0) positions.clear()
        for (index in start until items.size) positions[items[index].key] = index
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val header = view.findViewById<View>(R.id.messageHeader)
        private val avatar = view.findViewById<ImageView>(R.id.messageAvatar)
        private val author = view.findViewById<TextView>(R.id.messageAuthor)
        private val time = view.findViewById<TextView>(R.id.messageTime)
        private val body = view.findViewById<TextView>(R.id.messageBody)
        private val imageStub = view.findViewById<ViewStub>(R.id.messageImagesStub)
        private var images: MessageImageGridView? = null
        private val youtubeStub = view.findViewById<ViewStub>(R.id.messageYouTubeStub)
        private var youtubePreview: View? = null
        private var youtubeThumbnail: ImageView? = null
        private val sunoStub = view.findViewById<ViewStub>(R.id.messageSunoStub)
        private var sunoPlayer: SunoPlayerView? = null
        private val filesStub = view.findViewById<ViewStub>(R.id.messageFilesStub)
        private var files: MessageFileListView? = null
        private val state = view.findViewById<TextView>(R.id.messageState)
        private val bodyPaintFlags = body.paintFlags
        private val horizontalPadding = view.paddingLeft
        private val groupVerticalSpacing = view.resources.getDimensionPixelSize(
            R.dimen.message_group_vertical_spacing,
        )
        private val innerVerticalSpacing = view.resources.getDimensionPixelSize(
            R.dimen.message_inner_vertical_spacing,
        )
        private val groupMinHeight = view.resources.getDimensionPixelSize(
            R.dimen.message_group_min_height,
        )
        private val avatarPlaceholderPadding = view.resources.getDimensionPixelSize(
            R.dimen.message_avatar_placeholder_padding,
        )
        private var boundAsContinuation: Boolean? = null
        private var boundAsGroupEnd: Boolean? = null
        private var avatarRequest: MattermostImageLoader.ImageRequest? = null
        private var boundAvatarUserId: String? = null
        private var youtubeRequest: MattermostImageLoader.ImageRequest? = null
        private var boundYouTubeId: String? = null
        private var tapGlowAnimator: AnimatorSet? = null
        private var tapGlowDrawable: GradientDrawable? = null

        init {
            body.movementMethod = LinkMovementMethod.getInstance()
            body.linksClickable = true
            itemView.setOnLongClickListener { handleLongPress() }
            body.setOnLongClickListener { handleLongPress() }
        }

        fun bind(item: FeedItem, isContinuation: Boolean, isGroupEnd: Boolean) {
            resetTapGlow()
            if (boundAsContinuation != isContinuation || boundAsGroupEnd != isGroupEnd) {
                boundAsContinuation = isContinuation
                boundAsGroupEnd = isGroupEnd
                header.visibility = if (isContinuation) View.GONE else View.VISIBLE
                avatar.visibility = if (isContinuation) View.GONE else View.VISIBLE
                val topPadding = if (isContinuation) innerVerticalSpacing else groupVerticalSpacing
                val bottomPadding = if (isGroupEnd) groupVerticalSpacing else innerVerticalSpacing
                itemView.minimumHeight = if (isContinuation) 0 else groupMinHeight
                itemView.setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            }
            if (!isContinuation) {
                author.text = item.author
                val minute = item.createAt / MessageGrouping.MINUTE_MS
                time.text = timeLabels.get(minute) ?: timeFormatter
                    .format(Instant.ofEpochMilli(minute * MessageGrouping.MINUTE_MS))
                    .also { timeLabels.put(minute, it) }
                bindAvatar(item.userId)
            } else {
                clearAvatar()
            }
            body.text = messageText(item, itemView.context)
            body.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE
            bindSuno(sunoSong(item.message))
            bindYouTube(youtubeVideo(item.message))
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
            if (item.files.isEmpty()) {
                files?.apply {
                    visibility = View.GONE
                    clearFiles()
                }
            } else {
                val fileView = files ?: (filesStub.inflate() as MessageFileListView).also {
                    files = it
                    it.onFileClick = onFileClick
                }
                fileView.visibility = View.VISIBLE
                fileView.setFiles(item.files)
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
            resetTapGlow()
            clearAvatar()
            clearSuno()
            clearYouTube()
            images?.clearImages()
            files?.clearFiles()
        }

        private fun bindAvatar(userId: String) {
            if (boundAvatarUserId == userId) return
            clearAvatar()
            boundAvatarUserId = userId
            avatar.setImageResource(R.drawable.ic_person)
            avatarRequest = imageLoader.loadAvatar(userId) { bitmap ->
                if (boundAvatarUserId == userId && bitmap != null) {
                    avatar.setPadding(0, 0, 0, 0)
                    avatar.setImageBitmap(bitmap)
                }
            }
        }

        private fun clearAvatar() {
            avatarRequest?.cancel()
            avatarRequest = null
            boundAvatarUserId = null
            avatar.setPadding(avatarPlaceholderPadding, avatarPlaceholderPadding, avatarPlaceholderPadding, avatarPlaceholderPadding)
            avatar.setImageResource(R.drawable.ic_person)
        }

        private fun bindYouTube(video: YouTubeVideo?) {
            if (video == null) {
                clearYouTube()
                return
            }
            val preview = youtubePreview ?: youtubeStub.inflate().also {
                youtubePreview = it
                youtubeThumbnail = it.findViewById(R.id.youtubeThumbnail)
            }
            preview.visibility = View.VISIBLE
            preview.contentDescription = itemView.context.getString(R.string.youtube_video_preview)
            preview.setOnClickListener { onYouTubeClick(video) }
            if (boundYouTubeId == video.id) return
            youtubeRequest?.cancel()
            boundYouTubeId = video.id
            youtubeThumbnail?.setImageDrawable(null)
            youtubeRequest = imageLoader.loadYouTubeThumbnail(video.id) { bitmap ->
                if (boundYouTubeId == video.id && bitmap != null) {
                    youtubeThumbnail?.setImageBitmap(bitmap)
                }
            }
        }

        private fun bindSuno(song: SunoSong?) {
            if (song == null) {
                clearSuno()
                return
            }
            val playerView = sunoPlayer ?: (sunoStub.inflate() as SunoPlayerView).also {
                sunoPlayer = it
            }
            playerView.visibility = View.VISIBLE
            playerView.bind(sunoController, song)
        }

        private fun clearSuno() {
            sunoPlayer?.apply {
                clear()
                visibility = View.GONE
            }
        }

        private fun clearYouTube() {
            youtubeRequest?.cancel()
            youtubeRequest = null
            boundYouTubeId = null
            youtubeThumbnail?.setImageDrawable(null)
            youtubePreview?.apply {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }

        private fun handleLongPress(): Boolean {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return false
            val handled = onMessageLongPress(items[position])
            if (handled) {
                itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                playTapGlow()
            }
            return handled
        }

        private fun playTapGlow() {
            resetTapGlow()
            val glowColor = ContextCompat.getColor(itemView.context, R.color.mattermost_blue)
            val glow = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, glowColor, Color.TRANSPARENT),
            ).apply {
                cornerRadius = TAP_GLOW_CORNER_DP * itemView.resources.displayMetrics.density
                alpha = 0
            }
            tapGlowDrawable = glow
            itemView.background = glow

            val fadeIn = ObjectAnimator.ofInt(glow, "alpha", 0, TAP_GLOW_PEAK_ALPHA).apply {
                duration = TAP_GLOW_IN_MS
            }
            val fadeOut = ObjectAnimator.ofInt(glow, "alpha", TAP_GLOW_PEAK_ALPHA, 0).apply {
                duration = TAP_GLOW_OUT_MS
                startDelay = TAP_GLOW_HOLD_MS
            }
            tapGlowAnimator = AnimatorSet().apply {
                playSequentially(fadeIn, fadeOut)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (itemView.background === glow) itemView.background = null
                        if (tapGlowDrawable === glow) {
                            tapGlowDrawable = null
                            tapGlowAnimator = null
                        }
                    }
                })
                start()
            }
        }

        private fun resetTapGlow() {
            val glow = tapGlowDrawable
            tapGlowAnimator?.removeAllListeners()
            tapGlowAnimator?.cancel()
            tapGlowAnimator = null
            tapGlowDrawable = null
            if (itemView.background === glow) itemView.background = null
        }

    }

    private companion object {
        const val LINK_CACHE_CHARACTERS = 256 * 1_024
        const val PAYLOAD_CONTENT = "content"
        const val PAYLOAD_GROUPING = "grouping"
        const val TAP_GLOW_CORNER_DP = 12f
        const val TAP_GLOW_PEAK_ALPHA = 105
        const val TAP_GLOW_IN_MS = 120L
        const val TAP_GLOW_HOLD_MS = 70L
        const val TAP_GLOW_OUT_MS = 360L

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

    private fun messageText(item: FeedItem, context: Context): CharSequence {
        val message = linkedMessage(item.message)
        if (item.editAt <= 0L || item.message.isBlank()) return message
        return SpannableStringBuilder(message).apply {
            append(' ')
            val markerStart = length
            append(context.getString(R.string.edited_hint))
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.message_metadata)),
                markerStart,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                RelativeSizeSpan(0.78f),
                markerStart,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.ITALIC),
                markerStart,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun youtubeVideo(message: String): YouTubeVideo? {
        youtubeMessages.get(message)?.let { return it.video }
        return YouTubeLinkParser.firstVideo(message).also {
            youtubeMessages.put(message, CachedYouTube(it))
        }
    }

    private fun sunoSong(message: String): SunoSong? {
        sunoMessages.get(message)?.let { return it.song }
        return SunoLinkParser.firstSong(message).also {
            sunoMessages.put(message, CachedSuno(it))
        }
    }

    private data class CachedYouTube(val video: YouTubeVideo?)
    private data class CachedSuno(val song: SunoSong?)
}
