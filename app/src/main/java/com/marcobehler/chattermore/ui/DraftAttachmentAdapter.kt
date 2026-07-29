package com.marcobehler.chattermore.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.data.AttachmentStatus
import com.marcobehler.chattermore.data.DraftAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DraftAttachmentAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRetry: (String) -> Unit,
    private val onRemove: (String) -> Unit,
) : RecyclerView.Adapter<DraftAttachmentAdapter.Holder>() {
    private var items: List<DraftAttachment> = emptyList()

    fun submit(items: List<DraftAttachment>) {
        val previous = this.items
        val updated = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size

            override fun getNewListSize(): Int = updated.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].localId == updated[newItemPosition].localId

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == updated[newItemPosition]
        })
        this.items = updated
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_draft_attachment, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview: ImageView = view.findViewById(R.id.draftAttachmentPreview)
        private val name: TextView = view.findViewById(R.id.draftAttachmentName)
        private val status: TextView = view.findViewById(R.id.draftAttachmentStatus)
        private val progress: ProgressBar = view.findViewById(R.id.draftAttachmentProgress)
        private val retry: ImageButton = view.findViewById(R.id.retryDraftAttachment)
        private val remove: ImageButton = view.findViewById(R.id.removeDraftAttachment)
        private var previewJob: Job? = null
        private var boundId: String? = null

        fun bind(item: DraftAttachment) {
            boundId = item.localId
            name.text = item.source.name
            retry.isVisible = item.status is AttachmentStatus.Failed
            retry.setOnClickListener { onRetry(item.localId) }
            remove.setOnClickListener { onRemove(item.localId) }
            renderStatus(item)
            loadPreview(item)
        }

        fun recycle() {
            previewJob?.cancel()
            previewJob = null
            boundId = null
            preview.setImageDrawable(null)
        }

        private fun renderStatus(item: DraftAttachment) {
            val size = item.source.sizeBytes?.let(FileSizeFormatter::format)
            when (val upload = item.status) {
                AttachmentStatus.Queued -> {
                    status.text = context.getString(R.string.attachment_waiting)
                    progress.isVisible = true
                    progress.isIndeterminate = true
                }
                is AttachmentStatus.Uploading -> {
                    val percent = uploadPercent(upload.bytesWritten, upload.totalBytes)
                    status.text = percent?.let {
                        context.getString(R.string.attachment_uploading_percent, it)
                    } ?: context.getString(R.string.attachment_uploading)
                    progress.isVisible = true
                    progress.isIndeterminate = percent == null
                    if (percent != null) progress.setProgress(percent, true)
                }
                AttachmentStatus.Finalizing -> {
                    status.setText(R.string.attachment_finalizing)
                    progress.isVisible = true
                    progress.isIndeterminate = true
                }
                is AttachmentStatus.Ready -> {
                    status.text = size ?: context.getString(R.string.attachment_ready)
                    progress.isVisible = false
                }
                is AttachmentStatus.Failed -> {
                    status.text = upload.message
                    progress.isVisible = false
                }
            }
        }

        private fun loadPreview(item: DraftAttachment) {
            previewJob?.cancel()
            val isImage = item.source.mimeType.startsWith("image/", ignoreCase = true)
            val isVideo = item.source.mimeType.startsWith("video/", ignoreCase = true)
            preview.setImageResource(
                when {
                    isVideo -> R.drawable.ic_video
                    isImage -> R.drawable.ic_gallery
                    else -> R.drawable.ic_attach_file
                },
            )
            if (!isImage && !isVideo) return
            previewJob = scope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.loadThumbnail(item.source.uri, Size(160, 160), null)
                    }.getOrNull()
                }
                if (boundId == item.localId && bitmap != null) preview.setImageBitmap(bitmap)
            }
        }
    }
}

internal fun uploadPercent(bytesWritten: Long, totalBytes: Long?): Int? = totalBytes
    ?.takeIf { it > 0L }
    ?.let { (bytesWritten.toDouble() * 100.0 / it).toInt().coerceIn(0, 99) }
