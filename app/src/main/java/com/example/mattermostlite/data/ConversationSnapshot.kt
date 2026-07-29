package com.example.mattermostlite.data

import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedFile
import com.example.mattermostlite.model.FeedImage
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.model.LinkPreview
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.network.mattermostJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ConversationSnapshot(
    val baseUrl: String,
    val teamName: String,
    val username: String,
    val teamId: String,
    val userId: String,
    val channelId: String,
    val title: String,
    val peerUserId: String?,
    val items: List<FeedItem>,
    val savedAt: Long,
) {
    fun matches(config: ServerConfig): Boolean =
        normalizedBaseUrl(baseUrl) == normalizedBaseUrl(config.baseUrl) &&
            teamName.equals(config.teamName.trim(), ignoreCase = true) &&
            username.equals(config.username.trim(), ignoreCase = true)
}

internal object ConversationSnapshotCodec {
    fun encode(snapshot: ConversationSnapshot): String = buildJsonObject {
        put("version", VERSION)
        put("base_url", snapshot.baseUrl)
        put("team_name", snapshot.teamName)
        put("username", snapshot.username)
        put("team_id", snapshot.teamId)
        put("user_id", snapshot.userId)
        put("channel_id", snapshot.channelId)
        put("title", snapshot.title)
        put("peer_user_id", snapshot.peerUserId.orEmpty())
        put("saved_at", snapshot.savedAt)
        put("items", buildJsonArray {
            snapshot.items
                .filter { it.deliveryState == DeliveryState.SENT && !it.serverId.isNullOrBlank() }
                .takeLast(MAX_ITEMS)
                .forEach { add(it.toJson()) }
        })
    }.toString()

    fun encodeBounded(snapshot: ConversationSnapshot, maxBytes: Int): String? {
        require(maxBytes > 0)
        var items = snapshot.items.takeLast(MAX_ITEMS)
        while (items.isNotEmpty()) {
            val encoded = encode(snapshot.copy(items = items))
            if (encoded.toByteArray(Charsets.UTF_8).size <= maxBytes) return encoded
            items = items.drop(maxOf(1, items.size / 4))
        }
        return encode(snapshot.copy(items = emptyList()))
            .takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
    }

    fun decode(encoded: String): ConversationSnapshot? = runCatching {
        val value = mattermostJson.parseToJsonElement(encoded).jsonObject
        if (value.long("version") != VERSION.toLong()) return null
        val baseUrl = value.string("base_url")
        val teamName = value.string("team_name")
        val username = value.string("username")
        val teamId = value.string("team_id")
        val userId = value.string("user_id")
        val channelId = value.string("channel_id")
        val title = value.string("title")
        if (
            baseUrl.isBlank() ||
            teamName.isBlank() ||
            username.isBlank() ||
            teamId.isBlank() ||
            userId.isBlank() ||
            channelId.isBlank() ||
            title.isBlank()
        ) {
            return null
        }
        ConversationSnapshot(
            baseUrl = baseUrl,
            teamName = teamName,
            username = username,
            teamId = teamId,
            userId = userId,
            channelId = channelId,
            title = title,
            peerUserId = value.string("peer_user_id").ifBlank { null },
            items = value["items"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject.toFeedItem(channelId) }
                ?.takeLast(MAX_ITEMS)
                .orEmpty(),
            savedAt = value.long("saved_at"),
        )
    }.getOrNull()

    private fun FeedItem.toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("server_id", serverId.orEmpty())
        put("channel_id", channelId)
        put("user_id", userId)
        put("author", author)
        put("message", message)
        put("create_at", createAt)
        put("update_at", updateAt)
        put("edit_at", editAt)
        put("file_ids", JsonArray(fileIds.map(::JsonPrimitive)))
        put("images", buildJsonArray {
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("file_id", image.fileId)
                        put("name", image.name)
                        put("width", image.width)
                        put("height", image.height)
                        put("has_preview", image.hasPreviewImage)
                    },
                )
            }
        })
        put("files", buildJsonArray {
            files.forEach { file ->
                add(
                    buildJsonObject {
                        put("file_id", file.fileId)
                        put("name", file.name)
                        put("mime_type", file.mimeType)
                        put("size", file.sizeBytes)
                    },
                )
            }
        })
        put("previews", buildJsonArray {
            linkPreviews.forEach { preview ->
                add(
                    buildJsonObject {
                        put("url", preview.url)
                        put("title", preview.title)
                        put("description", preview.description)
                        put("site_name", preview.siteName)
                        put("image_url", preview.imageUrl.orEmpty())
                    },
                )
            }
        })
    }

    private fun JsonObject.toFeedItem(expectedChannelId: String): FeedItem? {
        val serverId = string("server_id").takeIf(String::isNotBlank) ?: return null
        val channelId = string("channel_id")
        val userId = string("user_id")
        if (channelId != expectedChannelId || userId.isBlank()) return null
        return FeedItem(
            key = string("key").ifBlank { "post:$serverId" },
            serverId = serverId,
            pendingId = null,
            channelId = channelId,
            userId = userId,
            author = string("author").ifBlank { userId },
            message = string("message"),
            createAt = long("create_at"),
            updateAt = long("update_at"),
            deliveryState = DeliveryState.SENT,
            fileIds = stringList("file_ids"),
            images = this["images"]
                ?.jsonArray
                ?.mapNotNull { image ->
                    val data = image.jsonObject
                    val fileId = data.string("file_id").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    FeedImage(
                        fileId = fileId,
                        name = data.string("name"),
                        width = data.long("width").toInt(),
                        height = data.long("height").toInt(),
                        hasPreviewImage = data.boolean("has_preview"),
                    )
                }
                .orEmpty(),
            files = this["files"]
                ?.jsonArray
                ?.mapNotNull { file ->
                    val data = file.jsonObject
                    val fileId = data.string("file_id").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    FeedFile(
                        fileId = fileId,
                        name = data.string("name").ifBlank { fileId },
                        mimeType = data.string("mime_type").ifBlank { "application/octet-stream" },
                        sizeBytes = data.long("size"),
                    )
                }
                .orEmpty(),
            linkPreviews = this["previews"]
                ?.jsonArray
                ?.mapNotNull { preview ->
                    val data = preview.jsonObject
                    val url = data.string("url").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    LinkPreview(
                        url = url,
                        title = data.string("title"),
                        description = data.string("description"),
                        siteName = data.string("site_name"),
                        imageUrl = data.string("image_url").ifBlank { null },
                    )
                }
                .orEmpty(),
            editAt = long("edit_at"),
        )
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.stringList(name: String): List<String> =
        this[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    const val MAX_ITEMS = 100
    private const val VERSION = 1
}

private fun normalizedBaseUrl(value: String): String = value.trim().trimEnd('/')
