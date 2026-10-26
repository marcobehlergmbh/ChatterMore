package com.marcobehler.chattermore.network

import com.marcobehler.chattermore.ui.SunoLinkParser
import com.marcobehler.chattermore.ui.SunoSong
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

class SunoAudioResolver(
    private val client: OkHttpClient,
) {
    suspend fun resolve(song: SunoSong): ResolvedSunoAudio {
        val page = runCatching { fetchPage(song.pageUrl) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return song.songId?.let(::fallbackAudio) ?: throw error
        }
        val audioUrl = extractMetadata(page.html, "og:audio")
            ?.decodeHtmlEntities()
            ?.takeIf(::isTrustedAudioUrl)
        val title = extractSongTitle(page.html)
        val finalSongId = song.songId ?: SunoLinkParser.fromUrl(page.finalUrl)?.songId
        return if (audioUrl != null) {
            ResolvedSunoAudio(
                audioUrl = audioUrl,
                title = title,
            )
        } else {
            finalSongId?.let { fallbackAudio(it, title) }
                ?: throw IOException("Suno did not expose playable audio for this link")
        }
    }

    private suspend fun fetchPage(url: String): SunoPage = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", USER_AGENT)
            .build(),
    ).readResponse { response ->
        if (!response.isSuccessful) throw IOException("Suno returned HTTP ${response.code}")
        val html = response.body.readBoundedText(MAX_PAGE_BYTES)
            ?: throw IOException("Suno page was too large")
        SunoPage(html, response.request.url.toString())
    }

    private fun fallbackAudio(songId: String, title: String? = null): ResolvedSunoAudio = ResolvedSunoAudio(
        audioUrl = "https://cdn1.suno.ai/$songId.mp3",
        title = title,
    )

    internal companion object {
        private const val MAX_PAGE_BYTES = 2 * 1_024 * 1_024
        private const val USER_AGENT = "Mozilla/5.0 (Android) ChatterMore/1.0"
        private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE = Regex(
            """([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
            RegexOption.IGNORE_CASE,
        )

        internal fun extractMetadata(html: String, property: String): String? = META_TAG
            .findAll(html)
            .firstNotNullOfOrNull { match ->
                val attributes = ATTRIBUTE.findAll(match.value).associate { attribute ->
                    attribute.groupValues[1].lowercase() to
                        (attribute.groupValues[2].ifEmpty { attribute.groupValues[3] })
                }
                attributes["content"]?.takeIf {
                    attributes["property"].equals(property, ignoreCase = true) ||
                        attributes["name"].equals(property, ignoreCase = true)
                }
            }

        internal fun extractSongTitle(html: String): String? = sequenceOf(
            extractMetadata(html, "og:title"),
            extractMetadata(html, "twitter:title"),
            TITLE_TAG.find(html)?.groupValues?.get(1),
        ).filterNotNull()
            .map { value ->
                value.decodeHtmlEntities()
                    .replace(WHITESPACE, " ")
                    .replace(SUNO_TITLE_SUFFIX, "")
                    .replace(SUNO_TITLE_PREFIX, "")
                    .trim()
            }
            .firstOrNull { it.isNotBlank() && !it.equals("Suno", ignoreCase = true) }

        private fun isTrustedAudioUrl(value: String): Boolean = runCatching {
            val uri = URI(value.decodeHtmlEntities())
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase()?.let { it == "suno.ai" || it.endsWith(".suno.ai") } == true
        }.getOrDefault(false)

        private val TITLE_TAG = Regex(
            """<title\b[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE = Regex("""\s+""")
        private val SUNO_TITLE_SUFFIX = Regex(
            """\s*[|·\-–—]\s*Suno(?:\s+AI)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val SUNO_TITLE_PREFIX = Regex(
            """^Suno(?:\s+AI)?\s*[|·\-–—]\s*""",
            RegexOption.IGNORE_CASE,
        )

        private fun String.decodeHtmlEntities(): String = this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        private fun okhttp3.ResponseBody.readBoundedText(limit: Int): String? {
            if (contentLength() > limit) return null
            return byteStream().use { input ->
                val output = ByteArrayOutputStream(32 * 1_024)
                val buffer = ByteArray(16 * 1_024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) return null
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private data class SunoPage(
        val html: String,
        val finalUrl: String,
    )
}

data class ResolvedSunoAudio(
    val audioUrl: String,
    val title: String?,
)
