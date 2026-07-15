package com.example.mattermostlite.network

import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostFile
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostTeam
import com.example.mattermostlite.model.MattermostUser
import com.example.mattermostlite.model.PostPage
import com.example.mattermostlite.model.UNKNOWN_FILE_SIZE
import com.example.mattermostlite.model.UploadCapabilities
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
)

internal fun parseChannels(json: String): List<MattermostChannel> =
    mattermostJson.parseToJsonElement(json).jsonArray.map { parseChannel(it.jsonObject) }

internal fun parsePost(json: String): MattermostPost =
    parsePost(mattermostJson.parseToJsonElement(json).jsonObject)

internal fun parsePost(value: JsonObject): MattermostPost = MattermostPost(
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
    files = value["metadata"]?.objectOrNull()
        ?.get("files")
        ?.arrayOrNull()
        ?.mapNotNull { it.objectOrNull()?.let(::parseFile) }
        .orEmpty(),
    editAt = value.long("edit_at"),
)

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
