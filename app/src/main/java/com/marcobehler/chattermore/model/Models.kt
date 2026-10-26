package com.marcobehler.chattermore.model

data class ServerConfig(
    val baseUrl: String,
    val teamName: String,
    val channelName: String,
    val username: String,
    val password: String,
)

data class RememberedServer(
    val baseUrl: String,
    val teamName: String,
    val username: String,
) {
    fun asLoginConfig(): ServerConfig = ServerConfig(
        baseUrl = baseUrl,
        teamName = teamName,
        channelName = "",
        username = username,
        password = "",
    )
}

data class RememberedConversation(
    val baseUrl: String,
    val teamId: String,
    val userId: String,
    val channelId: String,
) {
    fun channelIdFor(
        baseUrl: String,
        teamId: String,
        userId: String,
    ): String? = channelId.takeIf {
        this.baseUrl == baseUrl &&
            this.teamId == teamId &&
            this.userId == userId
    }
}

data class AuthenticatedServerConfig(
    val baseUrl: String,
    val teamName: String,
    val channelName: String,
    val token: String,
)

data class MattermostUser(
    val id: String,
    val username: String,
    val firstName: String = "",
    val lastName: String = "",
) {
    val displayName: String
        get() = listOf(firstName, lastName)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { username.ifBlank { id } }
}

data class MentionContext(
    val currentUserId: String = "",
    val currentUsername: String = "",
    val usersByUsername: Map<String, MattermostUser> = emptyMap(),
)

data class MattermostUserStatus(
    val userId: String,
    val status: String,
)

enum class UserPresence {
    ACTIVE,
    AWAY,
    OFFLINE;

    companion object {
        fun fromMattermost(status: String): UserPresence = when (status.lowercase()) {
            "online" -> ACTIVE
            "away", "dnd", "ooo" -> AWAY
            else -> OFFLINE
        }
    }
}

data class MattermostChannel(
    val id: String,
    val name: String,
    val displayName: String,
    val teamId: String = "",
    val type: String = "",
    val lastPostAt: Long = 0,
    val totalMessageCount: Long = 0,
    val deleteAt: Long = 0,
)

sealed interface Conversation {
    val channel: MattermostChannel
    val channelId: String
        get() = channel.id
    val title: String
    val canNavigateBack: Boolean

    data class Channel(
        override val channel: MattermostChannel,
    ) : Conversation {
        override val title: String
            get() = channel.displayName.ifBlank { channel.name }
        override val canNavigateBack: Boolean = true
    }

    data class DirectMessage(
        override val channel: MattermostChannel,
        val peer: MattermostUser,
    ) : Conversation {
        override val title: String
            get() = peer.displayName
        override val canNavigateBack: Boolean = true
    }
}

data class MattermostTeam(
    val id: String,
    val name: String,
    val displayName: String,
)

data class MattermostChannelMember(
    val channelId: String,
    val userId: String,
    val messageCount: Long,
    val mentionCount: Long,
    val lastViewedAt: Long,
)

enum class ConversationKind {
    CHANNEL,
    DIRECT_MESSAGE,
}

data class ConversationSummary(
    val channel: MattermostChannel,
    val kind: ConversationKind,
    val title: String,
    val peerUserId: String? = null,
    val peerUsername: String? = null,
    val lastPostAt: Long = channel.lastPostAt,
    val unread: Boolean = false,
    val mentionCount: Long = 0,
) {
    val id: String
        get() = channel.id
}

data class InitializedDirectory(
    val team: MattermostTeam,
    val user: MattermostUser,
    val config: AuthenticatedServerConfig,
    val conversations: List<ConversationSummary>,
)

sealed interface ConversationMutation {
    data class Replace(val conversations: List<ConversationSummary>) : ConversationMutation
    data class Update(val conversation: ConversationSummary) : ConversationMutation
}

