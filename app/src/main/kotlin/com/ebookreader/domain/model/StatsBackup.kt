package com.ebookreader.domain.model

/**
 * A portable snapshot of everything the Stats screen is built from: the finished
 * reading sessions plus each book's accumulated time and position.
 *
 * Books are identified by title + author, never by row id — ids are local
 * autoincrement values and mean nothing on another device or after a reinstall.
 */
data class StatsBackup(
    val exportedAt: Long,
    val appVersion: String,
    val books: List<BookStatsSnapshot>,
    val sessions: List<SessionSnapshot>,
    /** Absent from files written before highlights were carried. */
    val highlights: List<HighlightSnapshot> = emptyList(),
)

/** A highlight, by its book's title and author and its place in a chapter's text. */
data class HighlightSnapshot(
    val bookTitle: String,
    val bookAuthor: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val note: String,
    val color: String,
    val createdAt: Long,
)

data class BookStatsSnapshot(
    val title: String,
    val author: String,
    val format: String,
    val totalPages: Int,
    val currentPage: Int,
    val scrollFraction: Float,
    /** Share of the book read, 0..1. Absent from older files; see the decoder. */
    val progress: Float,
    val readingStatus: String,
    val isFavorite: Boolean,
    val dateAdded: Long,
    val lastRead: Long?,
    val totalReadingSeconds: Long,
    /** "Chapter 4", "Page 12 of 40": absent from older files. */
    val positionLabel: String? = null,
)

data class SessionSnapshot(
    val bookTitle: String,
    val bookAuthor: String,
    val durationSeconds: Long,
    /** When the sitting began. Format 1 files have no such field; see the decoder. */
    val startedAt: Long,
    /** When the sitting ended. */
    val timestamp: Long
)

/** How an imported backup is applied on top of what is already on the device. */
enum class StatsImportMode {
    /** Keep local sessions, add the ones the device does not already have. */
    MERGE,

    /** Drop every local session and use the backup as the whole history. */
    REPLACE
}

data class StatsImportResult(
    val sessionsImported: Int,
    val sessionsSkipped: Int,
    val booksMatched: Int,
    val booksUnmatched: Int,
    val highlightsImported: Int = 0,
)

/**
 * The identity used to line a backup entry up with a local book. Case- and
 * whitespace-insensitive so a re-imported file with a slightly different title
 * case still lands on the same book.
 */
fun bookMatchKey(title: String, author: String): String =
    title.trim().lowercase() + " " + author.trim().lowercase()
