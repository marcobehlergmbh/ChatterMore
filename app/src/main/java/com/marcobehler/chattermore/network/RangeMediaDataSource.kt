package com.marcobehler.chattermore.network

import android.media.MediaDataSource
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Feeds MediaMetadataRetriever through OkHttp range requests instead of the platform HTTP stack,
 * so frame grabs get the app's timeouts and redirect rules, can be abandoned mid-download, and
 * never fetch more than [byteBudget] bytes of a video.
 */
internal class RangeMediaDataSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val authorization: String,
    private val byteBudget: Long,
    private val isWanted: () -> Boolean,
) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) =
            size > MAX_BLOCKS
    }
    private var size = -1L
    private var prefix: ByteArray? = null
    private var fetchedBytes = 0L
    private var call: Call? = null

    /** Set when the grab failed for a reason worth retrying later, such as a dropped connection. */
    @Volatile
    var transientFailure = false
        private set

    /** Set when nobody wants the frame anymore, so the failure says nothing about the video. */
    @Volatile
    var cancelled = false
        private set

    @Synchronized
    override fun getSize(): Long {
        if (size < 0 && prefix == null) chunkAt(0)
        return size
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (size in 0..position) return -1
        val (chunkStart, chunk) = chunkAt(position)
        val index = position - chunkStart
        if (index >= chunk.size) {
            // Past the buffered start of a file whose server ignored Range: that's all we may read.
            if (prefix != null) throw IOException("Video frame byte budget exceeded")
            return -1
        }
        val count = min(length.toLong(), chunk.size - index).toInt()
        System.arraycopy(chunk, index.toInt(), buffer, offset, count)
        return count
    }

    @Synchronized
    override fun close() {
        call?.cancel()
        blocks.clear()
        prefix = null
    }

    /** Returns the start offset and bytes of the chunk holding [position], fetching it if needed. */
    private fun chunkAt(position: Long): Pair<Long, ByteArray> {
        prefix?.let { return 0L to it }
        val start = position - position % BLOCK_SIZE
        blocks[start]?.let { return start to it }
        fetch(start)
        prefix?.let { return 0L to it }
        return start to blocks.getValue(start)
    }

    private fun fetch(start: Long) {
        if (!isWanted()) {
            cancelled = true
            throw IOException("Video frame no longer needed")
        }
        // Counted per fetch, so a decoder seeking back and forth can't exceed the budget either.
        if (fetchedBytes + BLOCK_SIZE > byteBudget) throw IOException("Video frame byte budget exceeded")
        val end = start + BLOCK_SIZE - 1
        val newCall = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", authorization)
                .header("Range", "bytes=$start-$end")
                .build(),
        )
        call = newCall
        try {
            newCall.execute().use { response ->
                // OkHttp drops Authorization on cross-host redirects; still refuse to read anything
                // that didn't end up on HTTPS.
                if (response.request.url.scheme != "https") throw IOException("Insecure video redirect")
                if (!response.isSuccessful) {
                    transientFailure = response.code >= 500 || response.code in RETRYABLE_CODES
                    throw IOException("Video range request failed: ${response.code}")
                }
                when (response.code) {
                    206 -> {
                        size = parseTotalSize(response.header("Content-Range"))
                            ?: throw IOException("Missing Content-Range total")
                        val body = response.body.bytes()
                        if (body.size > BLOCK_SIZE) throw IOException("Range response larger than requested")
                        fetchedBytes += body.size
                        blocks[start] = body
                    }
                    // Some servers and proxies ignore Range and stream the whole file from byte 0.
                    // Keep the start of it, up to the budget; for typical phone videos the index and
                    // first frames live there.
                    200 -> {
                        val total = response.body.contentLength()
                        val (bytes, complete) = response.body.byteStream()
                            .readUpTo(byteBudget - fetchedBytes)
                        fetchedBytes += bytes.size
                        size = when {
                            complete -> bytes.size.toLong()
                            else -> total
                        }
                        blocks.clear()
                        prefix = bytes
                    }
                    else -> throw IOException("Unexpected video response: ${response.code}")
                }
            }
        } catch (e: IOException) {
            if (!isWanted()) {
                cancelled = true
            } else if (isNetworkFailure(e)) {
                // Offline, timeouts and resets say nothing about the video; try again later.
                transientFailure = true
            }
            throw e
        }
    }

    internal companion object {
        const val BLOCK_SIZE = 256 * 1_024
        const val MAX_BLOCKS = 8
        private val RETRYABLE_CODES = setOf(401, 408, 429)

        fun parseTotalSize(contentRange: String?): Long? =
            contentRange
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }

        /** Reads at most [limit] bytes; the flag says whether the stream ended within them. */
        private fun InputStream.readUpTo(limit: Long): Pair<ByteArray, Boolean> {
            val cap = limit.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val output = ByteArrayOutputStream(min(cap, BLOCK_SIZE))
            val buffer = ByteArray(16 * 1_024)
            while (output.size() < cap) {
                val count = read(buffer, 0, min(buffer.size, cap - output.size()))
                if (count < 0) return output.toByteArray() to true
                output.write(buffer, 0, count)
            }
            return output.toByteArray() to (read() < 0)
        }

        private fun isNetworkFailure(e: IOException): Boolean =
            e is java.net.SocketTimeoutException ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.SocketException ||
                e is java.io.InterruptedIOException ||
                e is javax.net.ssl.SSLException
    }
}
