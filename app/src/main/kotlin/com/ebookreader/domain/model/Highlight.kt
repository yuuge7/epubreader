package com.ebookreader.domain.model

import java.util.Date

/** Four inks, as on a desk: enough to sort passages by, not so many it becomes a palette. */
enum class HighlightColor { YELLOW, GREEN, BLUE, PINK }

/**
 * A passage marked in a book. [startOffset] and [endOffset] count characters of the
 * chapter's visible text, so the mark lands on the same words whatever the type settings.
 */
data class Highlight(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val note: String = "",
    val color: HighlightColor = HighlightColor.YELLOW,
    val createdAt: Date = Date(),
)

data class BookCollection(
    val id: Long = 0,
    val name: String,
    val bookCount: Int = 0,
)

/** A place in a book where searched-for text occurs. */
data class TextSearchHit(
    val bookId: Long,
    val chapterIndex: Int,
    /** The surrounding words, with the match between [SNIPPET_OPEN] and [SNIPPET_CLOSE]. */
    val snippet: String,
) {
    companion object {
        const val SNIPPET_OPEN = '\u0002'
        const val SNIPPET_CLOSE = '\u0003'
    }
}
