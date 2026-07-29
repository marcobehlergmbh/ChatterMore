package com.marcobehler.chattermore.ui

import androidx.media3.common.C
import com.marcobehler.chattermore.model.FeedFile
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackRestoreTest {
    private val file = FeedFile("video-id", "clip.mp4", "video/mp4", 12_000_000)

    @Test
    fun `keeps a valid playback position`() {
        assertEquals(42_500L, VideoPlaybackRestore(file, 42_500L, true).safePositionMs)
    }

    @Test
    fun `normalizes an unset Media3 position to the beginning`() {
        assertEquals(0L, VideoPlaybackRestore(file, C.TIME_UNSET, false).safePositionMs)
    }
}
