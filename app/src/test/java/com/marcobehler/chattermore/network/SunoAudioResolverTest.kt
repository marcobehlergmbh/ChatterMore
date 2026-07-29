package com.marcobehler.chattermore.network

import com.marcobehler.chattermore.ui.SunoSong
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class SunoAudioResolverTest {
    @Test
    fun `extracts audio and title regardless of meta attribute order`() {
        val html = """
            <meta content="A Song &amp; More" property="og:title">
            <meta content='https://cdn1.suno.ai/song.mp3?x=1&amp;y=2' property='og:audio'>
        """.trimIndent()

        assertEquals("A Song &amp; More", SunoAudioResolver.extractMetadata(html, "og:title"))
        assertEquals(
            "https://cdn1.suno.ai/song.mp3?x=1&amp;y=2",
            SunoAudioResolver.extractMetadata(html, "og:audio"),
        )
    }

    @Test
    fun `resolves and decodes published suno metadata`() = runBlocking {
        val html = """
            <meta property="og:title" content="A Song &amp; More">
            <meta property="og:audio" content="https://cdn1.suno.ai/song.mp3?x=1&amp;y=2">
        """.trimIndent()
        val client = clientReturning(200, html)

        val audio = SunoAudioResolver(client).resolve(
            SunoSong("https://suno.com/s/share-code", null),
        )

        assertEquals("A Song & More", audio.title)
        assertEquals("https://cdn1.suno.ai/song.mp3?x=1&y=2", audio.audioUrl)
    }

    @Test
    fun `extracts a clean song title from common Suno page titles`() {
        assertEquals(
            "Midnight Drive",
            SunoAudioResolver.extractSongTitle(
                """<meta property="og:title" content="Midnight Drive | Suno AI">""",
            ),
        )
        assertEquals(
            "Neon Rain",
            SunoAudioResolver.extractSongTitle("""<title>Suno - Neon Rain</title>"""),
        )
    }

    @Test
    fun `keeps page title when current Suno page omits og audio`() = runBlocking {
        val id = "2849f55e-e19c-463e-b064-aa2932444bdf"
        val html = """
            <title>To the Sun by MOVÉ | Suno</title>
            <meta property="og:title" content="To the Sun">
            <meta property="og:type" content="music.song">
        """.trimIndent()

        val audio = SunoAudioResolver(clientReturning(200, html)).resolve(
            SunoSong("https://suno.com/song/$id", id),
        )

        assertEquals("To the Sun", audio.title)
        assertEquals("https://cdn1.suno.ai/$id.mp3", audio.audioUrl)
    }

    @Test
    fun `uuid song link falls back to the suno CDN when its page is unavailable`() = runBlocking {
        val id = "e7d772da-2378-40c8-9872-90c2b8205ad7"

        val audio = SunoAudioResolver(clientReturning(503, "unavailable")).resolve(
            SunoSong("https://suno.com/song/$id", id),
        )

        assertEquals("https://cdn1.suno.ai/$id.mp3", audio.audioUrl)
    }

    private fun clientReturning(status: Int, body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Error")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()
}
