package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.*
import org.junit.Assert.*
import org.junit.Test

class OutboxTest {
    private val user = MattermostUser("me", "alice")
    private fun acknowledgement() = MattermostPost(
        "server", "a", user.id, "unsent", 100, 100, 0, "", "", "pending",
    )

    @Test fun `failed sends survive empty channel switches and page replacements`() {
        val store = FeedStore("unsupported")
        store.addPending("pending", "a", user, "unsent", 10)
        store.setPendingState("pending", DeliveryState.FAILED)
        store.replace(emptyList(), emptyList(), "b")
        assertTrue(store.snapshot().isEmpty())
        store.replace(emptyList(), emptyList(), "a")
        assertEquals("unsent", store.snapshot().single().message)
        assertEquals(DeliveryState.FAILED, store.snapshot().single().deliveryState)
    }

    @Test fun `send completion while another channel is open removes outbox entry without polluting feed`() {
        val store = FeedStore("unsupported")
        store.addPending("pending", "a", user, "unsent", 10)
        store.replace(emptyList(), emptyList(), "b")
        assertTrue(store.acknowledge(acknowledgement(), listOf(user)).isEmpty())
        assertTrue(store.snapshot().isEmpty())
        assertNull(store.pending("pending"))
        store.replace(listOf(acknowledgement()), listOf(user), "a")
        assertEquals(DeliveryState.SENT, store.snapshot().single().deliveryState)
    }

    @Test fun `send failure after navigation remains retryable and cannot cross account reset`() {
        val store = FeedStore("unsupported")
        store.addPending("pending", "a", user, "unsent", 10)
        store.replace(emptyList(), emptyList(), "b")
        assertNull(store.setPendingState("pending", DeliveryState.FAILED))
        store.replace(emptyList(), emptyList(), "a")
        assertEquals(DeliveryState.FAILED, store.snapshot().single().deliveryState)
        store.clear()
        store.replace(emptyList(), emptyList(), "a")
        assertTrue(store.snapshot().isEmpty())
        assertNull(store.pending("pending"))
    }

    @Test fun `another author cannot acknowledge a pending message`() {
        val store = FeedStore("unsupported")
        store.addPending("pending", "a", user, "unsent", 10)
        store.replace(emptyList(), emptyList(), "b")
        store.acknowledge(acknowledgement().copy(userId = "other"), emptyList())
        assertNotNull(store.pending("pending"))
    }

    @Test fun `deletion remains effective across channel switches and cached page replacement`() {
        val store = FeedStore("unsupported")
        val post = acknowledgement().copy(pendingPostId = "")
        store.replace(listOf(post), listOf(user), "a")
        store.replace(emptyList(), emptyList(), "b")
        store.acknowledge(post.copy(deleteAt = 200, updateAt = 200), emptyList())
        store.replace(listOf(post), listOf(user), "a")
        assertTrue(store.snapshot().isEmpty())
    }
}
