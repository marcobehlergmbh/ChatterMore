package com.example.mattermostlite.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException

internal enum class VideoPlaybackErrorKind {
    NETWORK,
    MALFORMED,
    UNSUPPORTED,
    UNKNOWN,
}

internal data class VideoPlaybackErrorDetails(
    val kind: VideoPlaybackErrorKind,
    val technicalDetails: String,
) {
    @UnstableApi
    companion object {
        fun from(error: PlaybackException): VideoPlaybackErrorDetails {
            val causes = generateSequence(error.cause) { it.cause }.toList()
            val httpStatus = causes
                .filterIsInstance<InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
            val causeNames = causes
                .map { it.javaClass.simpleName }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_CAUSES)
            val details = buildList {
                add("${error.errorCodeName} (${error.errorCode})")
                httpStatus?.let { add("HTTP $it") }
                if (causeNames.isNotEmpty()) add(causeNames.joinToString(" → "))
            }.joinToString(" · ")
            return VideoPlaybackErrorDetails(classify(error.errorCode), details)
        }

        internal fun classify(errorCode: Int): VideoPlaybackErrorKind = when {
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> VideoPlaybackErrorKind.NETWORK
            errorCode in IO_ERROR_RANGE -> VideoPlaybackErrorKind.NETWORK
            errorCode in MALFORMED_ERROR_RANGE -> VideoPlaybackErrorKind.MALFORMED
            errorCode in UNSUPPORTED_CONTAINER_ERROR_RANGE -> VideoPlaybackErrorKind.UNSUPPORTED
            errorCode in DECODER_ERROR_RANGE -> VideoPlaybackErrorKind.UNSUPPORTED
            else -> VideoPlaybackErrorKind.UNKNOWN
        }

        private const val MAX_CAUSES = 4
        private val IO_ERROR_RANGE = 2_001..2_099
        private val MALFORMED_ERROR_RANGE = 3_001..3_002
        private val UNSUPPORTED_CONTAINER_ERROR_RANGE = 3_003..3_099
        private val DECODER_ERROR_RANGE = 4_001..4_999
    }
}
