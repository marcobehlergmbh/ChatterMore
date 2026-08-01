package com.marcobehler.chattermore

import com.marcobehler.chattermore.model.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class BenchmarkFixturesTest {
    private val now = 1_800_000_000_000L
    private val storeModes = listOf(
        BenchmarkFixtures.MODE_OVERVIEW,
        BenchmarkFixtures.MODE_CHANNEL,
        BenchmarkFixtures.MODE_DIRECT_MESSAGE,
        BenchmarkFixtures.MODE_ATTACHMENTS,
        BenchmarkFixtures.MODE_SETTINGS,
    )

    @Test
    fun `store modes provide the intended app screens`() {
        assertTrue(fixture(BenchmarkFixtures.MODE_OVERVIEW).screen is ScreenState.Overview)
        assertTrue(fixture(BenchmarkFixtures.MODE_CHANNEL).screen is ScreenState.Conversation)
        assertTrue(fixture(BenchmarkFixtures.MODE_DIRECT_MESSAGE).screen is ScreenState.Conversation)
        assertTrue(fixture(BenchmarkFixtures.MODE_ATTACHMENTS).items.any { it.files.isNotEmpty() })
        assertTrue(fixture(BenchmarkFixtures.MODE_SETTINGS).backgroundSettings.enabled)
        assertTrue(fixture(BenchmarkFixtures.MODE_QUOTES).items.any { it.message.startsWith(">") })
    }

    @Test
    fun `store fixture urls use only reserved example domain`() {
        storeModes
            .flatMap { fixture(it).items }
            .flatMap { it.visibleUrls() }
            .forEach { url ->
                assertEquals("example.com", URI(url).host)
            }
    }

    @Test
    fun `store fixture text contains no emails ips or credential fields`() {
        val visibleText = storeModes.flatMap { mode ->
            val fixture = fixture(mode)
            buildList {
                when (val screen = fixture.screen) {
                    is ScreenState.Overview -> {
                        add(screen.teamTitle)
                        addAll(screen.conversations.map { it.title })
                    }
                    is ScreenState.Conversation -> add(screen.title)
                    else -> Unit
                }
                addAll(fixture.users.flatMap { listOf(it.username, it.displayName) })
                fixture.items.forEach { item ->
                    add(item.author)
                    add(item.message)
                    addAll(item.files.map { it.name })
                    item.linkPreviews.forEach { preview ->
                        add(preview.title)
                        add(preview.description)
                        add(preview.siteName)
                    }
                }
            }
        }.joinToString("\n")

        assertFalse(EMAIL.containsMatchIn(visibleText))
        assertFalse(IPV4.containsMatchIn(visibleText))
        assertFalse(CREDENTIAL_FIELD.containsMatchIn(visibleText))
    }

    private fun fixture(mode: String): BenchmarkFixtureState =
        BenchmarkFixtures.create(
            mode = mode,
            performanceMessageCount = 20,
            now = now,
        )

    private fun FeedItem.visibleUrls(): List<String> =
        URL.findAll(message).map { it.value }.toList() + linkPreviews.map { it.url }

    private companion object {
        val URL = Regex("""https?://[^\s]+""")
        val EMAIL = Regex("""\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val CREDENTIAL_FIELD = Regex("""(?i)\b(password|passcode|secret|token)\s*[:=]""")
    }
}
