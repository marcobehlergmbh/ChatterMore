package com.example.mattermostlite.network

import com.example.mattermostlite.model.ChannelNotFoundException
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostException
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostTeam
import com.example.mattermostlite.model.MattermostUser
import com.example.mattermostlite.model.PostPage
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.model.TeamNotFoundException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MattermostApi(
    private val client: OkHttpClient,
) {
    suspend fun currentUser(config: ServerConfig): MattermostUser =
        parseUser(execute(request(config, apiUrl(config, "users", "me"))))

    suspend fun resolveChannel(config: ServerConfig): MattermostChannel {
        val team = resolveTeam(config)
        return try {
            parseChannel(
                execute(
                    request(
                        config,
                        apiUrl(config, "teams", team.id, "channels", "name", config.channelName),
                    ),
                ),
            )
        } catch (error: MattermostException) {
            if (error.statusCode != 404) throw error

            val matches = parseChannels(
                execute(
                    request(
                        config,
                        apiUrl(config, "users", "me", "teams", team.id, "channels"),
                    ),
                ),
            ).filter { it.matches(config.channelName) }
            when (matches.size) {
                0 -> throw ChannelNotFoundException(config.channelName, config.teamName)
                1 -> matches.single()
                else -> throw IllegalArgumentException(
                    "More than one channel is displayed as \"${config.channelName}\". Enter its URL name instead.",
                )
            }
        }
    }

    private suspend fun resolveTeam(config: ServerConfig): MattermostTeam = try {
        parseTeam(
            execute(
                request(config, apiUrl(config, "teams", "name", config.teamName)),
            ),
        )
    } catch (error: MattermostException) {
        if (error.statusCode != 404) throw error

        val matches = parseTeams(
            execute(
                request(
                    config,
                    apiUrl(config, "users", "me", "teams"),
                ),
            ),
        ).filter { it.matches(config.teamName) }
        when (matches.size) {
            0 -> throw TeamNotFoundException(config.teamName)
            1 -> matches.single()
            else -> throw IllegalArgumentException(
                "More than one team is displayed as \"${config.teamName}\". Enter its URL name instead.",
            )
        }
    }

    suspend fun posts(
        config: ServerConfig,
        channelId: String,
        before: String? = null,
        after: String? = null,
        perPage: Int = 100,
    ): PostPage {
        require(before == null || after == null) { "before and after are mutually exclusive" }
        val url = apiUrl(config, "channels", channelId, "posts").newBuilder()
            .addQueryParameter("per_page", perPage.coerceIn(1, 200).toString())
            .apply {
                before?.let { addQueryParameter("before", it) }
                after?.let { addQueryParameter("after", it) }
            }
            .build()
        return parsePostPage(execute(request(config, url)))
    }

    suspend fun users(config: ServerConfig, ids: Collection<String>): List<MattermostUser> {
        if (ids.isEmpty()) return emptyList()
        val body = userIdsBody(ids).toRequestBody(JSON_MEDIA_TYPE)
        return parseUsers(
            execute(
                request(config, apiUrl(config, "users", "ids")).newBuilder()
                    .post(body)
                    .build(),
            ),
        )
    }

    suspend fun createPost(
        config: ServerConfig,
        channelId: String,
        message: String,
        pendingPostId: String,
    ): MattermostPost {
        val body = createPostBody(channelId, message, pendingPostId).toRequestBody(JSON_MEDIA_TYPE)
        return parsePost(
            execute(
                request(config, apiUrl(config, "posts")).newBuilder()
                    .post(body)
                    .build(),
            ),
        )
    }

    fun normalizedConfig(config: ServerConfig): ServerConfig {
        val url = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid server URL")
        require(url.scheme == "https") { "The server URL must use HTTPS" }
        require(url.query == null && url.fragment == null) { "The server URL cannot contain a query or fragment" }
        require(config.teamName.isNotBlank()) { "Enter a team name" }
        require(config.channelName.isNotBlank()) { "Enter a channel name" }
        require(config.token.isNotBlank()) { "Enter a personal access token" }
        return config.copy(
            baseUrl = url.toString().trimEnd('/'),
            teamName = config.teamName.trim(),
            channelName = config.channelName.trim(),
            token = config.token.trim(),
        )
    }

    internal fun apiUrl(config: ServerConfig, vararg path: String): HttpUrl {
        val base = config.baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server URL")
        return base.newBuilder()
            .addPathSegments("api/v4")
            .apply { path.forEach(::addPathSegment) }
            .build()
    }

    private fun request(config: ServerConfig, url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.token}")
        .header("Accept", "application/json")
        .build()

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body.string()
                    if (!continuation.isActive) return
                    if (response.isSuccessful) {
                        continuation.resume(body)
                    } else {
                        continuation.resumeWithException(
                            MattermostException(
                                response.code,
                                errorMessage(body).ifBlank { "Mattermost returned HTTP ${response.code}" },
                            ),
                        )
                    }
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun MattermostTeam.matches(input: String): Boolean =
            name.equals(input, ignoreCase = true) || displayName.equals(input, ignoreCase = true)

        fun MattermostChannel.matches(input: String): Boolean =
            name.equals(input, ignoreCase = true) || displayName.equals(input, ignoreCase = true)
    }
}
