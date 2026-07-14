package com.example.mattermostlite.ui

import com.example.mattermostlite.model.FeedItem

internal object MessageGrouping {
    const val MINUTE_MS = 60_000L

    fun isContinuation(previous: FeedItem?, current: FeedItem): Boolean =
        previous != null &&
            current.userId.isNotBlank() &&
            previous.userId == current.userId &&
            previous.createAt / MINUTE_MS == current.createAt / MINUTE_MS
}
