package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageLinkParserTest {
    @Test
    fun `plain text takes the allocation-free fast path`() {
        val message = "A message without links"

        assertSame(message, MessageLinkParser.parse(message))
    }

    @Test
    fun `parses web links and excludes sentence punctuation`() {
        val message = "See https://example.com/docs?q=fast, then http://internal:8065/path."
        val links = MessageLinkParser.findLinks(message)

        assertEquals(listOf("https://example.com/docs?q=fast", "http://internal:8065/path"), links.map { it.target })
        assertEquals(listOf("https://example.com/docs?q=fast", "http://internal:8065/path"), links.map {
            message.substring(it.start, it.end)
        })
    }

    @Test
    fun `normalizes boundary-safe www links`() {
        val links = MessageLinkParser.findLinks("Open (www.example.com). notwww.example.com")

        assertEquals(listOf("https://www.example.com"), links.map { it.target })
    }

    @Test
    fun `keeps balanced parentheses inside a link`() {
        val links = MessageLinkParser.findLinks("https://example.com/wiki/Foo_(bar)")

        assertEquals(
            "https://example.com/wiki/Foo_(bar)",
            links.single().target,
        )
    }
}
