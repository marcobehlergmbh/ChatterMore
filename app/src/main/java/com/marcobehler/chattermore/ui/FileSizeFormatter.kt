package com.marcobehler.chattermore.ui

import java.text.NumberFormat
import java.util.Locale

internal object FileSizeFormatter {
    private const val UNIT_BASE = 1_000.0
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String? {
        if (bytes < 0) return null
        if (bytes < UNIT_BASE) return "$bytes ${UNITS[0]}"

        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= UNIT_BASE && unitIndex < UNITS.lastIndex) {
            value /= UNIT_BASE
            unitIndex++
        }
        val number = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = if (value < 100) 1 else 0
        }.format(value)
        return "$number ${UNITS[unitIndex]}"
    }
}