data class MattermostPost(
    val id: String,
    val channelId: String,
    val userId: String,
    val message: String,
    val createAt: Long,
    val updateAt: Long,
    val deleteAt: Long,
    val rootId: String,
    val type: String,
    val pendingPostId: String,
    val fileIds: List<String> = emptyList(),
    val files: List<MattermostFile> = emptyList(),
    val linkPreviews: List<LinkPreview> = emptyList(),
    val hasMetadata: Boolean = false,
    val editAt: Long = 0,
)

data class LinkPreview(
    val url: String,
    val title: String = "",
    val description: String = "",
    val siteName: String = "",
    val imageUrl: String? = null,
)

data class MattermostFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val hasPreviewImage: Boolean,
    val sizeBytes: Long = UNKNOWN_FILE_SIZE,
)

data class UploadCapabilities(
    val enabled: Boolean = true,
    val maxFileSizeBytes: Long? = null,
)

data class FeedImage(
    val fileId: String,
    val name: String,
    val width: Int,
    val height: Int,
    val hasPreviewImage: Boolean,
)

data class FeedFile(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = UNKNOWN_FILE_SIZE,
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/", ignoreCase = true) ||
            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private companion object {
        val VIDEO_EXTENSIONS = setOf(
            "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ogv", "ts", "webm",
        )
    }
}

const val UNKNOWN_FILE_SIZE = -1L

data class PostPage(
    val order: List<String>,
    val posts: Map<String, MattermostPost>,
) {
    val orderedPosts: List<MattermostPost>
        get() = order.mapNotNull(posts::get)
}

data class PostSearchPage(
    val order: List<String>,
    val posts: Map<String, MattermostPost>,
    val matches: Map<String, List<String>> = emptyMap(),
) {
    val orderedPosts: List<MattermostPost>
        get() = order.mapNotNull(posts::get)
}

data class MessageSearchResult(
    val postId: String,
    val channelId: String,
    val rootId: String,
    val userId: String,
    val author: String,
    val message: String,
    val createAt: Long,
    val fileCount: Int,
    val matchTerms: List<String>,
)

enum class DeliveryState {
    SENT,
    OPTIMISTIC,
    UNCONFIRMED,
    FAILED,
}

data class FeedItem(
    val key: String,
    val serverId: String?,
    val pendingId: String?,
    val channelId: String,
    val userId: String,
    val author: String,
    val message: String,
    val createAt: Long,
    val updateAt: Long,
    val deliveryState: DeliveryState,
    val fileIds: List<String> = emptyList(),
    val images: List<FeedImage> = emptyList(),
    val files: List<FeedFile> = emptyList(),
    val linkPreviews: List<LinkPreview> = emptyList(),
    val editAt: Long = 0,
)

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    OFFLINE,
}

sealed interface FeedMutation {
    data class Replace(
        val items: List<FeedItem>,
        val conversationId: String? = null,
    ) : FeedMutation
    data class Prepend(val items: List<FeedItem>) : FeedMutation
    data class PageInsert(val index: Int, val items: List<FeedItem>) : FeedMutation
    data class PageWindow(val items: List<FeedItem>) : FeedMutation
    data class Insert(val index: Int, val item: FeedItem) : FeedMutation
    data class Update(val previousKey: String, val item: FeedItem) : FeedMutation
    data class Remove(val key: String) : FeedMutation
}

sealed interface SocketSignal {
    data object Opened : SocketSignal

    data class Hello(
        val connectionId: String,
        val sequence: Long,
    ) : SocketSignal

    data class PostEvent(
        val event: String,
        val post: MattermostPost,
        val channelId: String,
        val sequence: Long,
    ) : SocketSignal

    data class StatusChange(
        val userId: String,
        val status: String,
        val sequence: Long,
    ) : SocketSignal

    data class Sequence(val sequence: Long) : SocketSignal

    data class Disconnected(val cause: Throwable?) : SocketSignal
}

data class SocketCursor(
    val connectionId: String,
    val nextSequence: Long,
)

class MattermostException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class TeamNotFoundException(
    val teamName: String,
) : Exception("Team \"$teamName\" was not found.")

class ChannelNotFoundException(
    val channelName: String,
    val teamName: String,
) : Exception("Channel \"$channelName\" was not found in team \"$teamName\".")
