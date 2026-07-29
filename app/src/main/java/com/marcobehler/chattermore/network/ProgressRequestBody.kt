package com.marcobehler.chattermore.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit,
    private val onBodyComplete: () -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = runCatching { contentLength() }.getOrDefault(-1L).takeIf { it >= 0L }
        var written = 0L
        notifyProgress(written, total)
        val countingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                notifyProgress(written, total)
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
        runCatching(onBodyComplete)
    }

    private fun notifyProgress(written: Long, total: Long?) {
        runCatching { onProgress(written, total) }
    }
}
