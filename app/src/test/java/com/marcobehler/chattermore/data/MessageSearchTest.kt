package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.MattermostChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchTest {
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
}
