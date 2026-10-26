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
        private var loaded = false
        private var request: MattermostImageLoader.ImageRequest? = null

        fun bind(loader: MattermostImageLoader, file: FeedFile) {
            if (!file.isVideo) {
                clear()
                return
            }
            if (fileId == file.fileId && loaded) return
            if (fileId != file.fileId) clear()
            val boundId = file.fileId
            fileId = boundId
            // Only hold space for a frame that is actually on its way; a cached one pops in when
            // read, and anything else (mobile data, undecodable, retry pending) stays a plain row.
            preview.visibility = if (loader.canFetchVideoThumbnail(boundId)) View.VISIBLE else View.GONE
            // Ask again even for the same file: a load dropped by configure() never calls back, and
            // a skipped one may succeed now. Subscribing before cancelling the previous request
            // lets the loader keep a fetch that is still running.
            val previous = request
            request = loader.loadVideoThumbnail(boundId) { bitmap ->
                if (fileId != boundId) return@loadVideoThumbnail
                if (bitmap == null) {
                    preview.visibility = View.GONE
                } else {
                    loaded = true
                    image.setImageBitmap(bitmap)
                    preview.visibility = View.VISIBLE
                }
            }
            previous?.cancel()
        }

        fun clear() {
            request?.cancel()
            request = null
            fileId = null
            loaded = false
            image.setImageDrawable(null)
            preview.visibility = View.GONE
        }
    }
}
