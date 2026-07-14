package com.example.mattermostlite.ui

import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGroupingTest {
    @Test
    fun `groups consecutive messages from the same user in the same minute`() {
        assertTrue(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 60_001),
                item(userId = "user", createAt = 119_999),
            ),
        )
    }

    @Test
    fun `starts a new group for another user or minute`() {
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 119_999),
                item(userId = "other", createAt = 119_999),
            ),
        )
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 119_999),
                item(userId = "user", createAt = 120_000),
            ),
        )
    }

    @Test
    fun `first message always starts a group`() {
        assertFalse(MessageGrouping.isContinuation(null, item("user", 1)))
    }

    private fun item(userId: String, createAt: Long) = FeedItem(
        key = "$userId:$createAt",
        serverId = "$userId:$createAt",
        pendingId = null,
        channelId = "channel",
        userId = userId,
        author = userId,
        message = "message",
        createAt = createAt,
        updateAt = createAt,
        deliveryState = DeliveryState.SENT,
    )
}
