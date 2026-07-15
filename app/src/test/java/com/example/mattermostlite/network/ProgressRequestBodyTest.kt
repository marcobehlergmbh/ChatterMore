package com.example.mattermostlite.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProgressRequestBodyTest {
    @Test
    fun `reports complete request body writes before finalization`() {
        val payload = ByteArray(24_000) { (it % 127).toByte() }
        val progress = mutableListOf<Pair<Long, Long?>>()
        var complete = false
        val body = ProgressRequestBody(
            delegate = byteArrayBody(payload),
            onProgress = { written, total -> progress += written to total },
            onBodyComplete = { complete = true },
        )
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals(payload.size.toLong(), sink.size)
        assertEquals(0L to payload.size.toLong(), progress.first())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next.first >= previous.first })
        assertTrue(complete)
    }

    @Test
    fun `keeps total unknown and does not finalize a failed body`() {
        val progress = mutableListOf<Pair<Long, Long?>>()
        var complete = false
        val body = ProgressRequestBody(
            delegate = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = -1L

                override fun writeTo(sink: BufferedSink) {
                    sink.write(ByteArray(9_000))
                    throw IOException("source failed")
                }
            },
            onProgress = { written, total -> progress += written to total },
            onBodyComplete = { complete = true },
        )

        val failure = runCatching { body.writeTo(Buffer()) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(progress.isNotEmpty())
        assertNull(progress.first().second)
        assertFalse(complete)
    }

    private fun byteArrayBody(payload: ByteArray): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = payload.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            sink.write(payload, 0, 8_000)
            sink.write(payload, 8_000, payload.size - 8_000)
        }
    }
}
