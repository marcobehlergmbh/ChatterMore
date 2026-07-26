package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConversationKind
import com.example.mattermostlite.model.ConversationSummary
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostChannelMember
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDirectoryTest {
    @Test
    fun `builds channels and direct messages without per-conversation data`() {
        val channels = listOf(
            channel("town", "town-square", "Town Square", "O", total = 12, lastPostAt = 10),
            channel("private", "release", "Release", "P", total = 5, lastPostAt = 20),
            channel("dm", "me__alice", "", "D", total = 8, lastPostAt = 30),
            channel("group", "me__alice__bob", "", "G", total = 3, lastPostAt = 40),
            channel("deleted", "old", "Old", "O", deleteAt = 1),
        )
        val memberships = listOf(
            member("town", messageCount = 12),
            member("private", messageCount = 4),
            member("dm", messageCount = 8, mentionCount = 1),
        )

        val result = buildConversationDirectory(
            channels = channels,
            memberships = memberships,
            currentUserId = "me",
            users = listOf(MattermostUser("alice", "alice", "Alice", "A")),
        )

        assertEquals(listOf("private", "town", "dm"), result.map(ConversationSummary::id))
        assertEquals(
            listOf(ConversationKind.CHANNEL, ConversationKind.CHANNEL, ConversationKind.DIRECT_MESSAGE),
            result.map(ConversationSummary::kind),
        )
        assertEquals("Alice A", result.last().title)
        assertTrue(result.first().unread)
        assertFalse(result[1].unread)
        assertTrue(result.last().unread)
    }

    @Test
    fun `direct message peer extraction excludes the current user`() {
        assertEquals("alice", directMessagePeerId("me__alice", "me"))
        assertEquals("alice", directMessagePeerId("alice__me", "me"))
        assertEquals(null, directMessagePeerId("invalid", "me"))
    }

    @Test
    fun `inactive posts update one summary and only other users mark it unread`() {
        val summary = ConversationSummary(
            channel = channel("channel", "channel", "Channel", "O", lastPostAt = 10),
            kind = ConversationKind.CHANNEL,
            title = "Channel",
        )

        val own = applyInactivePost(summary, post("channel", "me", 20), "posted", "me")
        val other = applyInactivePost(own, post("channel", "alice", 30), "posted", "me")
        val edit = applyInactivePost(summary, post("channel", "alice", 40), "post_edited", "me")

        assertFalse(own.unread)
        assertEquals(20L, own.lastPostAt)
        assertTrue(other.unread)
        assertEquals(30L, other.lastPostAt)
        assertEquals(summary, edit)
    }

    private fun channel(
        id: String,
        name: String,
        displayName: String,
        type: String,
        total: Long = 0,
        lastPostAt: Long = 0,
        deleteAt: Long = 0,
    ) = MattermostChannel(
        id = id,
        name = name,
        displayName = displayName,
        teamId = "team",
        type = type,
        lastPostAt = lastPostAt,
        totalMessageCount = total,
        deleteAt = deleteAt,
    )

    private fun member(
        channelId: String,
        messageCount: Long,
        mentionCount: Long = 0,
    ) = MattermostChannelMember(
        channelId = channelId,
        userId = "me",
        messageCount = messageCount,
        mentionCount = mentionCount,
        lastViewedAt = 0,
    )

    private fun post(channelId: String, userId: String, createAt: Long) = MattermostPost(
        id = "post-$createAt",
        channelId = channelId,
        userId = userId,
        message = "message",
        createAt = createAt,
        updateAt = createAt,
        deleteAt = 0,
        rootId = "",
        type = "",
        pendingPostId = "",
    )
}
