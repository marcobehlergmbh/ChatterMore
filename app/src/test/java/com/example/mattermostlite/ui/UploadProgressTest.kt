package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadProgressTest {
    @Test
    fun `upload percentage reserves one hundred for server confirmation`() {
        assertEquals(0, uploadPercent(0L, 1_000L))
        assertEquals(50, uploadPercent(500L, 1_000L))
        assertEquals(99, uploadPercent(1_000L, 1_000L))
        assertEquals(99, uploadPercent(1_100L, 1_000L))
    }

    @Test
    fun `upload percentage is indeterminate without a positive total`() {
        assertNull(uploadPercent(100L, null))
        assertNull(uploadPercent(100L, -1L))
        assertNull(uploadPercent(0L, 0L))
    }
}
