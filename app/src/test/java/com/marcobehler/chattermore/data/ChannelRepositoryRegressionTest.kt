package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.*
import com.marcobehler.chattermore.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class ChannelRepositoryRegressionTest {
    @Test fun `closing while cached login refresh is pending prevents initialization from committing`() = runBlocking {
        Fixture(coroutineContext).use { fixture ->
            fixture.blockDirectory = true
            val initialization = async { fixture.repository.initialize(fixture.login) }
            withTimeout(5000) { fixture.directoryStarted.await() }
            fixture.repository.close()
            fixture.release.countDown()
            try {
                initialization.await()
                fail("A cleared connection must not finish initialization")
            } catch (_: CancellationException) {
                // Expected: the caller cannot persist the cancelled initialization's credentials.
            }
            assertNull(fixture.repository.openConversation("a"))
        }
    }

    @Test fun `failure after leaving a channel survives switching back and can be retried`() = runBlocking {
        Fixture(coroutineContext).use { fixture ->
            val repo = fixture.repository
            repo.initialize(fixture.login)
            repo.openConversation("a")
            fixture.blockSend = true
            fixture.failSend = true
            val sending = async { repo.send("keep this message") }
            withTimeout(5000) { fixture.sendStarted.await() }
            repo.openConversation("b")
            fixture.release.countDown()
            assertFalse(sending.await())
            assertTrue(repo.snapshot().isEmpty())
            repo.openConversation("a")
            val failed = repo.snapshot().single()
            assertEquals("keep this message", failed.message)
            assertEquals(DeliveryState.FAILED, failed.deliveryState)
            fixture.failSend = false
            repo.openConversation("b")
            repo.retry(requireNotNull(failed.pendingId))
            assertTrue(repo.snapshot().isEmpty())
            repo.openConversation("a")
            assertEquals(DeliveryState.FAILED, repo.snapshot().single().deliveryState)
            repo.retry(requireNotNull(failed.pendingId))
            assertEquals(DeliveryState.SENT, repo.snapshot().single().deliveryState)
        }
    }

    @Test fun `successful send after navigation is reconciled once on returning`() = runBlocking {
        Fixture(coroutineContext).use { fixture ->
            val repo = fixture.repository
            repo.initialize(fixture.login)
            repo.openConversation("a")
            fixture.blockSend = true
            val sending = async { repo.send("keep this message") }
            withTimeout(5000) { fixture.sendStarted.await() }
            repo.openConversation("b")
            fixture.release.countDown()
            assertTrue(sending.await())
            assertTrue(repo.snapshot().isEmpty())
            repo.openConversation("a")
            assertEquals("sent", repo.snapshot().single().serverId)
        }
    }

    @Test fun `notification message outside latest page can be loaded in its destination`() = runBlocking {
        Fixture(coroutineContext).use { fixture ->
            val repo = fixture.repository
            repo.initialize(fixture.login)
            repo.openConversation("b")
            repo.openConversation("a")
            assertTrue(repo.snapshot().isEmpty())
            assertTrue(repo.loadMessageContext("a", "old"))
            assertEquals("old", repo.snapshot().single().serverId)
            assertFalse(repo.loadMessageContext("b", "old"))
        }
    }

    private class Fixture(context: CoroutineContext) : AutoCloseable {
        val login = ServerConfig("https://review.invalid", "team", "", "alice", "dummy")
        private val scope = CoroutineScope(context + SupervisorJob())
        val directoryStarted = CompletableDeferred<Unit>()
        val sendStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        @Volatile var blockDirectory = false
        @Volatile var blockSend = false
        @Volatile var failSend = false
        @Volatile private var sent: String? = null
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            var code = 200
            val body = when {
                path == "/api/v4/users/login" -> """{"id":"me","username":"alice"}"""
                path == "/api/v4/teams/name/team" -> """{"id":"team","name":"team","display_name":"Team"}"""
                path == "/api/v4/users/me/teams/team/channels" -> {
                    if (blockDirectory) {
                        directoryStarted.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    """[{"id":"a","name":"a","type":"O"},{"id":"b","name":"b","type":"O"},{"id":"self","name":"me__me","type":"D"}]"""
                }
                path == "/api/v4/posts" -> {
                    if (blockSend) {
                        sendStarted.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    if (failSend) {
                        code = 500
                        """{"message":"temporarily unavailable"}"""
                    } else {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        val pending = mattermostJson.parseToJsonElement(buffer.readUtf8()).jsonObject.string("pending_post_id")
                        post("sent", pending).also { sent = it }
                    }
                }
                path == "/api/v4/posts/old" -> post("old", "")
                path.endsWith("/posts") -> {
                    val saved = sent.takeIf { path == "/api/v4/channels/a/posts" }
                    if (saved == null) """{"order":[],"posts":{}}"""
                    else """{"order":["sent"],"posts":{"sent":$saved}}"""
                }
                path == "/api/v4/config/client" -> "{}"
                path.endsWith("/view") -> "{}"
                else -> "[]"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").header("Token", "test-token")
                .body(body.toResponseBody()).build()
        }.build()
        val repository = ChannelRepository(
            MattermostApi(client), MattermostSocket(client), scope,
            object : ConversationCache {
                override suspend fun load(key: ConversationSummaryCache.Key) = listOf(
                    ConversationSummary(
                        MattermostChannel("a", "a", "A", type = "O"), ConversationKind.CHANNEL, "A",
                    ),
                )
                override suspend fun save(key: ConversationSummaryCache.Key, conversations: Collection<ConversationSummary>) = Unit
            },
            "unsupported",
        )

        private fun post(id: String, pending: String) =
            """{"id":"$id","channel_id":"a","user_id":"me","message":"keep this message","create_at":100,"update_at":100,"pending_post_id":"$pending"}"""

        override fun close() {
            release.countDown()
            repository.close()
            scope.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
