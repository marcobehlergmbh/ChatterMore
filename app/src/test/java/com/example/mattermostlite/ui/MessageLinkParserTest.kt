package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `renders the Mattermost text smiley families as Unicode`() {
        val examples = mapOf(
            ":)" to "🙂",
            ";-)" to "😉",
            ":o" to "😮",
            ":-O" to "😱",
            ":]" to "😏",
            ":D" to "😄",
            "X-D" to "😝",
            ":p" to "😛",
            ":@" to "😡",
            ":(" to "🙁",
            ":'-(" to "😢",
            ":/" to "😕",
            ":S" to "😖",
            ":|" to "😐",
            ":\$" to "😳",
            ":-x" to "😷",
            "<3" to "❤️",
            "</3" to "💔",
        )

        examples.forEach { (source, expected) ->
            assertEquals(source, expected, MessageLinkParser.format(source)?.text)
        }
    }

    @Test
    fun `formats smileys and links in one scan while retaining shifted link ranges`() {
        val formatted = MessageLinkParser.format(":-D https://example.com/:/ then :)")!!

        assertEquals("😄 https://example.com/:/ then 🙂", formatted.text)
        val link = formatted.links.single()
        assertEquals("https://example.com/:/", formatted.text.substring(link.start, link.end))
        assertEquals("https://example.com/:/", link.target)
    }

    @Test
    fun `leaves smileys in URLs and backtick code unchanged`() {
        val formatted = MessageLinkParser.format(
            "https://example.com/:/ `:)` ```\n:(\n``` outside :D",
        )!!

        assertEquals(
            "https://example.com/:/ `:)` ```\n:(\n``` outside 😄",
            formatted.text,
        )
        assertEquals("https://example.com/:/", formatted.links.single().target)
    }

    @Test
    fun `escaped smileys display literally without converting`() {
        assertEquals(":) and \\:) and 🙂", MessageLinkParser.format("\\:) and \\\\:) and :)")?.text)
    }

    @Test
    fun `requires token boundaries and leaves named aliases alone`() {
        val text = "word:) 8:) :smile: already 🙂"

        assertNull(MessageLinkParser.format(text))
        assertSame(text, MessageLinkParser.parse(text))
    }

    @Test
    fun `renders multiple smileys without creating link spans`() {
        val formatted = MessageLinkParser.format("Hi :) welcome ;)")

        assertTrue(formatted != null)
        assertEquals("Hi 🙂 welcome 😉", formatted?.text)
        assertTrue(formatted?.links?.isEmpty() == true)
    }
}
