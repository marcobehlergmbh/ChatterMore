package com.marcobehler.chattermore.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RangeMediaDataSourceTest {
    private val block = RangeMediaDataSource.BLOCK_SIZE
    private val video = ByteArray(block * 3 + 100) { (it % 251).toByte() }

    @Test
    fun `serves bytes across blocks from range requests`() {
        val requests = mutableListOf<Request>()
        val source = source(rangeServer(requests))

        assertEquals(video.size.toLong(), source.size)
        val buffer = ByteArray(16)
        val read = source.readAt(block.toLong() - 8, buffer, 0, buffer.size)

        // A read is cut at the block boundary; the caller asks again for the rest.
        assertEquals(8, read)
        assertArrayEquals(video.copyOfRange(block - 8, block), buffer.copyOf(8))
        assertEquals(-1, source.readAt(video.size.toLong(), buffer, 0, buffer.size))
        assertEquals(listOf("bytes=0-${block - 1}"), requests.map { it.header("Range") })
        assertEquals("Bearer token", requests.single().header("Authorization"))
    }

    @Test
    fun `stops fetching once the byte budget is spent`() {
        val source = source(rangeServer(mutableListOf()), byteBudget = block.toLong())
        source.readAt(0, ByteArray(1), 0, 1)

        assertThrows { source.readAt(block.toLong(), ByteArray(1), 0, 1) }
        assertFalse(source.transientFailure)
        assertFalse(source.cancelled)
    }

    @Test
    fun `marks dropped connections as worth retrying`() {
        val source = source(
            OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("timeout") }
                .build(),
        )

        assertThrows { source.size }
        assertTrue(source.transientFailure)
    }

    @Test
    fun `server errors are retryable but missing files are not`() {
        val unavailable = source(clientReturning(503))
        assertThrows { unavailable.size }
        assertTrue(unavailable.transientFailure)

        val missing = source(clientReturning(404))
        assertThrows { missing.size }
        assertFalse(missing.transientFailure)
    }

    @Test
    fun `refuses servers that ignore the range header`() {
        val source = source(clientReturning(200))

        assertThrows { source.size }
        assertFalse(source.transientFailure)
    }

    @Test
    fun `does not fetch once nobody wants the frame`() {
        val requests = mutableListOf<Request>()
        val source = source(rangeServer(requests), isWanted = { false })

        assertThrows { source.size }
        assertTrue(source.cancelled)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `parses the total size from content range`() {
        assertEquals(1234L, RangeMediaDataSource.parseTotalSize("bytes 0-99/1234"))
        assertNull(RangeMediaDataSource.parseTotalSize("bytes 0-99/*"))
        assertNull(RangeMediaDataSource.parseTotalSize(null))
    }

    private fun source(
        client: OkHttpClient,
        byteBudget: Long = Long.MAX_VALUE,
        isWanted: () -> Boolean = { true },
    ) = RangeMediaDataSource(
        client = client,
        url = "https://chat.example.com/api/v4/files/file-1".toHttpUrl(),
        authorization = "Bearer token",
        byteBudget = byteBudget,
        isWanted = isWanted,
    )

    private fun rangeServer(requests: MutableList<Request>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                requests += chain.request()
                val range = chain.request().header("Range").orEmpty()
                val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                val last = minOf(end, video.size - 1)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$last/${video.size}")
                    .body(video.copyOfRange(start, last + 1).toResponseBody())
                    .build()
            },
        )
        .build()

    private fun clientReturning(status: Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("status")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
        .build()

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }
}
