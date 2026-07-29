package com.marcobehler.chattermore.network

import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MattermostChannelMember
import com.marcobehler.chattermore.model.MattermostFile
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.MattermostPost
import com.marcobehler.chattermore.model.MattermostTeam
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.MattermostUserStatus
import com.marcobehler.chattermore.model.PostPage
import com.marcobehler.chattermore.model.UNKNOWN_FILE_SIZE
import com.marcobehler.chattermore.model.UploadCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val mattermostJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

internal fun parseUser(json: String): MattermostUser =
    parseUser(mattermostJson.parseToJsonElement(json).jsonObject)

internal fun parseUser(value: JsonObject): MattermostUser = MattermostUser(
    id = value.string("id"),
    username = value.string("username"),
    firstName = value.string("first_name"),
    lastName = value.string("last_name"),
)

internal fun parseUsers(json: String): List<MattermostUser> =
    mattermostJson.parseToJsonElement(json).jsonArray.map { parseUser(it.jsonObject) }

internal fun parseUserStatuses(json: String): List<MattermostUserStatus> =
    mattermostJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val value = element.objectOrNull() ?: return@mapNotNull null
        val userId = value.string("user_id")
        if (userId.isBlank()) return@mapNotNull null
        MattermostUserStatus(
            userId = userId,
            status = value.string("status"),
        )
    }

internal fun parseTeam(json: String): MattermostTeam {
    val value = mattermostJson.parseToJsonElement(json).jsonObject
    return parseTeam(value)
}

internal fun parseTeam(value: JsonObject): MattermostTeam = MattermostTeam(
    id = value.string("id"),
    name = value.string("name"),
    displayName = value.string("display_name").ifBlank { value.string("name") },
)

internal fun parseTeams(json: String): List<MattermostTeam> =
    mattermostJson.parseToJsonElement(json).jsonArray.map { parseTeam(it.jsonObject) }

internal fun parseChannel(json: String): MattermostChannel {
    val value = mattermostJson.parseToJsonElement(json).jsonObject
    return parseChannel(value)
}

internal fun parseChannel(value: JsonObject): MattermostChannel = MattermostChannel(
    id = value.string("id"),
    name = value.string("name"),
    displayName = value.string("display_name").ifBlank { value.string("name") },
    teamId = value.string("team_id"),
    type = value.string("type"),
    lastPostAt = value.long("last_post_at"),
    totalMessageCount = value.long("total_msg_count"),
    deleteAt = value.long("delete_at"),
)

internal fun parseChannels(json: String): List<MattermostChannel> =
    mattermostJson.parseToJsonElement(json).jsonArray.map { parseChannel(it.jsonObject) }

internal fun parseChannelMembers(json: String): List<MattermostChannelMember> =
    mattermostJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val value = element.objectOrNull() ?: return@mapNotNull null
        val channelId = value.string("channel_id")
        if (channelId.isBlank()) return@mapNotNull null
        MattermostChannelMember(
            channelId = channelId,
            userId = value.string("user_id"),
            messageCount = value.long("msg_count"),
            mentionCount = value.long("mention_count"),
            lastViewedAt = value.long("last_viewed_at"),
        )
    }

internal fun parsePost(json: String): MattermostPost =
    parsePost(mattermostJson.parseToJsonElement(json).jsonObject)

internal fun parsePost(value: JsonObject): MattermostPost {
    val metadata = value["metadata"]?.objectOrNull()
    return MattermostPost(
        id = value.string("id"),
        channelId = value.string("channel_id"),
        userId = value.string("user_id"),
        message = value.string("message"),
        createAt = value.long("create_at"),
        updateAt = value.long("update_at"),
        deleteAt = value.long("delete_at"),
        rootId = value.string("root_id"),
        type = value.string("type"),
        pendingPostId = value.string("pending_post_id"),
        fileIds = value.stringList("file_ids"),
        files = metadata
            ?.get("files")
            ?.arrayOrNull()
            ?.mapNotNull { it.objectOrNull()?.let(::parseFile) }
            .orEmpty(),
        linkPreviews = metadata
            ?.get("embeds")
            ?.arrayOrNull()
            ?.mapNotNull { it.objectOrNull()?.let(::parseLinkPreview) }
            .orEmpty(),
        hasMetadata = metadata != null,
        editAt = value.long("edit_at"),
    )
}

