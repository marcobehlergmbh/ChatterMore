package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageScrollAnchorTest {
    private val anchor = MessageScrollAnchor(
        itemKey = "post:visible",
        fallbackPosition = 42,
        topOffset = -18,
    )

    @Test
    fun `stable message key wins after the feed changes`() {
        assertEquals(57, anchor.resolvePosition(itemCount = 100, keyedPosition = 57))
    }

    @Test
    fun `removed message falls back to a clamped adapter position`() {
        assertEquals(19, anchor.resolvePosition(itemCount = 20, keyedPosition = null))
    }

    @Test
    fun `empty feed has no restoration position`() {
        assertNull(anchor.resolvePosition(itemCount = 0, keyedPosition = null))
    }
}
