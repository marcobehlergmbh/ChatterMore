package com.example.mattermostlite.data

import android.content.Context
import android.util.Base64
import com.example.mattermostlite.model.ConversationKind
import com.example.mattermostlite.model.ConversationSummary
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.network.mattermostJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

class ConversationSummaryCache(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun load(key: Key): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(key.preferenceKey(), null) ?: return@withContext emptyList()
        runCatching {
            mattermostJson.parseToJsonElement(encoded).jsonArray.mapNotNull { element ->
                val value = element.jsonObject
                val id = value.string("id")
                val kind = runCatching {
                    ConversationKind.valueOf(value.string("kind"))
                }.getOrNull()
                if (id.isBlank() || kind == null) return@mapNotNull null
                val channel = MattermostChannel(
                    id = id,
                    name = value.string("name"),
                    displayName = value.string("display_name"),
                    teamId = value.string("team_id"),
                    type = value.string("type"),
                    lastPostAt = value.long("last_post_at"),
                    totalMessageCount = value.long("total_msg_count"),
                )
                ConversationSummary(
                    channel = channel,
                    kind = kind,
                    title = value.string("title"),
                    peerUserId = value.string("peer_user_id").ifBlank { null },
                    peerUsername = value.string("peer_username").ifBlank { null },
                    lastPostAt = value.long("last_post_at"),
                    unread = value.string("unread").toBooleanStrictOrNull() ?: false,
                    mentionCount = value.long("mention_count"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(key: Key, conversations: Collection<ConversationSummary>) =
        withContext(Dispatchers.IO) {
            val encoded = buildJsonArray {
                conversations.forEach { summary ->
                    add(
                        buildJsonObject {
                            put("id", summary.id)
                            put("name", summary.channel.name)
                            put("display_name", summary.channel.displayName)
                            put("team_id", summary.channel.teamId)
                            put("type", summary.channel.type)
                            put("total_msg_count", summary.channel.totalMessageCount)
                            put("kind", summary.kind.name)
                            put("title", summary.title)
                            put("peer_user_id", summary.peerUserId.orEmpty())
                            put("peer_username", summary.peerUsername.orEmpty())
                            put("last_post_at", summary.lastPostAt)
                            put("unread", summary.unread)
                            put("mention_count", summary.mentionCount)
                        },
                    )
                }
            }.toString()
            preferences.edit().putString(key.preferenceKey(), encoded).apply()
        }

    data class Key(
        val baseUrl: String,
        val userId: String,
        val teamId: String,
    ) {
        private val identity: String
            get() = "$baseUrl\u0000$userId\u0000$teamId"

        internal fun preferenceKey(): String = Base64.encodeToString(
            identity.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }

    private companion object {
        const val PREFERENCES = "conversation_summaries"
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun kotlinx.serialization.json.JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L
