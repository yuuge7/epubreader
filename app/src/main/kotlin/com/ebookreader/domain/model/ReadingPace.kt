package com.ebookreader.domain.model

import kotlin.math.ceil

/**
 * How fast this reader reads, learned from their own books, and how long that leaves in
 * a chapter or a book. The app counts time spent reading, so it can say "18 min left in
 * this chapter" from the reader's own history rather than from a generic average.
 *
 * Text is measured in visible, non-space characters, the same measure the reader weighs
 * chapters with ([Book.textLength], [Book.chapterWeights]).
 */
object ReadingPace {
    /** About 200 words a minute; the guess until there is history to learn from. */
    const val DEFAULT_CHARS_PER_MINUTE = 1000f

    /** Below 60 words a minute the book sat open while nobody read it. */
    private const val MIN_CHARS_PER_MINUTE = 300f
    /** Past 1000 words a minute it was skimmed, jumped through, or marked finished unread. */
    private const val MAX_CHARS_PER_MINUTE = 5000f
    /** A book needs this much reading before its pace is trusted. */
    private const val MIN_SECONDS = 300L

    /** Characters per minute over every book with a believable pace, weighted by time. */
    fun charactersPerMinute(books: List<Book>): Float {
        var chars = 0.0
        var seconds = 0L
        for (book in books) {
            if (book.format != BookFormat.EPUB || book.textLength <= 0) continue
            if (book.totalReadingSeconds < MIN_SECONDS) continue
            val read = book.readingProgress.toDouble() * book.textLength
            val pace = read / (book.totalReadingSeconds / 60.0)
            if (pace < MIN_CHARS_PER_MINUTE || pace > MAX_CHARS_PER_MINUTE) continue
            chars += read
            seconds += book.totalReadingSeconds
        }
        return if (seconds == 0L) DEFAULT_CHARS_PER_MINUTE else (chars / (seconds / 60.0)).toFloat()
    }

    /** Minutes left in the whole book, or null when its text has not been measured. */
    fun minutesLeftInBook(book: Book, charsPerMinute: Float): Int? {
        if (book.readingStatus == ReadingStatus.FINISHED) return 0
        return when (book.format) {
            BookFormat.EPUB -> {
                if (book.textLength <= 0) return null
                minutes(book.textLength * (1f - book.readingProgress), charsPerMinute)
            }
            // Paged books: this book's own minutes per page, once there is enough of it.
            else -> {
                if (book.totalPages <= 0) return null
                val perPage = minutesPerPage(book) ?: return null
                ceil((book.totalPages - book.currentPage - 1).coerceAtLeast(0) * perPage).toInt()
            }
        }
    }

    /** A paged book's own pace, from the pages reached so far; null until it has enough time. */
    fun minutesPerPage(book: Book): Float? {
        if (book.totalReadingSeconds < MIN_SECONDS) return null
        return book.totalReadingSeconds / 60f / (book.currentPage + 1)
    }

    /** Minutes left in the chapter being read, or null without per-chapter measurements. */
    fun minutesLeftInChapter(book: Book, charsPerMinute: Float): Int? {
        val weights = book.chapterWeights
        if (book.format != BookFormat.EPUB || book.textLength <= 0 || weights.isEmpty()) return null
        val total = weights.sum()
        if (total <= 0) return null
        val chapter = book.currentPage.coerceIn(0, weights.lastIndex)
        val chapterChars = book.textLength.toFloat() * weights[chapter] / total
        return minutes(chapterChars * (1f - book.scrollFraction.coerceIn(0f, 1f)), charsPerMinute)
    }

    private fun minutes(chars: Float, charsPerMinute: Float): Int =
        ceil(chars.coerceAtLeast(0f) / charsPerMinute.coerceAtLeast(1f)).toInt()
}
