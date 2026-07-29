package com.marcobehler.chattermore.ui

import com.marcobehler.chattermore.model.MattermostUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionAutocompleteTest {
    private val alice = MattermostUser("1", "alice", "Alice", "Anderson")
    private val albert = MattermostUser("2", "bert", "Albert", "Baker")
    private val bob = MattermostUser("3", "bob", "Bob", "Zimmer")

    @Test
    fun `typing at starts a mention at the cursor`() {
        assertEquals(
            ActiveMention(start = 6, end = 10, query = "ali"),
            MentionAutocomplete.activeMention("Hello @ali", 10),
        )
        assertEquals(
            ActiveMention(start = 4, end = 10, query = "al"),
            MentionAutocomplete.activeMention("Hi (@alice)!", 7),
        )
    }

    @Test
    fun `email addresses do not start mention suggestions`() {
        assertNull(MentionAutocomplete.activeMention("mail@example", 12))
    }

    @Test
    fun `empty query returns all users sorted by username`() {
        assertEquals(
            listOf("alice", "bert", "bob"),
            MentionAutocomplete.suggestions(listOf(bob, alice, albert), "").map { it.username },
        )
    }

    @Test
    fun `query matches usernames and display names with prefix matches first`() {
        assertEquals(
            listOf("alice", "bert"),
            MentionAutocomplete.suggestions(listOf(albert, alice, bob), "al").map { it.username },
        )
    }

    @Test
    fun `selection replaces the complete mention token and keeps following text`() {
        val insertion = MentionAutocomplete.insert(
            text = "Hello @alcie, welcome",
            mention = ActiveMention(start = 6, end = 12, query = "al"),
            username = "alice",
        )

        assertEquals("Hello @alice, welcome", insertion.text)
        assertEquals(12, insertion.cursor)
    }

    @Test
    fun `selection appends a space at the end of the message`() {
        val insertion = MentionAutocomplete.insert(
            text = "@bo",
            mention = ActiveMention(start = 0, end = 3, query = "bo"),
            username = "bob",
        )

        assertEquals("@bob ", insertion.text)
        assertEquals(5, insertion.cursor)
    }
}
