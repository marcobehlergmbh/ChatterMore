package com.example.mattermostlite.ui

import com.example.mattermostlite.model.MattermostUser
import java.util.Locale

data class ActiveMention(
    val start: Int,
    val end: Int,
    val query: String,
)

data class MentionInsertion(
    val text: String,
    val cursor: Int,
)

object MentionAutocomplete {
    fun activeMention(text: CharSequence, cursor: Int): ActiveMention? {
        if (cursor !in 0..text.length) return null
        var tokenStart = cursor
        while (tokenStart > 0 && text[tokenStart - 1].isUsernameCharacter()) tokenStart--
        val at = tokenStart - 1
        if (at < 0 || text[at] != '@') return null
        if (at > 0 && (text[at - 1].isUsernameCharacter() || text[at - 1] == '@')) return null

        var tokenEnd = cursor
        while (tokenEnd < text.length && text[tokenEnd].isUsernameCharacter()) tokenEnd++
        return ActiveMention(
            start = at,
            end = tokenEnd,
            query = text.subSequence(at + 1, cursor).toString(),
        )
    }

    fun suggestions(
        users: Collection<MattermostUser>,
        query: String,
    ): List<MattermostUser> {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        return users.asSequence()
            .filter { it.username.isNotBlank() }
            .distinctBy { it.username.lowercase(Locale.ROOT) }
            .mapNotNull { user ->
                val username = user.username.lowercase(Locale.ROOT)
                val displayName = user.displayName.lowercase(Locale.ROOT)
                val rank = when {
                    normalizedQuery.isEmpty() -> 0
                    username.startsWith(normalizedQuery) -> 0
                    displayName.startsWith(normalizedQuery) -> 1
                    normalizedQuery in username -> 2
                    normalizedQuery in displayName -> 3
                    else -> return@mapNotNull null
                }
                RankedUser(user, rank, username)
            }
            .sortedWith(compareBy<RankedUser>({ it.rank }, { it.username }))
            .map(RankedUser::user)
            .toList()
    }

    fun insert(
        text: CharSequence,
        mention: ActiveMention,
        username: String,
    ): MentionInsertion {
        require(mention.start in 0..mention.end && mention.end <= text.length)
        require(username.isNotBlank())
        val appendSpace = mention.end == text.length
        val replacement = if (appendSpace) "@$username " else "@$username"
        val result = buildString(text.length - (mention.end - mention.start) + replacement.length + 1) {
            append(text, 0, mention.start)
            append(replacement)
            append(text, mention.end, text.length)
        }
        val replacementEnd = mention.start + replacement.length
        val cursor = if (
            !appendSpace &&
            mention.end < text.length &&
            text[mention.end] == ' '
        ) {
            replacementEnd + 1
        } else {
            replacementEnd
        }
        return MentionInsertion(result, cursor)
    }

    private fun Char.isUsernameCharacter(): Boolean =
        isLetterOrDigit() || this == '-' || this == '_' || this == '.'

    private data class RankedUser(
        val user: MattermostUser,
        val rank: Int,
        val username: String,
    )
}
