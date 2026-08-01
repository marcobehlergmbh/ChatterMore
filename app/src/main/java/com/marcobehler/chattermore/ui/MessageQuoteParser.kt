package com.marcobehler.chattermore.ui

internal data class MessageTextRange(
    val start: Int,
    val end: Int,
)

internal data class ParsedMessageQuotes(
    val text: String,
    val quoteRanges: List<MessageTextRange>,
    val markerRanges: List<MessageTextRange>,
)

/**
 * Removes Markdown blockquote markers while retaining enough range information
 * to decorate the resulting paragraphs without disturbing existing link and
 * mention spans.
 */
internal object MessageQuoteParser {
    fun parse(text: String): ParsedMessageQuotes? {
        if ('>' !in text) return null

        val output = StringBuilder(text.length)
        val quoteRanges = mutableListOf<MessageTextRange>()
        val markerRanges = mutableListOf<MessageTextRange>()
        var activeQuoteStart: Int? = null
        var lineStart = 0

        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline >= 0) newline else text.length
            val contentStart = quoteContentStart(text, lineStart, lineEnd)

            if (contentStart != null) {
                if (activeQuoteStart == null) activeQuoteStart = output.length
                markerRanges += MessageTextRange(lineStart, contentStart)
                output.append(text, contentStart, lineEnd)
            } else {
                activeQuoteStart?.let { start ->
                    if (start < output.length) {
                        quoteRanges += MessageTextRange(start, output.length)
                    }
                }
                activeQuoteStart = null
                output.append(text, lineStart, lineEnd)
            }

            if (newline < 0) break
            output.append('\n')
            lineStart = newline + 1
        }

        activeQuoteStart?.let { start ->
            if (start < output.length) {
                quoteRanges += MessageTextRange(start, output.length)
            }
        }

        if (markerRanges.isEmpty()) return null
        return ParsedMessageQuotes(
            text = output.toString(),
            quoteRanges = quoteRanges,
            markerRanges = markerRanges,
        )
    }

    private fun quoteContentStart(text: String, lineStart: Int, lineEnd: Int): Int? {
        var marker = lineStart
        while (marker < lineEnd && marker - lineStart < MAX_LEADING_SPACES &&
            text[marker] == ' '
        ) {
            marker += 1
        }
        if (marker >= lineEnd || text[marker] != '>') return null

        var contentStart = marker + 1
        while (contentStart < lineEnd && text[contentStart] == '>') {
            contentStart += 1
        }
        if (contentStart < lineEnd &&
            (text[contentStart] == ' ' || text[contentStart] == '\t')
        ) {
            contentStart += 1
        }
        return contentStart
    }

    private const val MAX_LEADING_SPACES = 3
}
