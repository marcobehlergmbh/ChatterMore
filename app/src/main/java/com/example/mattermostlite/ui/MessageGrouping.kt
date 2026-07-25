package com.example.mattermostlite.ui

import com.example.mattermostlite.model.FeedItem
import java.time.Instant
import java.time.ZoneId

internal object MessageGrouping {
    const val MINUTE_MS = 60_000L
    const val GROUP_WINDOW_MS = 3 * MINUTE_MS

    fun isContinuation(
        previous: FeedItem?,
        current: FeedItem,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean = isContinuation(
        previous = previous,
        current = current,
        sameLocalDay = previous != null &&
            localDay(previous.createAt, zoneId) == localDay(current.createAt, zoneId),
    )

    fun isContinuation(
        previous: FeedItem?,
        current: FeedItem,
        sameLocalDay: Boolean,
    ): Boolean {
        if (previous == null || !sameLocalDay || current.userId.isBlank()) return false
        val elapsed = current.createAt - previous.createAt
        return previous.userId == current.userId && elapsed in 0..GROUP_WINDOW_MS
    }

    fun localDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toEpochDay()

    fun startsNewDay(previousDay: Long?, currentDay: Long): Boolean =
        previousDay == null || previousDay != currentDay
}
