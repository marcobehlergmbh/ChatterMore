package com.example.mattermostlite.ui

import java.net.URI
import java.net.URLDecoder

internal object YouTubeLinkParser {
    fun firstVideo(message: String): YouTubeVideo? = MessageLinkParser.findLinks(message)
        .firstNotNullOfOrNull { fromUrl(it.target) }

    fun fromUrl(url: String): YouTubeVideo? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val normalizedHost = host
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("music.")
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val videoId = when (normalizedHost) {
            "youtu.be" -> segments.firstOrNull()
            "youtube.com", "youtube-nocookie.com" -> when (segments.firstOrNull()) {
                "watch" -> queryParameter(uri.rawQuery, "v")
                "shorts", "embed", "live" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }?.takeIf(::isVideoId) ?: return null
        YouTubeVideo(videoId, url)
    }.getOrNull()

    private fun queryParameter(query: String?, expectedName: String): String? = query
        ?.split('&')
        ?.firstNotNullOfOrNull { part ->
            val separator = part.indexOf('=')
            val rawName = if (separator < 0) part else part.substring(0, separator)
            if (URLDecoder.decode(rawName, Charsets.UTF_8.name()) != expectedName) {
                null
            } else {
                URLDecoder.decode(
                    if (separator < 0) "" else part.substring(separator + 1),
                    Charsets.UTF_8.name(),
                )
            }
        }

    private fun isVideoId(value: String): Boolean =
        value.length == VIDEO_ID_LENGTH && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private const val VIDEO_ID_LENGTH = 11
}

data class YouTubeVideo(
    val id: String,
    val url: String,
)
