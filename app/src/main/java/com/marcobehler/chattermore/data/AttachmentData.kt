package com.marcobehler.chattermore.data

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.marcobehler.chattermore.model.MattermostFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileNotFoundException
import java.util.UUID

data class AttachmentSelection(
    val uri: Uri,
    val temporary: Boolean = false,
)

data class LocalAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val temporary: Boolean,
)

sealed interface AttachmentStatus {
    data object Queued : AttachmentStatus

    data class Uploading(
        val bytesWritten: Long,
        val totalBytes: Long?,
    ) : AttachmentStatus

    data object Finalizing : AttachmentStatus

    data class Ready(val file: MattermostFile) : AttachmentStatus

    data class Failed(val message: String) : AttachmentStatus
}

data class DraftAttachment(
    val localId: String = UUID.randomUUID().toString(),
    val source: LocalAttachment,
    val status: AttachmentStatus = AttachmentStatus.Queued,
)

class AttachmentDataSource(
    private val resolver: ContentResolver,
) {
    suspend fun inspect(selection: AttachmentSelection): LocalAttachment = withContext(Dispatchers.IO) {
        persistReadAccess(selection.uri)
        val metadata = queryMetadata(selection.uri)
        val name = sanitizeFileName(
            metadata.first
                ?: selection.uri.lastPathSegment?.substringAfterLast('/')
                ?: "attachment",
        )
        val mimeType = resolver.getType(selection.uri)
            ?.takeIf(String::isNotBlank)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val descriptorLength = runCatching {
            resolver.openAssetFileDescriptor(selection.uri, "r")?.use { it.length }
        }.getOrNull()
        val size = metadata.second
            ?.takeIf { it >= 0L }
            ?: descriptorLength?.takeIf { it >= 0L }
        LocalAttachment(
            uri = selection.uri,
            name = name,
            mimeType = mimeType,
            sizeBytes = size,
            temporary = selection.temporary,
        )
    }

    fun requestBody(source: LocalAttachment): RequestBody = object : RequestBody() {
        override fun contentType() = source.mimeType.toMediaTypeOrNull()

        override fun contentLength(): Long = source.sizeBytes ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(source.uri)
                ?: throw FileNotFoundException("The selected file is no longer available.")
            input.use {
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
            }
        }
    }

    fun release(source: LocalAttachment) {
        if (source.temporary) {
            runCatching { resolver.delete(source.uri, null, null) }
        } else {
            runCatching {
                resolver.releasePersistableUriPermission(
                    source.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    fun discard(selection: AttachmentSelection) {
        if (selection.temporary) runCatching { resolver.delete(selection.uri, null, null) }
    }

    private fun persistReadAccess(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() != true) return null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            name to size
        } finally {
            cursor?.close()
        }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024

        fun sanitizeFileName(value: String): String = value
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\r', '_')
            .replace('\n', '_')
            .trim()
            .take(240)
            .ifBlank { "attachment" }
    }
}
