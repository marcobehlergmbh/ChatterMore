package com.marcobehler.chattermore.network

import android.media.MediaDataSource
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
        if (size < 0) block(0)
        return size
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (size in 0..position) return -1
        val start = position - position % BLOCK_SIZE
        val block = block(start)
        val index = (position - start).toInt()
        if (index >= block.size) return -1
        val count = min(length, block.size - index)
        System.arraycopy(block, index, buffer, offset, count)
        return count
    }

    @Synchronized
    override fun close() {
        call?.cancel()
        blocks.clear()
    }

    private fun block(start: Long): ByteArray {
        blocks[start]?.let { return it }
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
        val bytes = try {
            newCall.execute().use { response ->
                // OkHttp drops Authorization on cross-host redirects; still refuse to read anything
                // that didn't end up on HTTPS.
                if (response.request.url.scheme != "https") throw IOException("Insecure video redirect")
                if (!response.isSuccessful) {
                    transientFailure = response.code >= 500 || response.code in RETRYABLE_CODES
                    throw IOException("Video range request failed: ${response.code}")
                }
                // A 200 means the server ignored Range and would stream the whole file.
                if (response.code != 206) throw IOException("Server does not support range requests")
                size = parseTotalSize(response.header("Content-Range"))
                    ?: throw IOException("Missing Content-Range total")
                val body = response.body.bytes()
                if (body.size > BLOCK_SIZE) throw IOException("Range response larger than requested")
                body
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
        fetchedBytes += bytes.size
        blocks[start] = bytes
        return bytes
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

        private fun isNetworkFailure(e: IOException): Boolean =
            e is java.net.SocketTimeoutException ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.SocketException ||
                e is java.io.InterruptedIOException ||
                e is javax.net.ssl.SSLException
    }
}
