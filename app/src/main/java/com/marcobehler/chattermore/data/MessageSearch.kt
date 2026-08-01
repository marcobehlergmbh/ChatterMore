package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.MattermostChannel
import java.util.Locale

internal object MessageSearch {
    fun isSearchable(query: String): Boolean =
        query.count(Char::isLetterOrDigit) >= MIN_SEARCH_CHARACTERS

    fun scopedTerms(query: String, channel: MattermostChannel): String {
        val scope = channel.name
            .takeIf { channel.type == "O" || channel.type == "P" }
            ?.takeIf(String::isNotBlank)
            ?: channel.id
        return "${query.trim()} in:$scope"
    }

    fun highlightTerms(query: String, serverMatches: List<String> = emptyList()): List<String> {
        val candidates = serverMatches.ifEmpty {
            query.splitToSequence(' ', '\t', '\n', '\r')
                .filterNot { ':' in it }
                .toList()
        }
        return candidates.asSequence()
            .map { it.trim(*TERM_TRIM_CHARACTERS).lowercase(Locale.ROOT) }
            .filter { it.length >= MIN_HIGHLIGHT_CHARACTERS }
            .distinct()
            .take(MAX_HIGHLIGHT_TERMS)
            .toList()
    }

    fun locallyMatches(message: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val normalized = message.lowercase(Locale.ROOT)
        return terms.all(normalized::contains)
    }

    private const val MIN_SEARCH_CHARACTERS = 3
    private const val MIN_HIGHLIGHT_CHARACTERS = 2
    private const val MAX_HIGHLIGHT_TERMS = 12
    private val TERM_TRIM_CHARACTERS = charArrayOf(
        '"', '\'', '`', '.', ',', ':', ';', '!', '?', '(', ')', '[', ']', '{', '}',
    )
}
