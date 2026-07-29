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

class MessageFileListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onFileClick: ((FeedFile) -> Unit)? = null

    fun setFiles(files: List<FeedFile>) {
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
        }
        for (index in files.size until childCount) {
            getChildAt(index).apply {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
    }

    fun clearFiles() {
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
    }

    private fun childAt(index: Int): View {
        while (childCount <= index) {
            LayoutInflater.from(context).inflate(R.layout.item_message_file, this, true)
        }
        return getChildAt(index)
    }
}
