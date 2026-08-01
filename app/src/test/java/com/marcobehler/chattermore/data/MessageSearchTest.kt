package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchTest {
    @Test
    fun `targets a top-level result directly and a reply through its visible root`() {
        val topLevel = result(postId = "post", rootId = "")
        val reply = result(postId = "reply", rootId = "root")

        assertEquals("post", MessageSearch.targetPostId(topLevel))
        assertEquals("root", MessageSearch.targetPostId(reply))
    }

    @Test
    fun `requires three searchable characters`() {
        assertFalse(MessageSearch.isSearchable("  go "))
        assertTrue(MessageSearch.isSearchable(" API "))
    }

    @Test
    fun `scopes regular channels by stable name and direct messages by id`() {
        val channel = MattermostChannel(
            id = "channel-id",
            name = "product-launch",
            displayName = "Product Launch",
            type = "O",
        )
        val direct = channel.copy(id = "direct-id", name = "user-a__user-b", type = "D")

        assertEquals("release in:product-launch", MessageSearch.scopedTerms(" release ", channel))
        assertEquals("release in:direct-id", MessageSearch.scopedTerms("release", direct))
    }

    @Test
    fun `uses server matches for highlighting and falls back to query words`() {
        assertEquals(
            listOf("release", "notes"),
            MessageSearch.highlightTerms("ignored query", listOf("Release", "notes")),
        )
        assertEquals(
            listOf("release", "notes"),
            MessageSearch.highlightTerms("\"release\" notes from:marco"),
        )
    }

    @Test
    fun `local provisional matching requires every visible query term`() {
        val terms = MessageSearch.highlightTerms("release notes")

        assertTrue(MessageSearch.locallyMatches("The RELEASE notes are ready", terms))
        assertFalse(MessageSearch.locallyMatches("The release is ready", terms))
    }

    private fun result(postId: String, rootId: String) = MessageSearchResult(
        postId = postId,
        channelId = "channel",
        rootId = rootId,
        userId = "user",
        author = "User",
        message = "message",
        createAt = 1L,
        fileCount = 0,
        matchTerms = emptyList(),
    )
}
