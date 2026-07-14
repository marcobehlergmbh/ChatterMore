package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkParserTest {
    @Test
    fun `parses standard short and shorts links`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=4")?.id,
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.fromUrl("https://youtu.be/dQw4w9WgXcQ")?.id,
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.fromUrl("https://m.youtube.com/shorts/dQw4w9WgXcQ")?.id,
        )
    }

    @Test
    fun `finds first youtube link among ordinary links`() {
        val video = YouTubeLinkParser.firstVideo(
            "Docs https://example.com then https://youtube.com/embed/dQw4w9WgXcQ.",
        )

        assertEquals("dQw4w9WgXcQ", video?.id)
    }

    @Test
    fun `rejects lookalike hosts and malformed ids`() {
        assertNull(YouTubeLinkParser.fromUrl("https://youtube.example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLinkParser.fromUrl("https://youtube.com/watch?v=too-short"))
    }
}
