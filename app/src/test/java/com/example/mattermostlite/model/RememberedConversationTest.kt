package com.example.mattermostlite.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberedConversationTest {
    private val remembered = RememberedConversation(
        baseUrl = "https://mattermost.example.com",
        teamId = "team-1",
        userId = "user-1",
        channelId = "channel-1",
    )

    @Test
    fun `returns channel for the same server team and user`() {
        assertEquals(
            "channel-1",
            remembered.channelIdFor(
                baseUrl = "https://mattermost.example.com",
                teamId = "team-1",
                userId = "user-1",
            ),
        )
    }

    @Test
    fun `does not return channel for another account scope`() {
        assertNull(
            remembered.channelIdFor(
                baseUrl = "https://other.example.com",
                teamId = "team-1",
                userId = "user-1",
            ),
        )
        assertNull(
            remembered.channelIdFor(
                baseUrl = "https://mattermost.example.com",
                teamId = "team-2",
                userId = "user-1",
            ),
        )
        assertNull(
            remembered.channelIdFor(
                baseUrl = "https://mattermost.example.com",
                teamId = "team-1",
                userId = "user-2",
            ),
        )
    }
}
