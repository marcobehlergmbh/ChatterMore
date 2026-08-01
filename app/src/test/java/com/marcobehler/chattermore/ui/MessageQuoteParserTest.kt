package com.marcobehler.chattermore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageQuoteParserTest {
    @Test
    fun `plain and inline greater-than text is unchanged`() {
        assertNull(MessageQuoteParser.parse("A plain message"))
        assertNull(MessageQuoteParser.parse("The result is 5 > 3"))
    }

    @Test
    fun `removes a quote marker and exposes the displayed range`() {
        val parsed = MessageQuoteParser.parse("> A focused release is easier to review.")!!

        assertEquals("A focused release is easier to review.", parsed.text)
        assertEquals(
            listOf(MessageTextRange(0, parsed.text.length)),
            parsed.quoteRanges,
        )
        assertEquals(listOf(MessageTextRange(0, 2)), parsed.markerRanges)
    }

    @Test
    fun `groups consecutive quote lines into one visual block`() {
        val parsed = MessageQuoteParser.parse(
            "Intro\n> First point\n> Second point\nOutro",
        )!!

        assertEquals(
            "Intro\nFirst point\nSecond point\nOutro",
            parsed.text,
        )
        assertEquals(
            listOf(MessageTextRange(6, 31)),
            parsed.quoteRanges,
        )
    }

    @Test
    fun `keeps separated quotes as separate visual blocks`() {
        val parsed = MessageQuoteParser.parse(
            "> First\n\nBetween\n\n> Second",
        )!!

        assertEquals("First\n\nBetween\n\nSecond", parsed.text)
        assertEquals(
            listOf(
                MessageTextRange(0, 6),
                MessageTextRange(16, 22),
            ),
            parsed.quoteRanges,
        )
    }

    @Test
    fun `accepts markdown indentation and repeated quote markers`() {
        val parsed = MessageQuoteParser.parse("   >>> Nested or multiline-style quote")!!

        assertEquals("Nested or multiline-style quote", parsed.text)
        assertEquals(listOf(MessageTextRange(0, 7)), parsed.markerRanges)
    }

    @Test
    fun `does not treat four-space-indented code as a quote`() {
        assertNull(MessageQuoteParser.parse("    > literal code"))
    }
}
