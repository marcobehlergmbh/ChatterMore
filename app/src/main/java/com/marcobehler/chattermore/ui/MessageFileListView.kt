package com.marcobehler.chattermore.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.network.MattermostImageLoader

class MessageFileListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onFileClick: ((FeedFile) -> Unit)? = null

    fun setFiles(loader: MattermostImageLoader, files: List<FeedFile>) {
        files.forEachIndexed { index, file ->
            val child = childAt(index)
            child.visibility = View.VISIBLE
            child.findViewById<TextView>(R.id.fileName).text = file.name
            val sizeLabel = FileSizeFormatter.format(file.sizeBytes)
            child.findViewById<TextView>(R.id.fileSize).apply {
                text = sizeLabel
                visibility = if (sizeLabel == null) View.GONE else View.VISIBLE
            }
            val actionDescription = context.getString(
                if (file.isVideo) R.string.play_video_file else R.string.download_file,
                file.name,
            )
            child.contentDescription = sizeLabel?.let {
                context.getString(R.string.file_attachment_with_size, actionDescription, it)
            } ?: actionDescription
            child.findViewById<ImageView>(R.id.fileIcon).setImageResource(
                if (file.isVideo) R.drawable.ic_video else R.drawable.ic_download,
            )
            child.setOnClickListener { onFileClick?.invoke(file) }
            (child.tag as VideoThumbnail).bind(loader, file)
        }
        for (index in files.size until childCount) hide(getChildAt(index))
    }

    fun clearFiles() {
        for (index in 0 until childCount) hide(getChildAt(index))
    }

    private fun hide(child: View) {
        child.visibility = View.GONE
        child.setOnClickListener(null)
        (child.tag as VideoThumbnail).clear()
    }

    private fun childAt(index: Int): View {
        while (childCount <= index) {
            val child = LayoutInflater.from(context).inflate(R.layout.item_message_file, this, false)
            child.tag = VideoThumbnail(
                child.findViewById(R.id.videoPreview),
                child.findViewById(R.id.videoThumbnail),
            )
            addView(child)
        }
        return getChildAt(index)
    }

    private class VideoThumbnail(
        private val preview: View,
        private val image: ImageView,
    ) {
        private var fileId: String? = null
        private var request: MattermostImageLoader.ImageRequest? = null

        fun bind(loader: MattermostImageLoader, file: FeedFile) {
            if (!file.isVideo) {
                clear()
                return
            }
            if (fileId == file.fileId) return
            clear()
            val boundId = file.fileId
            fileId = boundId
            preview.visibility = View.VISIBLE
            request = loader.loadVideoThumbnail(boundId) { bitmap ->
                if (fileId != boundId) return@loadVideoThumbnail
                // Frames from codecs the device can't decode fall back to the plain file card.
                if (bitmap == null) preview.visibility = View.GONE else image.setImageBitmap(bitmap)
            }
        }

        fun clear() {
            request?.cancel()
            request = null
            fileId = null
            image.setImageDrawable(null)
            preview.visibility = View.GONE
        }
    }
}
