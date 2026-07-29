package com.marcobehler.chattermore.ui

import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MessageGroupingTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `groups consecutive messages from the same user within three minutes`() {
        assertTrue(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 1),
                item(userId = "user", createAt = 180_001),
                utc,
            ),
        )
    }

    @Test
    fun `starts a new group for another user or after three minutes`() {
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 119_999),
                item(userId = "other", createAt = 119_999),
                utc,
            ),
        )
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 1),
                item(userId = "user", createAt = 180_002),
                utc,
            ),
        )
    }

    @Test
    fun `does not group messages whose timestamps move backwards`() {
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 2_000),
                item(userId = "user", createAt = 1_999),
                utc,
            ),
        )
    }

    @Test
    fun `groups across fixed clock buckets when elapsed time is short`() {
        assertTrue(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 179_999),
                item(userId = "user", createAt = 180_001),
                utc,
            ),
        )
    }

    @Test
    fun `does not group messages across a local midnight`() {
        assertFalse(
            MessageGrouping.isContinuation(
                item(userId = "user", createAt = 86_399_000),
                item(userId = "user", createAt = 86_401_000),
                utc,
            ),
        )
    }

    @Test
    fun `local day follows the supplied time zone`() {
        val timestamp = 23 * 60 * 60 * 1_000L
        assertTrue(
            MessageGrouping.localDay(timestamp, ZoneId.of("Europe/Berlin")) >
                MessageGrouping.localDay(timestamp, utc),
        )
    }

    @Test
    fun `day divider starts the feed and each new local day`() {
        assertTrue(MessageGrouping.startsNewDay(previousDay = null, currentDay = 10))
        assertFalse(MessageGrouping.startsNewDay(previousDay = 10, currentDay = 10))
        assertTrue(MessageGrouping.startsNewDay(previousDay = 10, currentDay = 11))
    }

    @Test
    fun `first message always starts a group`() {
        assertFalse(MessageGrouping.isContinuation(null, item("user", 1), utc))
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
