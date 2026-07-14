package com.example.mattermostlite.ui

import java.net.URI
import java.util.UUID

internal object SunoLinkParser {
    fun firstSong(message: String): SunoSong? = MessageLinkParser.findLinks(message)
        .firstNotNullOfOrNull { fromUrl(it.target) }

    fun fromUrl(url: String): SunoSong? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("www.") ?: return null
        if (host != "suno.com" && host != "suno.ai") return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.firstOrNull() !in SUPPORTED_PATHS || segments.size < 2) return null
        val songId = segments.getOrNull(1)?.takeIf(::isUuid)
        SunoSong(pageUrl = url, songId = songId)
    }.getOrNull()

    private fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private val SUPPORTED_PATHS = setOf("song", "s", "embed")
}

data class SunoSong(
    val pageUrl: String,
    val songId: String?,
)
