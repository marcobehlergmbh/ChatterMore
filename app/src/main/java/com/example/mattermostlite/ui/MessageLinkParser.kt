package com.example.mattermostlite.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.ClickableSpan
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import com.example.mattermostlite.model.MentionContext
import com.example.mattermostlite.model.MattermostUser
import java.util.Locale

/** Regex-free, single-pass link, emoticon, and mention formatter for the message-list hot path. */
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

    fun render(
        text: String,
        context: MentionContext,
        style: MentionStyle,
        onUserClick: ((MattermostUser) -> Unit)? = null,
    ): RenderedMessage {
        val formatted = format(text) ?: return RenderedMessage(text, MessageEmphasis.NONE)
        val resolution = resolveMentions(formatted, context)
        val validMentions = resolution.mentions
        val emphasis = resolution.emphasis
        val normalizedCurrentUsername = context.currentUsername.lowercase(Locale.ROOT)
        val spannable = if (formatted.links.isNotEmpty() || validMentions.isNotEmpty()) {
            SpannableString(formatted.text)
        } else {
            null
        }
        formatted.links.forEach { link ->
            spannable?.setSpan(
                URLSpan(link.target),
                link.start,
                link.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        validMentions.forEach { mention ->
            val direct = mention.kind == MentionKind.USER &&
                mention.normalizedName == normalizedCurrentUsername
            val foreground = when {
                direct -> style.directText
                mention.kind == MentionKind.USER -> style.userText
                else -> style.broadcastText
            }
            val background = when {
                direct -> style.directBackground
                mention.kind == MentionKind.USER -> style.userBackground
                else -> style.broadcastBackground
            }
            spannable?.setSpan(
                ForegroundColorSpan(foreground),
                mention.start,
                mention.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable?.setSpan(
                BackgroundColorSpan(background),
                mention.start,
                mention.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable?.setSpan(
                StyleSpan(Typeface.BOLD),
                mention.start,
                mention.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (mention.kind == MentionKind.USER && onUserClick != null) {
                val user = context.usersByUsername[mention.normalizedName]
                if (user != null) {
                    spannable?.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onUserClick(user)
                            }

                            override fun updateDrawState(ds: TextPaint) = Unit
                        },
                        mention.start,
                        mention.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return RenderedMessage(spannable ?: formatted.text, emphasis)
    }

    internal fun resolveMentions(
        formatted: FormattedMessage,
        context: MentionContext,
    ): MentionResolution {
        val normalizedCurrentUsername = context.currentUsername.lowercase(Locale.ROOT)
        var emphasis = MessageEmphasis.NONE
        val validMentions = formatted.mentions.filter { mention ->
            when (mention.kind) {
                MentionKind.USER -> mention.normalizedName in context.usersByUsername
                MentionKind.CHANNEL,
                MentionKind.ALL,
                MentionKind.HERE -> true
                MentionKind.GROUP -> false
            }
        }
        validMentions.forEach { mention ->
            val direct = mention.kind == MentionKind.USER &&
                mention.normalizedName == normalizedCurrentUsername
            emphasis = when {
                direct -> MessageEmphasis.DIRECT
                emphasis == MessageEmphasis.DIRECT -> emphasis
                mention.kind == MentionKind.HERE && emphasis == MessageEmphasis.NONE -> MessageEmphasis.HERE
                mention.kind == MentionKind.CHANNEL || mention.kind == MentionKind.ALL -> MessageEmphasis.BROADCAST
                else -> emphasis
            }
        }
        return MentionResolution(validMentions, emphasis)
    }

    internal fun format(text: String): FormattedMessage? {
        var cursor = 0
        var sourceStart = 0
        var codeDelimiterLength = 0
        var output: StringBuilder? = null
        var links: MutableList<MessageLink>? = null
        var mentions: MutableList<MessageMention>? = null

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

                val mention = if (text[candidateIndex] == '@') mentionAt(text, candidateIndex) else null
                if (mention != null) {
                    if (escaped) {
                        val builder = output
                        if (builder == null) {
                            output = StringBuilder(text.length).apply { append(text, 0, cursor) }
                        } else {
                            builder.append(text, sourceStart, cursor)
                        }
                        output!!.append(text, cursor + 1, mention.end)
                    } else {
                        val outputStart: Int
                        val outputEnd: Int
                        if (output == null) {
                            outputStart = mention.start
                            outputEnd = mention.end
                        } else {
                            output.append(text, sourceStart, mention.start)
                            outputStart = output.length
                            output.append(text, mention.start, mention.end)
                            outputEnd = output.length
                        }
                        if (mentions == null) mentions = ArrayList(1)
                        mentions.add(
                            MessageMention(
                                start = outputStart,
                                end = outputEnd,
                                kind = mention.kind,
                                normalizedName = mention.normalizedName,
                            ),
                        )
                    }
                    cursor = mention.end
                    sourceStart = cursor
                    continue
                }
            }
            cursor++
        }

        if (output == null && links == null && mentions == null) return null
        val displayText = output?.apply { append(text, sourceStart, text.length) }?.toString() ?: text
        return FormattedMessage(displayText, links ?: emptyList(), mentions ?: emptyList())
    }

    fun findMentionUsernames(text: String): Set<String> = format(text)
        ?.mentions
        ?.asSequence()
        ?.filter { it.kind == MentionKind.USER }
        ?.mapTo(linkedSetOf(), MessageMention::normalizedName)
        ?: emptySet()

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

    private fun mentionAt(text: String, index: Int): MentionMatch? {
        if (index !in text.indices || text[index] != '@' || !hasMentionBoundaryBefore(text, index)) {
            return null
        }
        val nameStart = index + 1
        if (nameStart >= text.length || !text[nameStart].isLetterOrDigit()) return null
        var end = nameStart + 1
        while (end < text.length && text[end].isMentionNameCharacter()) end++
        while (end > nameStart && text[end - 1] == '.') end--
        if (end == nameStart) return null
        val normalizedName = text.substring(nameStart, end).lowercase(Locale.ROOT)
        return MentionMatch(
            start = index,
            end = end,
            kind = BROADCAST_MENTIONS[normalizedName] ?: MentionKind.USER,
            normalizedName = normalizedName,
        )
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

    private fun hasMentionBoundaryBefore(text: String, index: Int): Boolean =
        index == 0 || (text[index - 1] != '@' && !text[index - 1].isMentionNameCharacter())

    private fun Char.isWordCharacter(): Boolean = isLetterOrDigit() || this == '_'

    private fun Char.isMentionNameCharacter(): Boolean =
        isLetterOrDigit() || this == '_' || this == '-' || this == '.'

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

    private data class MentionMatch(
        val start: Int,
        val end: Int,
        val kind: MentionKind,
        val normalizedName: String,
    )

    internal data class FormattedMessage(
        val text: String,
        val links: List<MessageLink>,
        val mentions: List<MessageMention>,
    )

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"
    private const val WWW_PREFIX = "www."
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?')
    private val BROADCAST_MENTIONS = mapOf(
        "channel" to MentionKind.CHANNEL,
        "here" to MentionKind.HERE,
        "all" to MentionKind.ALL,
    )

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

internal enum class MentionKind {
    USER,
    CHANNEL,
    HERE,
    ALL,
    GROUP,
}

internal data class MessageMention(
    val start: Int,
    val end: Int,
    val kind: MentionKind,
    val normalizedName: String,
)

internal data class MentionStyle(
    val userText: Int,
    val userBackground: Int,
    val directText: Int,
    val directBackground: Int,
    val broadcastText: Int,
    val broadcastBackground: Int,
)

internal enum class MessageEmphasis {
    NONE,
    BROADCAST,
    HERE,
    DIRECT,
}

internal data class RenderedMessage(
    val text: CharSequence,
    val emphasis: MessageEmphasis,
)

internal data class MentionResolution(
    val mentions: List<MessageMention>,
    val emphasis: MessageEmphasis,
)
