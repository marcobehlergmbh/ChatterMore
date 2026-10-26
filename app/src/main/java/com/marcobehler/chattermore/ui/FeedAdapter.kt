package com.marcobehler.chattermore.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
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
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.MentionContext
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.UserPresence
import com.marcobehler.chattermore.network.MattermostImageLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FeedAdapter(
    private val onRetry: (String) -> Unit,
    private val onUserClick: (userId: String, title: String) -> Unit,
    private val onImageClick: (FeedImage) -> Unit,
    private val onFileClick: (FeedFile) -> Unit,
    private val onYouTubeClick: (YouTubeVideo) -> Unit,
    private val onLinkPreviewClick: (LinkPreview) -> Unit,
    private val onMessageLongPress: (FeedItem) -> Boolean,
    private val imageLoader: MattermostImageLoader,
    private val sunoController: SunoPlaybackController,
) : RecyclerView.Adapter<FeedAdapter.MessageViewHolder>() {
    private val items = mutableListOf<FeedItem>()
    private val positions = mutableMapOf<String, Int>()
    private val avatarPositionsByUserId = mutableMapOf<String, MutableList<Int>>()
    private val presenceByUserId = mutableMapOf<String, UserPresence>()
    private val zoneId = ZoneId.systemDefault()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(zoneId)
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeLabels = LruCache<Long, String>(2_048)
    private val localDays = LruCache<Long, Long>(2_048)
    private val dateLabels = LruCache<Long, String>(512)
    private val formattedMessages = object : LruCache<String, RenderedMessage>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: RenderedMessage): Int =
            (key.length + value.text.length).coerceAtLeast(1)
    }
    private val youtubeMessages = object : LruCache<String, CachedYouTube>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: CachedYouTube): Int = key.length.coerceAtLeast(1)
    }
    private val sunoMessages = object : LruCache<String, CachedSuno>(LINK_CACHE_CHARACTERS) {
        override fun sizeOf(key: String, value: CachedSuno): Int = key.length.coerceAtLeast(1)
    }
    private var mentionContext = MentionContext()
    private var mentionStyle: MentionStyle? = null
    private val onMentionUserClick: (MattermostUser) -> Unit = { user ->
        onUserClick(user.id, user.displayName)
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
        val previous = items.getOrNull(position - 1)
        val next = items.getOrNull(position + 1)
        val currentDay = localDay(item.createAt)
        val previousDay = previous?.let { localDay(it.createAt) }
        holder.bind(
            item = item,
            isContinuation = MessageGrouping.isContinuation(
                previous,
                item,
                sameLocalDay = previousDay == currentDay,
            ),
            isGroupEnd = next == null || !MessageGrouping.isContinuation(
                item,
                next,
                sameLocalDay = currentDay == localDay(next.createAt),
            ),
            dividerDay = currentDay.takeIf { MessageGrouping.startsNewDay(previousDay, it) },
        )
    }

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PRESENCE }) {
            val item = items[position]
            holder.bindPresence(
                item = item,
                isContinuation = isContinuation(items.getOrNull(position - 1), item),
            )
        } else if (payloads.any { it == PAYLOAD_FLASH }) {
            holder.flash()
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
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

    fun insertRange(index: Int, newItems: List<FeedItem>) {
        if (newItems.isEmpty()) return
        val target = index.coerceIn(0, items.size)
        items.addAll(target, newItems)
        rebuildPositions(target)
        notifyItemRangeInserted(target, newItems.size)
        if (target > 0) notifyItemChanged(target - 1, PAYLOAD_GROUPING)
        if (target + newItems.size < items.size) {
            notifyItemChanged(target + newItems.size, PAYLOAD_GROUPING)
        }
    }

    fun replacePageWindow(newItems: List<FeedItem>) {
        val oldKeys = items.mapTo(mutableSetOf(), FeedItem::key)
        val oldPreviousKeys = items.mapIndexed { index, item ->
            item.key to items.getOrNull(index - 1)?.key
        }.toMap()
        val oldNextKeys = items.mapIndexed { index, item ->
            item.key to items.getOrNull(index + 1)?.key
        }.toMap()
        val newKeys = newItems.mapTo(mutableSetOf(), FeedItem::key)
        var removalEnd = items.lastIndex
        while (removalEnd >= 0) {
            if (items[removalEnd].key in newKeys) {
                removalEnd--
                continue
            }
            var removalStart = removalEnd
            while (removalStart > 0 && items[removalStart - 1].key !in newKeys) {
                removalStart--
            }
            val count = removalEnd - removalStart + 1
            items.subList(removalStart, removalEnd + 1).clear()
            notifyItemRangeRemoved(removalStart, count)
            removalEnd = removalStart - 1
        }

        var index = 0
        while (index < newItems.size) {
            if (index < items.size && items[index].key == newItems[index].key) {
                index++
                continue
            }
            val retainedKey = items.getOrNull(index)?.key
            var insertionEnd = index
            while (
                insertionEnd < newItems.size &&
                (retainedKey == null || newItems[insertionEnd].key != retainedKey)
            ) {
                insertionEnd++
            }
            val inserted = newItems.subList(index, insertionEnd)
            if (inserted.isEmpty()) {
                // Page merges preserve the ordering of retained stable keys. Fall back safely if
                // that invariant is ever broken by a future mutation type.
                replace(newItems)
                return
            }
            items.addAll(index, inserted)
            notifyItemRangeInserted(index, inserted.size)
            index = insertionEnd
        }

        newItems.forEachIndexed { itemIndex, newItem ->
            if (items[itemIndex] != newItem) {
                items[itemIndex] = newItem
                val changedStart = (itemIndex - 1).coerceAtLeast(0)
                notifyItemRangeChanged(
                    changedStart,
                    minOf(3, items.size - changedStart),
                    PAYLOAD_CONTENT,
                )
            }
        }
        rebuildPositions()
        newItems.forEachIndexed { itemIndex, item ->
            if (
                item.key in oldKeys &&
                (
                    oldPreviousKeys[item.key] != newItems.getOrNull(itemIndex - 1)?.key ||
                        oldNextKeys[item.key] != newItems.getOrNull(itemIndex + 1)?.key
                )
            ) {
                notifyItemChanged(itemIndex, PAYLOAD_GROUPING)
            }
        }
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
        rebuildUserPositions()
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

    fun updatePresence(presence: Map<String, UserPresence>) {
        val changedUserIds = (presenceByUserId.keys + presence.keys).filterTo(mutableSetOf()) { userId ->
            (presenceByUserId[userId] ?: UserPresence.OFFLINE) !=
                (presence[userId] ?: UserPresence.OFFLINE)
        }
        presenceByUserId.clear()
        presenceByUserId.putAll(presence)
        changedUserIds.asSequence()
            .flatMap { avatarPositionsByUserId[it].orEmpty().asSequence() }
            .distinct()
            .forEach { notifyItemChanged(it, PAYLOAD_PRESENCE) }
    }

    fun updateMentionContext(context: MentionContext) {
        if (mentionContext == context) return
        mentionContext = context
        formattedMessages.snapshot().keys
            .filter { '@' in it }
            .forEach(formattedMessages::remove)
        items.forEachIndexed { index, item ->
            if ('@' in item.message) notifyItemChanged(index, PAYLOAD_MENTIONS)
        }
    }

    fun itemAt(position: Int): FeedItem? = items.getOrNull(position)

    fun positionOf(key: String): Int = positions[key] ?: RecyclerView.NO_POSITION

    fun positionOfServerId(serverId: String): Int =
        items.indexOfFirst { it.serverId == serverId }

    fun flashServerId(serverId: String) {
        val position = positionOfServerId(serverId)
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_FLASH)
        }
    }

    private fun isContinuation(previous: FeedItem?, current: FeedItem): Boolean =
        MessageGrouping.isContinuation(
            previous = previous,
            current = current,
            sameLocalDay = previous != null &&
                localDay(previous.createAt) == localDay(current.createAt),
        )

    private fun localDay(timestamp: Long): Long =
        localDays.get(timestamp) ?: MessageGrouping.localDay(timestamp, zoneId)
            .also { localDays.put(timestamp, it) }

    private fun dayLabel(epochDay: Long, context: Context): String {
        val date = LocalDate.ofEpochDay(epochDay)
        val today = LocalDate.now(zoneId)
        return when (date) {
            today -> context.getString(R.string.today)
            today.minusDays(1) -> context.getString(R.string.yesterday)
            else -> dateLabels.get(epochDay) ?: dayFormatter.format(date)
                .also { dateLabels.put(epochDay, it) }
        }
    }

    private fun rebuildPositions(start: Int = 0) {
        if (start == 0) positions.clear()
        for (index in start until items.size) positions[items[index].key] = index
        rebuildUserPositions()
    }

    private fun rebuildUserPositions() {
        avatarPositionsByUserId.clear()
        items.forEachIndexed { index, item ->
            if (!isContinuation(items.getOrNull(index - 1), item)) {
                avatarPositionsByUserId.getOrPut(item.userId) { mutableListOf() }.add(index)
            }
        }
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val attentionBar = view.findViewById<View>(R.id.messageAttentionBar)
        private val dayDivider = view.findViewById<View>(R.id.messageDayDivider)
        private val dayDividerLabel = view.findViewById<TextView>(R.id.messageDayDividerLabel)
        private val row = view.findViewById<View>(R.id.messageRow)
        private val content = view.findViewById<View>(R.id.messageContent)
        private val header = view.findViewById<View>(R.id.messageHeader)
        private val avatar = view.findViewById<ImageView>(R.id.messageAvatar)
        private val presenceBadge = view.findViewById<PresenceBadgeView>(R.id.messagePresence)
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
        private val linkPreviewStub = view.findViewById<ViewStub>(R.id.messageLinkPreviewStub)
        private var linkPreview: View? = null
        private var linkPreviewSite: TextView? = null
        private var linkPreviewTitle: TextView? = null
        private var linkPreviewDescription: TextView? = null
        private var linkPreviewImage: ImageView? = null
        private val filesStub = view.findViewById<ViewStub>(R.id.messageFilesStub)
        private var files: MessageFileListView? = null
        private val state = view.findViewById<TextView>(R.id.messageState)
        private val bodyPaintFlags = body.paintFlags
        private val horizontalPadding = row.paddingLeft
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
        private var linkPreviewRequest: MattermostImageLoader.ImageRequest? = null
        private var boundLinkPreviewImageUrl: String? = null
        private var tapGlowAnimator: AnimatorSet? = null
        private var tapGlowDrawable: GradientDrawable? = null
        private var clickableUserId: String? = null
        private var clickableUserTitle: String? = null

        init {
            body.movementMethod = LinkMovementMethod.getInstance()
            body.linksClickable = true
            itemView.setOnLongClickListener { handleLongPress() }
            body.setOnLongClickListener { handleLongPress() }
            author.setOnClickListener { openBoundUser() }
            avatar.setOnClickListener { openBoundUser() }
        }

        fun bind(
            item: FeedItem,
            isContinuation: Boolean,
            isGroupEnd: Boolean,
            dividerDay: Long?,
        ) {
            resetTapGlow()
            clickableUserId = item.userId
            clickableUserTitle = item.author
            dayDivider.visibility = if (dividerDay == null) View.GONE else View.VISIBLE
            dayDividerLabel.text = dividerDay?.let { dayLabel(it, itemView.context) }
            if (boundAsContinuation != isContinuation || boundAsGroupEnd != isGroupEnd) {
                boundAsContinuation = isContinuation
                boundAsGroupEnd = isGroupEnd
                header.visibility = if (isContinuation) View.GONE else View.VISIBLE
                avatar.visibility = if (isContinuation) View.GONE else View.VISIBLE
                val topPadding = if (isContinuation) innerVerticalSpacing else groupVerticalSpacing
                val bottomPadding = if (isGroupEnd) groupVerticalSpacing else innerVerticalSpacing
                row.minimumHeight = if (isContinuation) 0 else groupMinHeight
                row.setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            }
            if (!isContinuation) {
                author.text = item.author
                author.contentDescription = itemView.context.getString(
                    R.string.open_direct_message,
                    item.author,
                )
                val minute = item.createAt / MessageGrouping.MINUTE_MS
                time.text = timeLabels.get(minute) ?: timeFormatter
                    .format(Instant.ofEpochMilli(minute * MessageGrouping.MINUTE_MS))
                    .also { timeLabels.put(minute, it) }
                bindAvatar(item.userId)
            } else {
                clearAvatar()
            }
            bindPresence(item, isContinuation)
            val renderedMessage = messageText(item, itemView.context)
            body.text = renderedMessage.text
            body.visibility = if (renderedMessage.text.isBlank()) View.GONE else View.VISIBLE
            bindAttention(
                if (item.userId == mentionContext.currentUserId) {
                    MessageEmphasis.NONE
                } else {
                    renderedMessage.emphasis
                },
            )
            bindSuno(sunoSong(item.message))
            bindYouTube(youtubeVideo(item.message))
            bindLinkPreview(LinkPreviewSelector.firstGeneric(item.linkPreviews))
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
                fileView.setFiles(imageLoader, item.files)
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
            clickableUserId = null
            clickableUserTitle = null
            clearAvatar()
            clearSuno()
            clearYouTube()
            clearLinkPreview()
            images?.clearImages()
            files?.clearFiles()
            presenceBadge.visibility = View.GONE
            bindAttention(MessageEmphasis.NONE)
        }

        private fun openBoundUser() {
            val userId = clickableUserId ?: return
            onUserClick(userId, clickableUserTitle.orEmpty())
        }

        private fun bindAttention(emphasis: MessageEmphasis) {
            val (barColor, surfaceColor, description) = when (emphasis) {
                MessageEmphasis.NONE -> Triple(0, 0, null)
                MessageEmphasis.DIRECT -> Triple(
                    R.color.mention_direct_accent,
                    R.color.mention_direct_surface,
                    R.string.mentions_you,
                )
                MessageEmphasis.BROADCAST -> Triple(
                    R.color.mention_broadcast_accent,
                    R.color.mention_broadcast_surface,
                    R.string.channel_wide_mention,
                )
                MessageEmphasis.HERE -> Triple(
                    R.color.mention_broadcast_accent,
                    R.color.mention_broadcast_surface,
                    R.string.online_members_mention,
                )
            }
            if (emphasis == MessageEmphasis.NONE) {
                attentionBar.visibility = View.INVISIBLE
                content.background = null
                ViewCompat.setStateDescription(itemView, null)
            } else {
                attentionBar.visibility = View.VISIBLE
                attentionBar.setBackgroundColor(ContextCompat.getColor(itemView.context, barColor))
                content.setBackgroundColor(ContextCompat.getColor(itemView.context, surfaceColor))
                ViewCompat.setStateDescription(itemView, itemView.context.getString(description!!))
            }
        }

        fun bindPresence(item: FeedItem, isContinuation: Boolean) {
            if (isContinuation) {
                presenceBadge.visibility = View.GONE
                return
            }
            val presence = presenceByUserId[item.userId] ?: UserPresence.OFFLINE
            presenceBadge.presence = presence
            presenceBadge.visibility = View.VISIBLE
            val label = itemView.context.getString(
                when (presence) {
                    UserPresence.ACTIVE -> R.string.presence_active
                    UserPresence.AWAY -> R.string.presence_away
                    UserPresence.OFFLINE -> R.string.presence_offline
                },
            )
            avatar.contentDescription = itemView.context.getString(
                R.string.user_avatar_with_presence,
                item.author,
                label,
            )
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

        private fun bindLinkPreview(preview: LinkPreview?) {
            if (preview == null) {
                clearLinkPreview()
                return
            }
            val previewView = linkPreview ?: linkPreviewStub.inflate().also {
                linkPreview = it
                linkPreviewSite = it.findViewById(R.id.linkPreviewSite)
                linkPreviewTitle = it.findViewById(R.id.linkPreviewTitle)
                linkPreviewDescription = it.findViewById(R.id.linkPreviewDescription)
                linkPreviewImage = it.findViewById(R.id.linkPreviewImage)
            }
            val site = preview.siteName.ifBlank {
                Uri.parse(preview.url).host?.removePrefix("www.").orEmpty()
            }.ifBlank { preview.url }
            val title = preview.title.ifBlank { site }
            previewView.visibility = View.VISIBLE
            previewView.contentDescription = itemView.context.getString(
                R.string.link_preview_description,
                title,
                site,
            )
            previewView.setOnClickListener { onLinkPreviewClick(preview) }
            previewView.setOnLongClickListener { handleLongPress() }
            linkPreviewSite?.text = site
            linkPreviewTitle?.text = title
            linkPreviewDescription?.apply {
                text = preview.description
                visibility = if (preview.description.isBlank()) View.GONE else View.VISIBLE
            }

            val imageUrl = preview.imageUrl
            if (imageUrl.isNullOrBlank()) {
                clearLinkPreviewImage()
                return
            }
            if (boundLinkPreviewImageUrl == imageUrl) return
            clearLinkPreviewImage()
            boundLinkPreviewImageUrl = imageUrl
            linkPreviewRequest = imageLoader.loadLinkPreviewImage(imageUrl) { bitmap ->
                if (boundLinkPreviewImageUrl == imageUrl && bitmap != null) {
                    linkPreviewImage?.apply {
                        setImageBitmap(bitmap)
                        visibility = View.VISIBLE
                    }
                }
            }
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

        private fun clearLinkPreview() {
            clearLinkPreviewImage()
            linkPreview?.apply {
                visibility = View.GONE
                setOnClickListener(null)
                setOnLongClickListener(null)
            }
        }

        private fun clearLinkPreviewImage() {
            linkPreviewRequest?.cancel()
            linkPreviewRequest = null
            boundLinkPreviewImageUrl = null
            linkPreviewImage?.apply {
                setImageDrawable(null)
                visibility = View.GONE
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
            val glowColor = ContextCompat.getColor(itemView.context, R.color.chattermore_blue)
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

        fun flash() {
            playTapGlow()
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
        const val PAYLOAD_PRESENCE = "presence"
        const val PAYLOAD_MENTIONS = "mentions"
        const val PAYLOAD_FLASH = "flash"
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

    private fun formattedMessage(message: String, context: Context): RenderedMessage {
        formattedMessages.get(message)?.let { return it }
        val style = mentionStyle ?: MentionStyle(
            userText = ContextCompat.getColor(context, R.color.mention_user_text),
            userBackground = ContextCompat.getColor(context, R.color.mention_user_background),
            directText = ContextCompat.getColor(context, R.color.mention_direct_text),
            directBackground = ContextCompat.getColor(context, R.color.mention_direct_background),
            broadcastText = ContextCompat.getColor(context, R.color.mention_broadcast_text),
            broadcastBackground = ContextCompat.getColor(context, R.color.mention_broadcast_background),
        ).also { mentionStyle = it }
        val formatted = MessageLinkParser.render(
            message,
            mentionContext,
            style,
            onUserClick = onMentionUserClick,
        )
        val quotes = MessageQuoteParser.parse(formatted.text.toString())
        val rendered = if (quotes == null) {
            formatted
        } else {
            val quotedText = SpannableStringBuilder(formatted.text)
            quotes.markerRanges.asReversed().forEach { marker ->
                quotedText.delete(marker.start, marker.end)
            }
            val quoteColor = ContextCompat.getColor(context, R.color.quote_accent)
            val stripeWidth = context.resources.getDimensionPixelSize(R.dimen.quote_stripe_width)
            val gapWidth = context.resources.getDimensionPixelSize(R.dimen.quote_gap_width)
            quotes.quoteRanges.forEach { range ->
                quotedText.setSpan(
                    QuoteSpan(quoteColor, stripeWidth, gapWidth),
                    range.start,
                    range.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            formatted.copy(text = quotedText)
        }
        return rendered.also {
            if (rendered.text !== message || rendered.emphasis != MessageEmphasis.NONE || '@' in message) {
                formattedMessages.put(message, rendered)
            }
        }
    }

    private fun messageText(item: FeedItem, context: Context): RenderedMessage {
        val message = formattedMessage(item.message, context)
        if (item.editAt <= 0L || item.message.isBlank()) return message
        val editedText = SpannableStringBuilder(message.text).apply {
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
        return message.copy(text = editedText)
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
