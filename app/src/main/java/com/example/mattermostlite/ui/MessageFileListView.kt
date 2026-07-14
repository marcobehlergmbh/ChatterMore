package com.example.mattermostlite.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.mattermostlite.R
import com.example.mattermostlite.model.FeedFile

class MessageFileListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onFileClick: ((FeedFile) -> Unit)? = null

    fun setFiles(files: List<FeedFile>) {
        files.forEachIndexed { index, file ->
            val child = childAt(index)
            child.visibility = View.VISIBLE
            child.text = file.name
            child.contentDescription = context.getString(
                if (file.isVideo) R.string.play_video_file else R.string.download_file,
                file.name,
            )
            child.setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(
                    context,
                    if (file.isVideo) R.drawable.ic_video else R.drawable.ic_download,
                ),
                null,
                null,
                null,
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

    private fun childAt(index: Int): TextView {
        while (childCount <= index) {
            LayoutInflater.from(context).inflate(R.layout.item_message_file, this, true)
        }
        return getChildAt(index) as TextView
    }
}
