package com.marcobehler.chattermore.network

import com.marcobehler.chattermore.model.ChannelNotFoundException
import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MattermostChannelMember
import com.marcobehler.chattermore.model.MattermostFile
import com.marcobehler.chattermore.model.MattermostException
import com.marcobehler.chattermore.model.MattermostPost
import com.marcobehler.chattermore.model.MattermostTeam
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.MattermostUserStatus
import com.marcobehler.chattermore.model.PostPage
import com.marcobehler.chattermore.model.PostSearchPage
import com.marcobehler.chattermore.model.ServerConfig
import com.marcobehler.chattermore.model.TeamNotFoundException
import com.marcobehler.chattermore.model.UploadCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    suspend fun resolveTeam(config: AuthenticatedServerConfig): MattermostTeam {
        if (config.teamName.isBlank()) {
            return parseTeams(
                execute(
                    request(
                        config,
                        apiUrl(config, "users", "me", "teams"),
                    ),
                ),
            ).firstOrNull()
                ?: throw IllegalArgumentException("This account does not belong to a Mattermost team.")
        }
        return try {
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
    }

    suspend fun channelsForUser(
        config: AuthenticatedServerConfig,
        userId: String,
        teamId: String,
    ): List<MattermostChannel> = parseChannels(
        execute(
            request(
                config,
                apiUrl(config, "users", userId, "teams", teamId, "channels"),
            ),
        ),
    )

    suspend fun channel(config: AuthenticatedServerConfig, channelId: String): MattermostChannel =
        parseChannel(execute(request(config, apiUrl(config, "channels", channelId))))

    suspend fun channelMemberships(
        config: AuthenticatedServerConfig,
        userId: String,
        teamId: String,
    ): List<MattermostChannelMember> = parseChannelMembers(
        execute(
            request(
                config,
                apiUrl(config, "users", userId, "teams", teamId, "channels", "members"),
            ),
        ),
    )

    suspend fun viewChannel(
        config: AuthenticatedServerConfig,
        userId: String,
        channelId: String,
        previousChannelId: String?,
    ) {
        val body = channelViewBody(channelId, previousChannelId).toRequestBody(JSON_MEDIA_TYPE)
        execute(
            request(config, apiUrl(config, "channels", "members", userId, "view"))
                .newBuilder()
                .post(body)
                .build(),
        )
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
        val responseBody = execute(request(config, url))
        return withContext(Dispatchers.Default) { parsePostPage(responseBody) }
    }

    suspend fun post(
        config: AuthenticatedServerConfig,
        postId: String,
    ): MattermostPost {
        require(postId.isNotBlank()) { "The post ID cannot be blank" }
        val responseBody = execute(request(config, apiUrl(config, "posts", postId)))
        return withContext(Dispatchers.Default) { parsePost(responseBody) }
    }

    suspend fun searchPosts(
        config: AuthenticatedServerConfig,
        teamId: String,
        terms: String,
        page: Int = 0,
        perPage: Int = 50,
    ): PostSearchPage {
        require(teamId.isNotBlank()) { "The team ID cannot be blank" }
        require(terms.isNotBlank()) { "Search terms cannot be blank" }
        val body = searchPostsBody(terms, page, perPage).toRequestBody(JSON_MEDIA_TYPE)
        val responseBody = execute(
            request(config, apiUrl(config, "teams", teamId, "posts", "search"))
                .newBuilder()
                .post(body)
                .build(),
        )
        return withContext(Dispatchers.Default) { parsePostSearchPage(responseBody) }
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

    suspend fun directChannel(
        config: AuthenticatedServerConfig,
        currentUserId: String,
        otherUserId: String,
    ): MattermostChannel {
        require(currentUserId.isNotBlank()) { "The current user ID cannot be blank" }
        require(otherUserId.isNotBlank()) { "The other user ID cannot be blank" }
        val body = userIdsBody(listOf(currentUserId, otherUserId)).toRequestBody(JSON_MEDIA_TYPE)
        return parseChannel(
            execute(
                request(config, apiUrl(config, "channels", "direct")).newBuilder()
                    .post(body)
                    .build(),
            ),
        )
    }

    suspend fun usersByUsernames(
        config: AuthenticatedServerConfig,
        usernames: Collection<String>,
    ): List<MattermostUser> {
        if (usernames.isEmpty()) return emptyList()
        val body = usernamesBody(usernames).toRequestBody(JSON_MEDIA_TYPE)
        return parseUsers(
            execute(
                request(config, apiUrl(config, "users", "usernames")).newBuilder()
                    .post(body)
                    .build(),
            ),
        )
    }

    suspend fun usersInChannel(
        config: AuthenticatedServerConfig,
        channelId: String,
    ): List<MattermostUser> {
        val users = mutableListOf<MattermostUser>()
        var page = 0
        do {
            val pageUsers = parseUsers(
                execute(
                    request(
                        config,
                        apiUrl(config, "users").newBuilder()
                            .addQueryParameter("in_channel", channelId)
                            .addQueryParameter("active", "true")
                            .addQueryParameter("page", page.toString())
                            .addQueryParameter("per_page", USERS_PER_PAGE.toString())
                            .build(),
                    ),
                ),
            )
            users += pageUsers
            page++
        } while (pageUsers.size == USERS_PER_PAGE)
        return users
    }

    suspend fun userStatuses(
        config: AuthenticatedServerConfig,
        ids: Collection<String>,
    ): List<MattermostUserStatus> {
        if (ids.isEmpty()) return emptyList()
        val body = userIdsBody(ids).toRequestBody(JSON_MEDIA_TYPE)
        return parseUserStatuses(
            execute(
                request(config, apiUrl(config, "users", "status", "ids")).newBuilder()
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
    ): ApiResponse = callClient.newCall(request).readResponse { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            throw MattermostException(
                response.code,
                errorMessage(body).ifBlank { "Mattermost returned HTTP ${response.code}" },
            )
        }
        ApiResponse(body, response.header("Token"))
    }

    private companion object {
        const val USERS_PER_PAGE = 200
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
