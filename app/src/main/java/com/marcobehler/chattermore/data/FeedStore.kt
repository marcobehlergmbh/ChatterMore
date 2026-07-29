package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.model.FeedMutation
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.model.MattermostPost
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.MattermostFile

class FeedStore(
    private val unsupportedMessage: String,
    private val maxItems: Int = 2_000,
) {
    private val items = mutableListOf<FeedItem>()
    private val users = mutableMapOf<String, MattermostUser>()

    init {
        require(maxItems > 0) { "maxItems must be positive" }
    }

    fun snapshot(): List<FeedItem> = items.toList()

    fun userIds(): Set<String> = items.mapTo(mutableSetOf(), FeedItem::userId)

    fun replace(
        posts: Collection<MattermostPost>,
        profiles: Collection<MattermostUser>,
    ): FeedMutation.Replace {
        users.putAll(profiles.associateBy(MattermostUser::id))
        items.clear()
        posts.asSequence()
            .filter(::isVisible)
            .distinctBy(MattermostPost::id)
            .map(::toFeedItem)
            .sortedWith(FEED_ORDER)
            .toList()
            .takeLast(maxItems)
            .toCollection(items)
        return FeedMutation.Replace(snapshot())
    }

    fun merge(
        posts: Collection<MattermostPost>,
        profiles: Collection<MattermostUser> = emptyList(),
    ): List<FeedMutation> = merge(posts, profiles, retainNewest = true)

    fun mergeOlder(
        posts: Collection<MattermostPost>,
        profiles: Collection<MattermostUser> = emptyList(),
    ): List<FeedMutation> {
        val mutations = merge(posts, profiles, retainNewest = false)
        val inserts = mutations.filterIsInstance<FeedMutation.Insert>()
        val isContiguousPrepend = inserts.isNotEmpty() &&
            inserts.size == mutations.size &&
            inserts.indices.all { index -> inserts[index].index == index }
        return if (isContiguousPrepend) {
            listOf(FeedMutation.Prepend(inserts.map(FeedMutation.Insert::item)))
        } else {
            mutations
        }
    }

    private fun merge(
        posts: Collection<MattermostPost>,
        profiles: Collection<MattermostUser>,
        retainNewest: Boolean,
    ): List<FeedMutation> {
        users.putAll(profiles.associateBy(MattermostUser::id))
        val mutations = mutableListOf<FeedMutation>()
        posts.sortedWith(POST_ORDER).forEach { post ->
            val serverIndex = items.indexOfFirst { it.serverId == post.id }
            val pendingIndex = if (post.pendingPostId.isNotBlank()) {
                items.indexOfFirst { it.pendingId == post.pendingPostId }
            } else {
                -1
            }
            val existingIndex = if (serverIndex >= 0) serverIndex else pendingIndex

            if (!isVisible(post)) {
                if (existingIndex >= 0) {
                    val removed = items.removeAt(existingIndex)
                    mutations += FeedMutation.Remove(removed.key)
                }
                return@forEach
            }

            val item = toFeedItem(post, existingIndex.takeIf { it >= 0 }?.let(items::get))
            if (existingIndex >= 0) {
                val previous = items[existingIndex]
                if (previous != item) {
                    items[existingIndex] = item
                    mutations += FeedMutation.Update(previous.key, item)
                }
            } else {
                val insertionIndex = items.binarySearch(item, FEED_ORDER).let { result ->
                    if (result >= 0) result else -result - 1
                }
                items.add(insertionIndex, item)
                mutations += FeedMutation.Insert(insertionIndex, item)
            }
        }

        if (items.size > maxItems) {
            trimToMax(retainNewest)
            return listOf(FeedMutation.Replace(snapshot()))
        }
        return mutations
    }

    fun addPending(
        pendingId: String,
        channelId: String,
        user: MattermostUser,
        message: String,
        createAt: Long,
        uploadedFiles: List<MattermostFile> = emptyList(),
    ): FeedMutation {
        users[user.id] = user
        val item = FeedItem(
            key = pendingKey(pendingId),
            serverId = null,
            pendingId = pendingId,
            channelId = channelId,
            userId = user.id,
            author = user.displayName,
            message = message,
            createAt = createAt,
            updateAt = createAt,
            deliveryState = DeliveryState.OPTIMISTIC,
            fileIds = uploadedFiles.map(MattermostFile::id),
            images = imagesFrom(uploadedFiles),
            files = filesFrom(uploadedFiles),
        )
        val index = items.binarySearch(item, FEED_ORDER).let { if (it >= 0) it else -it - 1 }
        items.add(index, item)
        if (items.size > maxItems) {
            trimToMax(retainNewest = true)
            return FeedMutation.Replace(snapshot())
        }
        return FeedMutation.Insert(index, item)
    }

    fun setPendingState(pendingId: String, state: DeliveryState): FeedMutation.Update? {
        val index = items.indexOfFirst { it.pendingId == pendingId }
        if (index < 0) return null
        val previous = items[index]
        if (previous.serverId != null || previous.deliveryState == state) return null
        val updated = previous.copy(deliveryState = state)
        items[index] = updated
        return FeedMutation.Update(previous.key, updated)
    }

    fun markUnconfirmed(pendingId: String): FeedMutation.Update? {
        val item = pending(pendingId) ?: return null
        if (item.deliveryState != DeliveryState.OPTIMISTIC) return null
        return setPendingState(pendingId, DeliveryState.UNCONFIRMED)
    }

    fun pending(pendingId: String): FeedItem? = items.firstOrNull { it.pendingId == pendingId }

    fun post(serverId: String): FeedItem? = items.firstOrNull { it.serverId == serverId }

    fun updateProfiles(profiles: Collection<MattermostUser>): List<FeedMutation.Update> {
        users.putAll(profiles.associateBy(MattermostUser::id))
        return items.mapIndexedNotNull { index, item ->
            val author = users[item.userId]?.displayName ?: return@mapIndexedNotNull null
            if (author == item.author) return@mapIndexedNotNull null
            val updated = item.copy(author = author)
            items[index] = updated
            FeedMutation.Update(item.key, updated)
        }
    }

    fun missingUserIds(posts: Collection<MattermostPost>): Set<String> = posts.asSequence()
        .filter(::isVisible)
        .map(MattermostPost::userId)
        .filter(String::isNotBlank)
        .filterNot(users::containsKey)
        .toSet()

    fun oldestServerId(): String? = items.firstNotNullOfOrNull(FeedItem::serverId)

    fun newestServerId(): String? = items.asReversed().firstNotNullOfOrNull(FeedItem::serverId)

    fun isCurrent(post: MattermostPost): Boolean =
        items.any { it.serverId == post.id && it.updateAt == post.updateAt }

    private fun trimToMax(retainNewest: Boolean) {
        while (items.size > maxItems) {
            val sentIndex = if (retainNewest) {
                items.indexOfFirst { it.deliveryState == DeliveryState.SENT }
            } else {
                items.indexOfLast { it.deliveryState == DeliveryState.SENT }
            }
            items.removeAt(
                sentIndex.takeIf { it >= 0 } ?: if (retainNewest) 0 else items.lastIndex,
            )
        }
    }

    private fun isVisible(post: MattermostPost): Boolean =
        post.id.isNotBlank() && post.rootId.isBlank() && post.deleteAt == 0L

    private fun toFeedItem(post: MattermostPost, previous: FeedItem? = null): FeedItem {
        val preserveExistingMetadata = post.files.isEmpty() && post.fileIds.isNotEmpty()
        val images = if (preserveExistingMetadata) {
            previous?.images?.filter { it.fileId in post.fileIds }.orEmpty()
        } else if (post.files.isEmpty()) {
            emptyList()
        } else imagesFrom(post.files)
        val files = if (preserveExistingMetadata) {
            previous?.files?.filter { it.fileId in post.fileIds }.orEmpty()
        } else if (post.files.isEmpty()) {
            emptyList()
        } else filesFrom(post.files)
        val linkPreviews = if (post.hasMetadata) {
            post.linkPreviews
        } else {
            previous?.linkPreviews?.filter { post.message.contains(it.url) }.orEmpty()
        }
        return FeedItem(
            key = previous?.key ?: post.pendingPostId.takeIf(String::isNotBlank)?.let(::pendingKey) ?: serverKey(post.id),
            serverId = post.id,
            pendingId = post.pendingPostId.ifBlank { previous?.pendingId.orEmpty() }.ifBlank { null },
            channelId = post.channelId,
            userId = post.userId,
            author = users[post.userId]?.displayName ?: previous?.author ?: post.userId,
            message = post.message.ifBlank {
                if (images.isEmpty() && files.isEmpty()) unsupportedMessage else ""
            },
            createAt = post.createAt,
            updateAt = post.updateAt,
            deliveryState = DeliveryState.SENT,
            fileIds = post.fileIds.ifEmpty { post.files.map(MattermostFile::id) },
            images = images,
            files = files,
            linkPreviews = linkPreviews,
            editAt = post.editAt,
        )
    }

    private fun serverKey(id: String) = "post:$id"
    private fun pendingKey(id: String) = "pending:$id"

    private fun imagesFrom(files: List<MattermostFile>): List<FeedImage> = files.mapNotNull {
        if (it.id.isBlank() || !it.mimeType.startsWith("image/", ignoreCase = true)) {
            null
        } else {
            FeedImage(
                fileId = it.id,
                name = it.name,
                width = it.width,
                height = it.height,
                hasPreviewImage = it.hasPreviewImage,
            )
        }
    }

    private fun filesFrom(files: List<MattermostFile>): List<FeedFile> = files.mapNotNull {
        if (it.id.isBlank() || it.mimeType.startsWith("image/", ignoreCase = true)) {
            null
        } else {
            FeedFile(
                fileId = it.id,
                name = it.name.ifBlank { it.id },
                mimeType = it.mimeType.ifBlank { "application/octet-stream" },
                sizeBytes = it.sizeBytes,
            )
        }
    }

    private companion object {
        val POST_ORDER = compareBy<MattermostPost>({ it.createAt }, { it.id })
        val FEED_ORDER = compareBy<FeedItem>({ it.createAt }, { it.serverId ?: it.pendingId.orEmpty() })
    }
}
