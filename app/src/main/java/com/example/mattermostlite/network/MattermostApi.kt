package com.example.mattermostlite.network

import com.example.mattermostlite.model.ChannelNotFoundException
import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostFile
import com.example.mattermostlite.model.MattermostException
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostTeam
import com.example.mattermostlite.model.MattermostUser
import com.example.mattermostlite.model.PostPage
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.model.TeamNotFoundException
import com.example.mattermostlite.model.UploadCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MattermostApi(
    private val client: OkHttpClient,
    private val uploadClient: OkHttpClient = client,
) {
    suspend fun login(config: ServerConfig): Pair<AuthenticatedServerConfig, MattermostUser> {
        val normalized = normalizedConfig(config)
        val body = loginBody(normalized.username, normalized.password).toRequestBody(JSON_MEDIA_TYPE)
        val response = executeResponse(
            Request.Builder()
                .url(apiUrl(normalized.baseUrl, "users", "login"))
                .header("Accept", "application/json")
                .post(body)
                .build(),
        )
        val token = response.token
            ?: throw MattermostException(500, "The server did not return a session token.")
        return AuthenticatedServerConfig(
            baseUrl = normalized.baseUrl,
            teamName = normalized.teamName,
            channelName = normalized.channelName,
            token = token,
        ) to parseUser(response.body)
    }

    suspend fun resolveChannel(config: AuthenticatedServerConfig): MattermostChannel {
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

    private suspend fun resolveTeam(config: AuthenticatedServerConfig): MattermostTeam = try {
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
        config: AuthenticatedServerConfig,
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

    suspend fun users(config: AuthenticatedServerConfig, ids: Collection<String>): List<MattermostUser> {
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

    suspend fun filesForPost(config: AuthenticatedServerConfig, postId: String): List<MattermostFile> =
        parseFiles(
            execute(request(config, apiUrl(config, "posts", postId, "files", "info"))),
        )

    suspend fun uploadCapabilities(config: AuthenticatedServerConfig): UploadCapabilities =
        parseUploadCapabilities(
            execute(request(config, apiUrl(config, "config", "client"))),
        )

    suspend fun uploadFile(
        config: AuthenticatedServerConfig,
        channelId: String,
        fileName: String,
        clientId: String,
        body: RequestBody,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        onBodyComplete: () -> Unit = {},
    ): MattermostFile {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("channel_id", channelId)
            .addFormDataPart("client_ids", clientId)
            .addFormDataPart("files", fileName, body)
            .build()
        val progressBody = ProgressRequestBody(multipart, onProgress, onBodyComplete)
        val files = parseUploadedFiles(
            execute(
                request(config, apiUrl(config, "files")).newBuilder()
                    .post(progressBody)
                    .build(),
                uploadClient,
            ),
        )
        return files.singleOrNull()
            ?: throw MattermostException(500, "The server did not return the uploaded file metadata.")
    }

    suspend fun createPost(
        config: AuthenticatedServerConfig,
        channelId: String,
        message: String,
        pendingPostId: String,
        fileIds: List<String> = emptyList(),
    ): MattermostPost {
        val body = createPostBody(channelId, message, pendingPostId, fileIds).toRequestBody(JSON_MEDIA_TYPE)
        return parsePost(
            execute(
                request(config, apiUrl(config, "posts")).newBuilder()
                    .post(body)
                    .build(),
            ),
        )
    }

    suspend fun patchPost(
        config: AuthenticatedServerConfig,
        postId: String,
        message: String,
    ): MattermostPost {
        val body = patchPostBody(message).toRequestBody(JSON_MEDIA_TYPE)
        return parsePost(
            execute(
                request(config, apiUrl(config, "posts", postId, "patch")).newBuilder()
                    .put(body)
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
        require(config.username.isNotBlank()) { "Enter a username" }
        require(config.password.isNotBlank()) { "Enter a password" }
        return config.copy(
            baseUrl = url.toString().trimEnd('/'),
            teamName = config.teamName.trim(),
            channelName = config.channelName.trim(),
            username = config.username.trim(),
        )
    }

    internal fun apiUrl(config: AuthenticatedServerConfig, vararg path: String): HttpUrl =
        apiUrl(config.baseUrl, *path)

    private fun apiUrl(baseUrl: String, vararg path: String): HttpUrl {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server URL")
        return base.newBuilder()
            .addPathSegments("api/v4")
            .apply { path.forEach(::addPathSegment) }
            .build()
    }

    private fun request(config: AuthenticatedServerConfig, url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.token}")
        .header("Accept", "application/json")
        .build()

    private suspend fun execute(request: Request, callClient: OkHttpClient = client): String =
        executeResponse(request, callClient).body

    private suspend fun executeResponse(
        request: Request,
        callClient: OkHttpClient = client,
    ): ApiResponse = suspendCancellableCoroutine { continuation ->
        val call = callClient.newCall(request)
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
                        continuation.resume(ApiResponse(body, response.header("Token")))
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

    private data class ApiResponse(
        val body: String,
        val token: String?,
    )
}
