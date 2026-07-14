package com.example.mattermostlite.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackErrorDetailsTest {
    @Test
    fun `classifies transport parsing and decoder failures`() {
        assertEquals(
            VideoPlaybackErrorKind.NETWORK,
            VideoPlaybackErrorDetails.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            VideoPlaybackErrorKind.MALFORMED,
            VideoPlaybackErrorDetails.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            VideoPlaybackErrorKind.UNSUPPORTED,
            VideoPlaybackErrorDetails.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
        assertEquals(
            VideoPlaybackErrorKind.UNKNOWN,
            VideoPlaybackErrorDetails.classify(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
    }
}
