package com.example.mattermostlite.ui

internal object DownloadFileName {
    fun sanitize(name: String, fallback: String): String {
        val cleaned = name
            .map { character ->
                when {
                    character == '/' || character == '\\' || character.isISOControl() -> '_'
                    else -> character
                }
            }
            .joinToString("")
            .trim()
            .trim('.')
            .take(MAX_LENGTH)
        return cleaned.ifBlank { fallback.ifBlank { "attachment" } }
    }

    private const val MAX_LENGTH = 180
}
