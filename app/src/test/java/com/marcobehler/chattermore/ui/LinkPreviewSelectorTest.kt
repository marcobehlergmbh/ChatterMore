package com.marcobehler.chattermore.ui

import com.marcobehler.chattermore.model.LinkPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewSelectorTest {
    @Test
    fun `specialized links do not get a duplicate generic preview`() {
        val previews = listOf(
            LinkPreview("https://youtu.be/dQw4w9WgXcQ", title = "Video"),
            LinkPreview(
                "https://suno.com/song/e7d772da-2378-40c8-9872-90c2b8205ad7",
                title = "Song",
            ),
        )

        assertNull(LinkPreviewSelector.firstGeneric(previews))
    }

    @Test
    fun `selects at most the first ordinary preview from a mixed message`() {
        val first = LinkPreview("https://example.com/first", title = "First")
        val second = LinkPreview("https://example.com/second", title = "Second")

        assertEquals(
            first,
            LinkPreviewSelector.firstGeneric(
                listOf(LinkPreview("https://youtube.com/watch?v=dQw4w9WgXcQ"), first, second),
            ),
        )
    }
}
