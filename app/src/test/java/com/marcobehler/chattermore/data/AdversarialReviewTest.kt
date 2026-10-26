package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.*
import com.marcobehler.chattermore.network.MattermostApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AdversarialReviewTest {
    private val user = MattermostUser("u", "alice")
    private fun post(id: String = "p", at: Long = 100, updated: Long = at) = MattermostPost(
        id = id, channelId = "c", userId = "u", message = "original",
        createAt = at, updateAt = updated, deleteAt = 0, rootId = "", type = "", pendingPostId = "",
    )

    @Test fun rejectPrivateFileShare() {
        assertTrue(normalizeSharedUriStrings(listOf(
            "file:///data/user/0/com.marcobehler.chattermore/shared_prefs/conversation_summaries.xml"
        )).isEmpty())
    }

    @Test fun staleRestResponseMustNotUndoSocketEdit() {
        val store = FeedStore("unsupported")
        store.replace(listOf(post()), listOf(user))
        store.merge(listOf(post(updated = 200).copy(message = "edited")))
        store.merge(listOf(post()))
        assertEquals("edited", store.snapshot().single().message)
    }

    @Test fun staleRestResponseMustNotResurrectDeletedPost() {
        val store = FeedStore("unsupported")
        store.replace(listOf(post()), listOf(user))
        store.merge(listOf(post(updated = 200).copy(deleteAt = 200)))
        store.merge(listOf(post()))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun acknowledgementMustReorderUsingServerTimestamp() {
        val store = FeedStore("unsupported")
        store.replace(listOf(post("a", 100), post("b", 200)), listOf(user))
        store.addPending("pending", "c", user, "hello", 50)
        store.merge(listOf(post("ack", 300).copy(pendingPostId = "pending")))
        assertEquals(listOf(100L, 200L, 300L), store.snapshot().map { it.createAt })
        assertEquals("ack", store.newestServerId())
    }

    @Test fun truncatedBodyMustCompleteWithIOException() = runBlocking {
        val readAttempted = AtomicBoolean(false)
        val brokenBody = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            private val brokenSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readAttempted.set(true)
                    throw IOException("simulated truncated response")
                }
                override fun timeout() = Timeout.NONE
                override fun close() {}
            }.buffer()
            override fun source(): BufferedSource = brokenSource
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(brokenBody).build()
        }.build()
        try {
            val completed = withTimeoutOrNull(1500) {
                try {
                    MattermostApi(client).post(AuthenticatedServerConfig("https://review.invalid", "t", "", "dummy"), "p")
                    "unexpected success"
                } catch (_: IOException) {
                    "io failure"
                }
            }
            assertTrue("Response body must have been read", readAttempted.get())
            assertEquals("io failure", completed)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
