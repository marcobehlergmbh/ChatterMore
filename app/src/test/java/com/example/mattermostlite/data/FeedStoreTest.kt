package com.example.mattermostlite.data

import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreTest {
    private val user = MattermostUser("user-1", "marco", "Marco", "Behler")

    @Test
    fun `orders posts and exposes seek cursors`() {
        val store = FeedStore("Unsupported")
        store.replace(
            posts = listOf(post("new", 300), post("old", 100), post("middle", 200)),
            profiles = listOf(user),
        )

        assertEquals(listOf("old", "middle", "new"), store.snapshot().map { it.serverId })
        assertEquals("old", store.oldestServerId())
        assertEquals("new", store.newestServerId())
    }

    @Test
    fun `reconciles websocket or rest acknowledgement by pending id`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)
        assertEquals(DeliveryState.SENDING, store.snapshot().single().deliveryState)

        val mutations = store.merge(
            listOf(post("server-1", 100, pendingId = "client-1")),
            listOf(user),
        )

        assertEquals(1, store.snapshot().size)
        assertEquals("server-1", store.snapshot().single().serverId)
        assertEquals(DeliveryState.SENT, store.snapshot().single().deliveryState)
        assertTrue(mutations.single() is FeedMutation.Update)
        assertTrue(store.merge(listOf(post("server-1", 100, pendingId = "client-1"))).isEmpty())
    }

    @Test
    fun `filters replies and removes deleted posts`() {
        val store = FeedStore("Unsupported")
        store.replace(
            listOf(post("root", 100), post("reply", 200, rootId = "root")),
            listOf(user),
        )
        assertEquals(listOf("root"), store.snapshot().map { it.serverId })

        val mutations = store.merge(listOf(post("root", 100, deleteAt = 300)))
        assertTrue(mutations.single() is FeedMutation.Remove)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `failed pending post remains retryable`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)
        store.setPendingState("client-1", DeliveryState.FAILED)

        assertEquals(DeliveryState.FAILED, store.pending("client-1")?.deliveryState)
        assertEquals("hello", store.pending("client-1")?.message)
        assertNull(store.pending("missing"))
    }

    @Test
    fun `bounds resident items while preserving newest`() {
        val store = FeedStore("Unsupported", maxItems = 3)
        store.replace((1..5).map { post("post-$it", it.toLong()) }, listOf(user))

        assertEquals(listOf("post-3", "post-4", "post-5"), store.snapshot().map { it.serverId })
        assertFalse(store.snapshot().any { it.serverId == "post-1" })
    }

    @Test
    fun `older pagination slides bounded window toward history`() {
        val store = FeedStore("Unsupported", maxItems = 3)
        store.replace((3..5).map { post("post-$it", it.toLong()) }, listOf(user))

        val mutations = store.mergeOlder((1..2).map { post("post-$it", it.toLong()) })

        assertTrue(mutations.single() is FeedMutation.Replace)
        assertEquals(listOf("post-1", "post-2", "post-3"), store.snapshot().map { it.serverId })
        assertEquals("post-1", store.oldestServerId())
    }

    @Test
    fun `older page is emitted as one prepend mutation`() {
        val store = FeedStore("Unsupported", maxItems = 10)
        store.replace((4..6).map { post("post-$it", it.toLong()) }, listOf(user))

        val mutations = store.mergeOlder((1..3).map { post("post-$it", it.toLong()) })

        val prepend = mutations.single() as FeedMutation.Prepend
        assertEquals(listOf("post-1", "post-2", "post-3"), prepend.items.map { it.serverId })
        assertEquals((1..6).map { "post-$it" }, store.snapshot().map { it.serverId })
    }

    @Test
    fun `pending insert keeps adapter and bounded store in sync`() {
        val store = FeedStore("Unsupported", maxItems = 2)
        store.replace(listOf(post("post-1", 1), post("post-2", 2)), listOf(user))

        val mutation = store.addPending("client-1", "channel", user, "hello", 3)

        assertTrue(mutation is FeedMutation.Replace)
        assertEquals(listOf("post-2", null), store.snapshot().map { it.serverId })
        assertEquals("client-1", store.snapshot().last().pendingId)
    }

    private fun post(
        id: String,
        createAt: Long,
        pendingId: String = "",
        rootId: String = "",
        deleteAt: Long = 0,
    ) = MattermostPost(
        id = id,
        channelId = "channel",
        userId = user.id,
        message = "message-$id",
        createAt = createAt,
        updateAt = createAt,
        deleteAt = deleteAt,
        rootId = rootId,
        type = "",
        pendingPostId = pendingId,
    )
}
