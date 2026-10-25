package com.ebookreader.domain.model

import java.util.Date

/**
 * How a book is read. Formats the app converts at import (TXT, Markdown, FB2, MOBI,
 * AZW3) become [EPUB]; CBR comics become [COMIC], like CBZ.
 */
enum class BookFormat {
    PDF, EPUB, COMIC;

    companion object {
        /** A stored name this build does not know reads as EPUB rather than crashing. */
        fun fromName(name: String): BookFormat = entries.firstOrNull { it.name == name } ?: EPUB
    }
}

enum class ReadingStatus { NOT_STARTED, READING, FINISHED }
enum class SortOption { TITLE, AUTHOR, DATE_ADDED, LAST_READ }
enum class FilterOption { ALL, PDF, EPUB, COMIC, FAVORITES, READING, FINISHED }

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val format: BookFormat,
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /** Scroll position inside [currentPage]: 0f = top of page/chapter, 1f = bottom. */
    val scrollFraction: Float = 0f,
    /**
     * Share of the book read, 0..1, as the reader last measured it. For EPUB it is
     * weighted by how much text each chapter holds, so it cannot be derived from
     * [currentPage] and [totalPages] alone.
     */
    val progress: Float = 0f,
    val isFavorite: Boolean = false,
    val readingStatus: ReadingStatus = ReadingStatus.NOT_STARTED,
    val dateAdded: Date = Date(),
    val lastRead: Date? = null,
    val fileSize: Long = 0L,
    val totalReadingSeconds: Long = 0L,
    /** Visible characters in the whole book, as the reader measured them; 0 = not yet. */
    val textLength: Int = 0,
    /** How much text each chapter holds, in reading order; empty until measured. */
    val chapterWeights: List<Int> = emptyList(),
    /** Where the reader is, in words: a chapter title, or "Page 12 of 40". */
    val positionLabel: String? = null,
    val series: String? = null,
    /** Place in [series]; fractional for novellas slotted between books (2.5). */
    val seriesIndex: Float? = null,
    /** What the file was before import converted it, if it was: TXT, MD, FB2, MOBI... */
    val sourceFormat: String? = null,
    /** Whether the text is in the full-text search index. */
    val textIndexed: Boolean = false,
) {
    val readingProgress: Float
        get() = when {
            readingStatus == ReadingStatus.FINISHED -> 1f
            readingStatus == ReadingStatus.NOT_STARTED -> 0f
            else -> progress.coerceIn(0f, 1f)
        }

    /** The label for the file type: the original one for a converted book. */
    val formatLabel: String get() = sourceFormat ?: format.name

    val formattedReadingTime: String
        get() {
            if (totalReadingSeconds <= 0L) return ""
            val h = totalReadingSeconds / 3600
            val m = (totalReadingSeconds % 3600) / 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m"
                else -> "<1m"
            }
        }
}

/** Progress through a paged book: the page you are on counts as reached, so 1/100 is 1%. */
fun pageProgress(page: Int, totalPages: Int): Float =
    if (totalPages <= 0) 0f else ((page + 1).toFloat() / totalPages).coerceIn(0f, 1f)

/** "3", or "2.5" for a book between two others. */
fun formatSeriesIndex(index: Float): String =
    if (index % 1f == 0f) index.toInt().toString() else index.toString()

/** "Discworld 3", or the series alone when the book has no number in it. */
fun seriesLabel(series: String?, index: Float?): String? =
    series?.let { s -> index?.let { "$s ${formatSeriesIndex(it)}" } ?: s }

val Book.seriesLabel: String? get() = seriesLabel(series, seriesIndex)
