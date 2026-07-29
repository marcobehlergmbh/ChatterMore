package com.marcobehler.chattermore.ui

import com.marcobehler.chattermore.model.FeedFile

internal data class VideoPlaybackRestore(
    val file: FeedFile,
    val positionMs: Long,
    val playWhenReady: Boolean,
) {
    val safePositionMs: Long
        get() = positionMs.coerceAtLeast(0L)
}
