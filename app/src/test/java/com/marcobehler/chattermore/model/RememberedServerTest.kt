package com.marcobehler.chattermore.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RememberedServerTest {
    @Test
    fun `remembered server populates only non-secret login fields`() {
        val config = RememberedServer(
            baseUrl = "https://mattermost.example.com",
            teamName = "engineering",
            username = "marco",
        ).asLoginConfig()

        assertEquals("https://mattermost.example.com", config.baseUrl)
        assertEquals("engineering", config.teamName)
        assertEquals("marco", config.username)
        assertEquals("", config.channelName)
        assertEquals("", config.password)
    }
}
