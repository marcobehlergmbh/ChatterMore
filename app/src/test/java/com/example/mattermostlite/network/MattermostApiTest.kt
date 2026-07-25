package com.example.mattermostlite.network

import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MattermostApiTest {
    @Test
    fun `login exchanges credentials for a session used by authenticated requests`() = runBlocking {
        val requests = mutableListOf<CapturedRequest>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request.capture()
                val (body, token) = when (request.url.encodedPath) {
                    "/api/v4/users/login" ->
                        """{"id":"user-id","username":"marco"}""" to "session-token"
                    "/api/v4/teams/name/engineering" ->
                        """{"id":"team-id","name":"engineering","display_name":"Engineering"}""" to null
                    "/api/v4/teams/team-id/channels/name/town-square" ->
                        """{"id":"channel-id","name":"town-square","display_name":"Town Square"}""" to null
                    else -> error("Unexpected request: ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .apply { token?.let { header("Token", it) } }
                    .body(body.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
        val api = MattermostApi(client)

        val (session, user) = api.login(
            ServerConfig(
                baseUrl = "https://mattermost.example.com/",
                teamName = "engineering",
                channelName = "town-square",
                username = "marco",
                password = "secret",
            ),
        )
        val channel = api.resolveChannel(session)

        assertEquals("user-id", user.id)
        assertEquals("channel-id", channel.id)
        assertEquals("https://mattermost.example.com", session.baseUrl)
        assertEquals("session-token", session.token)

        val login = requests.first()
        assertEquals("/api/v4/users/login", login.path)
        assertNull(login.authorization)
        val loginJson = mattermostJson.parseToJsonElement(login.body).jsonObject
        assertEquals("marco", loginJson.string("login_id"))
        assertEquals("secret", loginJson.string("password"))
        requests.drop(1).forEach {
            assertEquals("Bearer session-token", it.authorization)
        }
    }

    @Test
    fun `patches a post message with the authenticated Mattermost endpoint`() = runBlocking {
        var captured: CapturedRequest? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured = request.capture()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"id":"post-1","channel_id":"channel-1","user_id":"user-1","message":"updated","create_at":10,"update_at":20,"edit_at":20}"""
                            .toResponseBody(JSON_MEDIA_TYPE),
                    )
                    .build()
            }
            .build()
        val api = MattermostApi(client)

        val post = api.patchPost(
            AuthenticatedServerConfig(
                baseUrl = "https://mattermost.example.com",
                teamName = "engineering",
                channelName = "town-square",
                token = "session-token",
            ),
            postId = "post-1",
            message = "updated",
        )

        assertEquals("updated", post.message)
        assertEquals(20L, post.editAt)
        assertEquals("/api/v4/posts/post-1/patch", captured?.path)
        assertEquals("PUT", captured?.method)
        assertEquals("Bearer session-token", captured?.authorization)
        assertEquals(
            "updated",
            captured?.body?.let { mattermostJson.parseToJsonElement(it).jsonObject.string("message") },
        )
    }

    @Test
    fun `loads user statuses in one authenticated request`() = runBlocking {
        var captured: CapturedRequest? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured = request.capture()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """[{"user_id":"user-1","status":"online"},{"user_id":"user-2","status":"away"}]"""
                            .toResponseBody(JSON_MEDIA_TYPE),
                    )
                    .build()
            }
            .build()
        val api = MattermostApi(client)
        val session = AuthenticatedServerConfig(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            channelName = "town-square",
            token = "session-token",
        )

        val statuses = api.userStatuses(session, listOf("user-1", "user-2"))

        assertEquals(listOf("online", "away"), statuses.map { it.status })
        assertEquals("/api/v4/users/status/ids", captured?.path)
        assertEquals("POST", captured?.method)
        assertEquals("Bearer session-token", captured?.authorization)
        assertEquals("[\"user-1\",\"user-2\"]", captured?.body)
    }

    @Test
    fun `gets or creates a direct channel for exactly two users`() = runBlocking {
        var captured: CapturedRequest? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured = request.capture()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"id":"dm-channel","name":"user-1__user-2","display_name":""}"""
                            .toResponseBody(JSON_MEDIA_TYPE),
                    )
                    .build()
            }
            .build()
        val api = MattermostApi(client)
        val session = AuthenticatedServerConfig(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            channelName = "town-square",
            token = "session-token",
        )

        val channel = api.directChannel(session, "user-1", "user-2")

        assertEquals("dm-channel", channel.id)
        assertEquals("/api/v4/channels/direct", captured?.path)
        assertEquals("POST", captured?.method)
        assertEquals("Bearer session-token", captured?.authorization)
        assertEquals("[\"user-1\",\"user-2\"]", captured?.body)
    }

    @Test
    fun `resolves mention usernames in one authenticated request`() = runBlocking {
        var captured: CapturedRequest? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured = request.capture()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"id":"user-1","username":"alice"}]""".toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
        val api = MattermostApi(client)
        val session = AuthenticatedServerConfig(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            channelName = "town-square",
            token = "session-token",
        )

        val users = api.usersByUsernames(session, listOf("alice", "bob"))

        assertEquals(listOf("alice"), users.map { it.username })
        assertEquals("/api/v4/users/usernames", captured?.path)
        assertEquals("POST", captured?.method)
        assertEquals("Bearer session-token", captured?.authorization)
        assertEquals("[\"alice\",\"bob\"]", captured?.body)
    }

    @Test
    fun `loads every active user in a channel using paginated requests`() = runBlocking {
        val requestedPages = mutableListOf<String?>()
        val requestedChannels = mutableListOf<String?>()
        val requestedPageSizes = mutableListOf<String?>()
        val requestedActiveFilters = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedPages += request.url.queryParameter("page")
                requestedChannels += request.url.queryParameter("in_channel")
                requestedPageSizes += request.url.queryParameter("per_page")
                requestedActiveFilters += request.url.queryParameter("active")
                val page = request.url.queryParameter("page")?.toInt()
                val body = if (page == 0) {
                    (0 until 200).joinToString(prefix = "[", postfix = "]") {
                        """{"id":"user-$it","username":"user$it"}"""
                    }
                } else {
                    """[{"id":"user-200","username":"user200"}]"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
        val api = MattermostApi(client)
        val session = AuthenticatedServerConfig(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            channelName = "town-square",
            token = "session-token",
        )

        val users = api.usersInChannel(session, "channel-1")

        assertEquals(201, users.size)
        assertEquals("user200", users.last().username)
        assertEquals(listOf("0", "1"), requestedPages)
        assertEquals(listOf("channel-1", "channel-1"), requestedChannels)
        assertEquals(listOf("200", "200"), requestedPageSizes)
        assertEquals(listOf("true", "true"), requestedActiveFilters)
    }

    @Test
    fun `loads upload capabilities and uploads multipart file`() = runBlocking {
        val requests = mutableListOf<CapturedRequest>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request.capture()
                val body = when (request.url.encodedPath) {
                    "/api/v4/config/client" ->
                        """{"EnableFileAttachments":"true","EnableMobileFileUpload":"true","MaxFileSize":"1024"}"""
                    "/api/v4/files" ->
                        """{"file_infos":[{"id":"file-1","name":"note.txt","mime_type":"text/plain","size":5}],"client_ids":["local-1"]}"""
                    else -> error("Unexpected request: ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (request.url.encodedPath.endsWith("files")) 201 else 200)
                    .message("OK")
                    .body(body.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
        val api = MattermostApi(client)
        val session = AuthenticatedServerConfig(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            channelName = "town-square",
            token = "session-token",
        )

        val capabilities = api.uploadCapabilities(session)
        val progress = mutableListOf<Pair<Long, Long?>>()
        var bodyComplete = false
        val uploaded = api.uploadFile(
            session,
            channelId = "channel-1",
            fileName = "note.txt",
            clientId = "local-1",
            body = "hello".toRequestBody("text/plain".toMediaType()),
            onProgress = { written, total -> progress += written to total },
            onBodyComplete = { bodyComplete = true },
        )

        assertEquals(1024L, capabilities.maxFileSizeBytes)
        assertEquals("file-1", uploaded.id)
        val upload = requests.last()
        assertEquals("/api/v4/files", upload.path)
        assertEquals("POST", upload.method)
        assertEquals("Bearer session-token", upload.authorization)
        assertTrue(upload.body.contains("name=\"channel_id\""))
        assertTrue(upload.body.contains("channel-1"))
        assertTrue(upload.body.contains("filename=\"note.txt\""))
        assertTrue(upload.body.contains("hello"))
        assertEquals(0L, progress.first().first)
        assertEquals(progress.last().second, progress.last().first)
        assertTrue(bodyComplete)
    }

    private fun Request.capture(): CapturedRequest {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return CapturedRequest(
            path = url.encodedPath,
            method = method,
            authorization = header("Authorization"),
            body = buffer.readUtf8(),
        )
    }

    private data class CapturedRequest(
        val path: String,
        val method: String,
        val authorization: String?,
        val body: String,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