internal fun parseLinkPreview(value: JsonObject): LinkPreview? {
    if (value.string("type") != "opengraph") return null
    val url = value.string("url").trim()
    if (url.isBlank()) return null
    val data = value["data"]?.objectOrNull() ?: return null
    val title = data.string("title").trim()
    val description = data.string("description").trim()
    val siteName = data.string("site_name").trim()
    val imageUrl = data["images"]
        ?.arrayOrNull()
        ?.asSequence()
        ?.mapNotNull { it.objectOrNull() }
        ?.mapNotNull { image ->
            image.string("secure_url").ifBlank { image.string("url") }.trim().ifBlank { null }
        }
        ?.firstOrNull()
    if (title.isBlank() && description.isBlank() && siteName.isBlank()) return null
    return LinkPreview(
        url = url,
        title = title,
        description = description,
        siteName = siteName,
        imageUrl = imageUrl,
    )
}

internal fun parseFiles(json: String): List<MattermostFile> =
    mattermostJson.parseToJsonElement(json).jsonArray.mapNotNull {
        it.objectOrNull()?.let(::parseFile)
    }

internal fun parseUploadedFiles(json: String): List<MattermostFile> =
    mattermostJson.parseToJsonElement(json).jsonObject["file_infos"]
        ?.arrayOrNull()
        ?.mapNotNull { it.objectOrNull()?.let(::parseFile) }
        .orEmpty()

internal fun parseUploadCapabilities(json: String): UploadCapabilities {
    val value = mattermostJson.parseToJsonElement(json).jsonObject
    val attachmentsEnabled = value["EnableFileAttachments"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: true
    val mobileUploadEnabled = value["EnableMobileFileUpload"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: true
    val maxFileSize = value["MaxFileSize"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
    return UploadCapabilities(
        enabled = attachmentsEnabled && mobileUploadEnabled,
        maxFileSizeBytes = maxFileSize,
    )
}

internal fun parseFile(value: JsonObject): MattermostFile = MattermostFile(
    id = value.string("id"),
    name = value.string("name"),
    mimeType = value.string("mime_type"),
    width = value.int("width"),
    height = value.int("height"),
    hasPreviewImage = value.boolean("has_preview_image"),
    sizeBytes = value.optionalLong("size") ?: UNKNOWN_FILE_SIZE,
)

internal fun parsePostPage(json: String): PostPage {
    val value = mattermostJson.parseToJsonElement(json).jsonObject
    val order = value["order"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    val posts = value["posts"]?.jsonObject
        ?.mapValues { (_, post) -> parsePost(post.jsonObject) }
        .orEmpty()
    return PostPage(order = order, posts = posts)
}

internal fun createPostBody(
    channelId: String,
    message: String,
    pendingPostId: String,
    fileIds: List<String> = emptyList(),
): String = buildJsonObject {
    put("channel_id", channelId)
    put("message", message)
    put("pending_post_id", pendingPostId)
    if (fileIds.isNotEmpty()) {
        putJsonArray("file_ids") { fileIds.forEach { add(JsonPrimitive(it)) } }
    }
}.toString()

internal fun patchPostBody(message: String): String = buildJsonObject {
    put("message", message)
}.toString()

internal fun loginBody(username: String, password: String): String = buildJsonObject {
    put("login_id", username)
    put("password", password)
}.toString()

internal fun userIdsBody(ids: Collection<String>): String =
    JsonArray(ids.map(::JsonPrimitive)).toString()

internal fun usernamesBody(usernames: Collection<String>): String =
    JsonArray(usernames.map(::JsonPrimitive)).toString()

internal fun channelViewBody(channelId: String, previousChannelId: String?): String =
    buildJsonObject {
        put("channel_id", channelId)
        previousChannelId?.takeIf(String::isNotBlank)?.let { put("prev_channel_id", it) }
    }.toString()

internal fun errorMessage(json: String): String = runCatching {
    mattermostJson.parseToJsonElement(json).jsonObject.string("message")
}.getOrDefault("")

internal fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L

internal fun JsonObject.optionalLong(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.longOrNull?.toInt() ?: 0

internal fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

internal fun JsonObject.stringList(name: String): List<String> =
    this[name]?.arrayOrNull()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.arrayOrNull(): JsonArray? = this as? JsonArray
