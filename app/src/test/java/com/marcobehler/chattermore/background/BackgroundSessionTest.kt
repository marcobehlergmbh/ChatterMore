package com.marcobehler.chattermore.background

import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import com.marcobehler.chattermore.model.MattermostUser
import org.junit.Assert.*
import org.junit.Test

class BackgroundSessionTest {
    private val config = AuthenticatedServerConfig("https://example.com", "team", "Selected DM", "expired")
    private val user = MattermostUser("me", "alice")

    @Test fun `reauthentication preserves selected conversation and checkpoint`() {
        val previous = BackgroundPollingSession(config, "dm-id", "me", "old-name", "last-post", 123)
        val refreshed = previous.refreshed(config.copy(channelName = "", token = "fresh"), user)!!
        assertEquals("dm-id", refreshed.channelId)
        assertEquals("Selected DM", refreshed.config.channelName)
        assertEquals("last-post", refreshed.checkpointPostId)
        assertEquals(123L, refreshed.checkpointCreateAt)
        assertEquals("fresh", refreshed.config.token)
        assertEquals("alice", refreshed.currentUsername)
    }

    @Test fun `reauthentication and notification targets cannot cross accounts or servers`() {
        val previous = BackgroundPollingSession(config, "dm-id", "me", "alice")
        assertNull(previous.refreshed(config, user.copy(id = "other")))
        assertNull(previous.refreshed(config.copy(baseUrl = "https://elsewhere.example"), user))
        val target = NotificationTarget(config.baseUrl, user.id, "dm-id", "older-post")
        assertTrue(target.matches(config, user.id))
        assertFalse(target.matches(config, "other"))
        assertFalse(target.matches(config.copy(baseUrl = "https://elsewhere.example"), user.id))
        assertFalse(target.copy(channelId = "").matches(config, user.id))
    }
}
