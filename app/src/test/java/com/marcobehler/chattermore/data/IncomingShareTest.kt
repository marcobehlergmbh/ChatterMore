package com.marcobehler.chattermore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareTest {
    @Test
    fun `incoming share cannot be applied to the restored conversation before selection`() {
        val share = IncomingShare(attachments = emptyList(), text = "Shared caption")

        assertFalse(share.isReadyFor("wd-exile"))
        assertFalse(share.isReadyFor(null))
    }

    @Test
    fun `selected share waits for its destination and cannot follow unrelated navigation`() {
        val share = IncomingShare(
            attachments = emptyList(),
            text = "Shared caption",
            destinationChannelId = "chosen-dm",
        )

        assertFalse(share.isReadyFor(null))
        assertFalse(share.isReadyFor("loading:chosen-dm:1"))
        assertFalse(share.isReadyFor("other-channel"))
        assertTrue(share.isReadyFor("chosen-dm"))
    }

    @Test
    fun `single share accepts stream and caption`() {
        assertEquals(
            NormalizedIncomingShare(
                uris = listOf("content://screenshots/latest"),
                text = "Screenshot caption",
            ),
            normalizeIncomingShare(
                action = "android.intent.action.SEND",
                extraStreams = listOf("content://screenshots/latest"),
                clipStreams = emptyList(),
                text = "  Screenshot caption  ",
            ),
        )
    }

    @Test
    fun `multiple share combines extra and clip streams without duplicates`() {
        assertEquals(
            NormalizedIncomingShare(
                uris = listOf(
                    "content://gallery/one",
                    "content://gallery/two",
                    "file:///sdcard/Download/report.pdf",
                    "android.resource://other.app/raw/item",
                ),
                text = "",
            ),
            normalizeIncomingShare(
                action = "android.intent.action.SEND_MULTIPLE",
                extraStreams = listOf(
                    "content://gallery/one",
                    "content://gallery/two",
                ),
                clipStreams = listOf(
                    "content://gallery/two",
                    "https://example.com/not-a-stream",
                    "file:///sdcard/Download/report.pdf",
                    "android.resource://other.app/raw/item",
                ),
                text = null,
            ),
        )
    }

    @Test
    fun `text-only share is accepted and malformed or empty shares are ignored`() {
        assertEquals(
            NormalizedIncomingShare(emptyList(), "Shared text"),
            normalizeIncomingShare(
                action = "android.intent.action.SEND",
                extraStreams = emptyList(),
                clipStreams = emptyList(),
                text = "Shared text",
            ),
        )
        assertEquals(
            null,
            normalizeIncomingShare(
                action = "android.intent.action.SEND",
                extraStreams = listOf("https://example.com/not-a-stream"),
                clipStreams = listOf(null, " "),
                text = " ",
            ),
        )
        assertEquals(
            null,
            normalizeIncomingShare(
                action = "android.intent.action.VIEW",
                extraStreams = listOf("content://gallery/one"),
                clipStreams = emptyList(),
                text = null,
            ),
        )
    }

    @Test
    fun `shared text is appended without adding empty separators`() {
        assertEquals("Existing\nShared caption", combineSharedDraftText("Existing", "Shared caption"))
        assertEquals("Existing", combineSharedDraftText("Existing", ""))
        assertEquals("Shared caption", combineSharedDraftText("", "Shared caption"))
    }
}
