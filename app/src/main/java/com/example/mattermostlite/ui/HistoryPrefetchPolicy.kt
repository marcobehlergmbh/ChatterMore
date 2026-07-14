package com.example.mattermostlite.ui

internal object HistoryPrefetchPolicy {
    const val REMAINING_PERCENT = 20

    fun threshold(itemCount: Int): Int {
        if (itemCount <= 0) return 0
        return ((itemCount * REMAINING_PERCENT) + 99) / 100
    }

    fun shouldPrefetch(firstVisibleItem: Int, itemCount: Int): Boolean =
        firstVisibleItem >= 0 && firstVisibleItem <= threshold(itemCount)
}
