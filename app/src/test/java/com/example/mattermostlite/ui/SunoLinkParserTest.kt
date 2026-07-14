package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SunoLinkParserTest {
    @Test
    fun `recognizes song share and embed links`() {
        val id = "e7d772da-2378-40c8-9872-90c2b8205ad7"

        assertEquals(id, SunoLinkParser.fromUrl("https://suno.com/song/$id")?.songId)
        assertEquals(id, SunoLinkParser.fromUrl("https://suno.ai/embed/$id")?.songId)
        assertEquals(null, SunoLinkParser.fromUrl("https://suno.com/s/share-code")?.songId)
    }

    @Test
    fun `finds suno link among regular links`() {
        val song = SunoLinkParser.firstSong(
            "Docs https://example.com then https://suno.com/s/share-code.",
        )

        assertEquals("https://suno.com/s/share-code", song?.pageUrl)
    }

    @Test
    fun `rejects insecure and lookalike hosts`() {
        assertNull(SunoLinkParser.fromUrl("http://suno.com/song/e7d772da-2378-40c8-9872-90c2b8205ad7"))
        assertNull(SunoLinkParser.fromUrl("https://suno.example.com/s/share-code"))
    }
}
