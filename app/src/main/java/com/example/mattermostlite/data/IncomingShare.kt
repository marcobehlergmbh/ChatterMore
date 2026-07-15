package com.example.mattermostlite.data

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.util.UUID

data class IncomingShare(
    val id: String = UUID.randomUUID().toString(),
    val attachments: List<AttachmentSelection>,
    val text: String,
)

class IncomingShareParser {
    fun parse(intent: Intent): IncomingShare? {
        val extraStreams = runCatching {
            buildList {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(::addAll)
            }
        }.getOrDefault(emptyList())
        val clipStreams = runCatching {
            buildList {
                intent.clipData?.let { clipData ->
                    repeat(clipData.itemCount) { index -> clipData.getItemAt(index).uri?.let(::add) }
                }
            }
        }.getOrDefault(emptyList())
        val normalized = normalizeIncomingShare(
            action = intent.action,
            extraStreams = extraStreams.map(Uri::toString),
            clipStreams = clipStreams.map(Uri::toString),
            text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull(),
        ) ?: return null
        val attachments = normalized.uris
            .map { AttachmentSelection(it.toUri()) }
        return IncomingShare(attachments = attachments, text = normalized.text)
    }
}

internal data class NormalizedIncomingShare(
    val uris: List<String>,
    val text: String,
)

internal fun normalizeIncomingShare(
    action: String?,
    extraStreams: Iterable<String?>,
    clipStreams: Iterable<String?>,
    text: String?,
): NormalizedIncomingShare? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val uris = normalizeSharedUriStrings(extraStreams + clipStreams)
    val normalizedText = text?.trim().orEmpty()
    return if (uris.isEmpty() && normalizedText.isBlank()) {
        null
    } else {
        NormalizedIncomingShare(uris = uris, text = normalizedText)
    }
}

internal fun normalizeSharedUriStrings(candidates: Iterable<String?>): List<String> {
    val result = linkedSetOf<String>()
    candidates.forEach { candidate ->
        val value = candidate?.trim().orEmpty()
        val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (value.isNotEmpty() && scheme in SUPPORTED_SHARED_URI_SCHEMES) result += value
    }
    return result.toList()
}

internal fun combineSharedDraftText(current: String, shared: String): String = when {
    current.isBlank() -> shared
    shared.isBlank() -> current
    else -> "$current\n$shared"
}

private val SUPPORTED_SHARED_URI_SCHEMES = setOf("content", "file", "android.resource")
