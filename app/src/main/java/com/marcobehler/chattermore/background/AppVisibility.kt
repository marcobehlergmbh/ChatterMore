package com.marcobehler.chattermore.background

import java.util.concurrent.atomic.AtomicBoolean

object AppVisibility {
    private val foreground = AtomicBoolean(false)

    val isForeground: Boolean
        get() = foreground.get()

    fun setForeground(value: Boolean) {
        foreground.set(value)
    }
}

