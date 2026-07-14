package com.example.mattermostlite.network

import com.example.mattermostlite.model.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun Request.capture(): CapturedRequest {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return CapturedRequest(
            path = url.encodedPath,
            authorization = header("Authorization"),
            body = buffer.readUtf8(),
        )
    }

    private data class CapturedRequest(
        val path: String,
        val authorization: String?,
        val body: String,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
