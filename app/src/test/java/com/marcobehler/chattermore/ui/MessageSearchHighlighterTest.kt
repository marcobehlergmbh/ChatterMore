package com.marcobehler.chattermore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchHighlighterTest {
    @Test
    fun `finds case-insensitive non-overlapping matches`() {
        val text = "Release notes for the release"

        val ranges = MessageSearchHighlighter.ranges(text, listOf("release", "notes"))

        assertEquals(
            listOf("Release", "notes", "release"),
            ranges.map { text.substring(it.start, it.end) },
        )
    }

    @Test
    fun `prefers longer overlapping terms`() {
        val text = "release notes"
        val ranges = MessageSearchHighlighter.ranges(text, listOf("release", "release notes"))

        assertEquals(listOf("release notes"), ranges.map { text.substring(it.start, it.end) })
    }

    @Test
    fun `empty inputs use allocation-light empty result`() {
        assertTrue(MessageSearchHighlighter.ranges("", listOf("release")).isEmpty())
        assertTrue(MessageSearchHighlighter.ranges("release", emptyList()).isEmpty())
    }
}
