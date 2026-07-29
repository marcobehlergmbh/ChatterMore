package com.marcobehler.chattermore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadFileNameTest {
    @Test
    fun `removes path traversal and control characters`() {
        val name = DownloadFileName.sanitize("../folder\\report\n.pdf", "file-id")

        assertEquals("_folder_report_.pdf", name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
    }

    @Test
    fun `uses fallback for an unusable filename`() {
        assertEquals("file-id", DownloadFileName.sanitize("...", "file-id"))
    }
}
