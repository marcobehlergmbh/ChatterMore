package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSnapshotTest {
    @Test
    fun `round trips bounded visible feed metadata`() {
        val snapshot = snapshot(
            items = listOf(
                item("post-1").copy(
                    fileIds = listOf("image-1", "document-1"),
                    images = listOf(FeedImage("image-1", "photo.jpg", 1200, 800, true)),
                    files = listOf(FeedFile("document-1", "notes.pdf", "application/pdf", 1234)),
                    linkPreviews = listOf(
                        LinkPreview(
                            url = "https://example.com",
                            title = "Example",
                            description = "A preview",
                            siteName = "Example",
                            imageUrl = "https://example.com/preview.jpg",
                        ),
                    ),
                    editAt = 200,
                ),
            ),
        )

        val decoded = ConversationSnapshotCodec.decode(
            ConversationSnapshotCodec.encode(snapshot),
        )

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `drops pending items and retains only newest one hundred sent rows`() {
        val sent = (1..120).map { item("post-$it", it.toLong()) }
        val pending = item("pending").copy(
            serverId = null,
            pendingId = "pending",
            deliveryState = DeliveryState.OPTIMISTIC,
        )

        val decoded = ConversationSnapshotCodec.decode(
            ConversationSnapshotCodec.encode(snapshot(sent + pending)),
        )!!

        assertEquals(ConversationSnapshotCodec.MAX_ITEMS, decoded.items.size)
        assertEquals("post-21", decoded.items.first().serverId)
        assertEquals("post-120", decoded.items.last().serverId)
    }

    @Test
    fun `shrinks oldest rows until encrypted payload budget is met`() {
        val largeItems = (1..20).map {
            item("post-$it").copy(message = "x".repeat(2_000))
        }

        val encoded = ConversationSnapshotCodec.encodeBounded(
            snapshot(largeItems),
            maxBytes = 12_000,
        )!!
        val decoded = ConversationSnapshotCodec.decode(encoded)!!

        assertTrue(encoded.toByteArray().size <= 12_000)
        assertTrue(decoded.items.size < largeItems.size)
        assertEquals("post-20", decoded.items.last().serverId)
    }

    @Test
    fun `rejects corrupt data and isolates snapshot by account`() {
        val snapshot = snapshot(listOf(item("post-1")))

        assertNull(ConversationSnapshotCodec.decode("""{"version":999}"""))
        assertFalse(
            snapshot.matches(
                ServerConfig(
                    baseUrl = snapshot.baseUrl,
                    teamName = snapshot.teamName,
                    channelName = "",
                    username = "other-user",
                    password = "",
                ),
            ),
        )
        assertTrue(
            snapshot.matches(
                ServerConfig(
                    baseUrl = "${snapshot.baseUrl}/",
                    teamName = snapshot.teamName.uppercase(),
                    channelName = "",
                    username = snapshot.username.uppercase(),
                    password = "",
                ),
            ),
        )
    }

    private fun snapshot(items: List<FeedItem>) = ConversationSnapshot(
        baseUrl = "https://mattermost.example.com",
        teamName = "engineering",
        username = "marco",
        teamId = "team-1",
        userId = "user-1",
        channelId = "channel-1",
        title = "Town Square",
        peerUserId = null,
        items = items,
        savedAt = 123,
    )

    private fun item(id: String, createAt: Long = 100) = FeedItem(
        key = "post:$id",
        serverId = id,
        pendingId = null,
        channelId = "channel-1",
        userId = "user-1",
        author = "Marco Behler",
        message = "message-$id",
        createAt = createAt,
        updateAt = createAt,
        deliveryState = DeliveryState.SENT,
    )
}
