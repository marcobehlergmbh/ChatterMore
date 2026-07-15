package com.example.mattermostlite.model

data class ServerConfig(
    val baseUrl: String,
    val teamName: String,
    val channelName: String,
    val username: String,
    val password: String,
)

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
)

data class MattermostTeam(
    val id: String,
    val name: String,
    val displayName: String,
)

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
    val editAt: Long = 0,
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
    val editAt: Long = 0,
)

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    OFFLINE,
}

sealed interface FeedMutation {
    data class Replace(val items: List<FeedItem>) : FeedMutation
    data class Prepend(val items: List<FeedItem>) : FeedMutation
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
