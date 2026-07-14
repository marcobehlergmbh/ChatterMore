package com.example.mattermostlite.ui

internal data class MessageScrollAnchor(
    val itemKey: String,
    val fallbackPosition: Int,
    val topOffset: Int,
) {
    fun resolvePosition(itemCount: Int, keyedPosition: Int?): Int? {
        if (itemCount <= 0) return null
        return keyedPosition?.takeIf { it in 0 until itemCount }
            ?: fallbackPosition.coerceIn(0, itemCount - 1)
    }
}
