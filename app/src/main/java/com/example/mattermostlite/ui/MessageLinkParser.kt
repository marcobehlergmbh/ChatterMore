package com.example.mattermostlite.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.URLSpan

/** Regex-free, single-pass link and emoticon formatter for the message-list hot path. */
internal object MessageLinkParser {
    fun parse(text: String): CharSequence {
        val formatted = format(text) ?: return text
        if (formatted.links.isEmpty()) return formatted.text
        return SpannableString(formatted.text).apply {
            formatted.links.forEach { link ->
                setSpan(
                    URLSpan(link.target),
                    link.start,
                    link.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    internal fun format(text: String): FormattedMessage? {
        var cursor = 0
        var sourceStart = 0
        var codeDelimiterLength = 0
        var output: StringBuilder? = null
        var links: MutableList<MessageLink>? = null

        while (cursor < text.length) {
            val link = linkAt(text, cursor)
            if (link != null) {
                val outputStart: Int
                val outputEnd: Int
                if (output == null) {
                    outputStart = link.start
                    outputEnd = link.end
                } else {
                    output.append(text, sourceStart, link.start)
                    outputStart = output.length
                    output.append(text, link.start, link.end)
                    outputEnd = output.length
                    sourceStart = link.end
                }
                if (links == null) links = ArrayList(1)
                links.add(MessageLink(outputStart, outputEnd, link.target))
                cursor = link.end
                continue
            }

            if (text[cursor] == '`') {
                val runLength = backtickRunLength(text, cursor)
                codeDelimiterLength = when {
                    codeDelimiterLength == 0 -> runLength
                    runLength >= codeDelimiterLength -> 0
                    else -> codeDelimiterLength
                }
                cursor += runLength
                continue
            }

            if (codeDelimiterLength == 0) {
                val escaped = text[cursor] == '\\' && cursor + 1 < text.length
                val candidateIndex = if (escaped) cursor + 1 else cursor
                val match = if (text[candidateIndex].isSmileyStart()) {
                    smileyAt(text, candidateIndex)
                } else {
                    null
                }
                if (match != null) {
                    val builder = output
                    if (builder == null) {
                        output = StringBuilder(text.length).apply { append(text, 0, cursor) }
                    } else {
                        builder.append(text, sourceStart, cursor)
                    }
                    val target = output!!
                    if (escaped) {
                        target.append(text, cursor + 1, match.end)
                    } else {
                        target.append(match.emoji)
                    }
                    cursor = match.end
                    sourceStart = cursor
                    continue
                }
            }
            cursor++
        }

        if (output == null && links == null) return null
        val displayText = output?.apply { append(text, sourceStart, text.length) }?.toString() ?: text
        return FormattedMessage(displayText, links ?: emptyList())
    }

    fun findLinks(text: String): List<MessageLink> {
        var cursor = 0
        var links: MutableList<MessageLink>? = null
        while (cursor < text.length) {
            val link = linkAt(text, cursor)
            if (link == null) {
                cursor++
            } else {
                if (links == null) links = ArrayList(1)
                links.add(link)
                cursor = link.end
            }
        }
        return links ?: emptyList()
    }

    private fun linkAt(text: String, index: Int): MessageLink? {
        val prefix = prefixAt(text, index) ?: return null
        if (!hasBoundaryBefore(text, index)) return null
        var end = index + prefix.length
        while (end < text.length && !text[end].isLinkTerminator()) end++
        end = trimTrailingPunctuation(text, index, end)
        val addressStart = index + prefix.length
        if (end <= addressStart || !text[addressStart].isHostStart()) return null
        val displayUrl = text.substring(index, end)
        val targetUrl = if (prefix == WWW_PREFIX) "https://$displayUrl" else displayUrl
        return MessageLink(index, end, targetUrl)
    }

    private fun smileyAt(text: String, index: Int): SmileyMatch? {
        if (index !in text.indices || !hasBoundaryBefore(text, index)) return null
        val patterns = when (text[index].lowercaseChar()) {
            ':' -> COLON_SMILEYS
            ';' -> WINK_SMILEYS
            'x' -> X_SMILEYS
            '<' -> HEART_SMILEYS
            else -> return null
        }
        patterns.forEach { pattern ->
            if (
                text.regionMatches(
                    thisOffset = index,
                    other = pattern.text,
                    otherOffset = 0,
                    length = pattern.text.length,
                    ignoreCase = pattern.ignoreCase,
                )
            ) {
                val end = index + pattern.text.length
                if (hasBoundaryAfter(text, end)) return SmileyMatch(end, pattern.emoji)
            }
        }
        return null
    }

    private fun backtickRunLength(text: String, start: Int): Int {
        var end = start + 1
        while (end < text.length && text[end] == '`') end++
        return end - start
    }

    private fun prefixAt(text: String, index: Int): String? = when (text[index]) {
        'h', 'H' -> when {
            text.regionMatches(index, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true) -> HTTPS_PREFIX
            text.regionMatches(index, HTTP_PREFIX, 0, HTTP_PREFIX.length, ignoreCase = true) -> HTTP_PREFIX
            else -> null
        }
        'w', 'W' -> WWW_PREFIX.takeIf {
            text.regionMatches(index, WWW_PREFIX, 0, WWW_PREFIX.length, ignoreCase = true)
        }
        else -> null
    }

    private fun hasBoundaryBefore(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isWordCharacter()

    private fun hasBoundaryAfter(text: String, index: Int): Boolean =
        index == text.length || !text[index].isWordCharacter()

    private fun Char.isWordCharacter(): Boolean = isLetterOrDigit() || this == '_'

    private fun Char.isSmileyStart(): Boolean =
        this == ':' || this == ';' || this == 'x' || this == 'X' || this == '<'

    private fun Char.isLinkTerminator(): Boolean =
        isWhitespace() || isISOControl() || this == '<' || this == '>' || this == '"' || this == '\''

    private fun Char.isHostStart(): Boolean = isLetterOrDigit() || this == '['

    private fun trimTrailingPunctuation(text: String, start: Int, initialEnd: Int): Int {
        var end = initialEnd
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) end--
        end = trimUnmatchedClosing(text, start, end, '(', ')')
        end = trimUnmatchedClosing(text, start, end, '[', ']')
        return trimUnmatchedClosing(text, start, end, '{', '}')
    }

    private fun trimUnmatchedClosing(
        text: String,
        start: Int,
        initialEnd: Int,
        opening: Char,
        closing: Char,
    ): Int {
        var balance = 0
        for (index in start until initialEnd) {
            when (text[index]) {
                opening -> balance++
                closing -> balance--
            }
        }
        var end = initialEnd
        while (balance < 0 && end > start && text[end - 1] == closing) {
            balance++
            end--
        }
        return end
    }

    private data class SmileyPattern(
        val text: String,
        val emoji: String,
        val ignoreCase: Boolean = false,
    )

    private data class SmileyMatch(
        val end: Int,
        val emoji: String,
    )

    internal data class FormattedMessage(
        val text: String,
        val links: List<MessageLink>,
    )

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"
    private const val WWW_PREFIX = "www."
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?')

    private val COLON_SMILEYS = arrayOf(
        SmileyPattern(":'-(", "😢"),
        SmileyPattern(":`-(", "😢"),
        SmileyPattern(":’-(", "😢"),
        SmileyPattern(":-)", "🙂"),
        SmileyPattern(":-]", "😏"),
        SmileyPattern(":-d", "😄", ignoreCase = true),
        SmileyPattern(":-p", "😛", ignoreCase = true),
        SmileyPattern(":-@", "😡"),
        SmileyPattern(":-[", "😡"),
        SmileyPattern(":-(", "🙁"),
        SmileyPattern(":-/", "😕"),
        SmileyPattern(":-s", "😖", ignoreCase = true),
        SmileyPattern(":-|", "😐"),
        SmileyPattern(":-$", "😳"),
        SmileyPattern(":-x", "😷", ignoreCase = true),
        SmileyPattern(":-o", "😱", ignoreCase = true),
        SmileyPattern(":'(", "😢"),
        SmileyPattern(":`(", "😢"),
        SmileyPattern(":’(", "😢"),
        SmileyPattern(":)", "🙂"),
        SmileyPattern(":]", "😏"),
        SmileyPattern(":d", "😄", ignoreCase = true),
        SmileyPattern(":p", "😛", ignoreCase = true),
        SmileyPattern(":@", "😡"),
        SmileyPattern(":[", "😡"),
        SmileyPattern(":(", "🙁"),
        SmileyPattern(":/", "😕"),
        SmileyPattern(":s", "😖", ignoreCase = true),
        SmileyPattern(":|", "😐"),
        SmileyPattern(":$", "😳"),
        SmileyPattern(":o", "😮", ignoreCase = true),
    )
    private val WINK_SMILEYS = arrayOf(
        SmileyPattern(";-)", "😉"),
        SmileyPattern(";)", "😉"),
    )
    private val X_SMILEYS = arrayOf(
        SmileyPattern("x-d", "😝", ignoreCase = true),
    )
    private val HEART_SMILEYS = arrayOf(
        SmileyPattern("</3", "💔"),
        SmileyPattern("<3", "❤️"),
    )
}

internal data class MessageLink(
    val start: Int,
    val end: Int,
    val target: String,
)
