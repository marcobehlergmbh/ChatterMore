package com.marcobehler.chattermore.ui

internal data class SearchHighlightRange(
    val start: Int,
    val end: Int,
)

internal object MessageSearchHighlighter {
    fun ranges(text: String, terms: List<String>): List<SearchHighlightRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val ranges = mutableListOf<SearchHighlightRange>()
        terms.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .forEach { term ->
                var start = 0
                while (start < text.length && ranges.size < MAX_RANGES) {
                    val match = text.indexOf(term, startIndex = start, ignoreCase = true)
                    if (match < 0) break
                    val end = match + term.length
                    if (ranges.none { match < it.end && end > it.start }) {
                        ranges += SearchHighlightRange(match, end)
                    }
                    start = end.coerceAtLeast(match + 1)
                }
            }
        return ranges.sortedBy(SearchHighlightRange::start)
    }

    private const val MAX_RANGES = 100
}
