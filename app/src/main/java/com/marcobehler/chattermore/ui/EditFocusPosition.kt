package com.marcobehler.chattermore.ui

internal fun editFocusTopOffset(
    originalTop: Int,
    itemHeight: Int,
    viewportHeight: Int,
    paddingTop: Int,
    paddingBottom: Int,
): Int {
    val lowestFullyVisibleTop = (
        viewportHeight - paddingBottom - itemHeight
    ).coerceAtLeast(paddingTop)
    return minOf(originalTop, lowestFullyVisibleTop)
}

internal fun nextEditFocusTop(previousTargetTop: Int, calculatedTargetTop: Int): Int =
    minOf(previousTargetTop, calculatedTargetTop)
