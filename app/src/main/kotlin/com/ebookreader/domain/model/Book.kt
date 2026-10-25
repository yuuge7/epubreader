package com.ebookreader.domain.model

import java.util.Date

enum class BookFormat { PDF, EPUB }
enum class ReadingStatus { NOT_STARTED, READING, FINISHED }
enum class SortOption { TITLE, AUTHOR, DATE_ADDED, LAST_READ }
enum class FilterOption { ALL, PDF, EPUB, FAVORITES, READING, FINISHED }

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
    val totalReadingSeconds: Long = 0L
) {
    val readingProgress: Float
        get() = when {
            readingStatus == ReadingStatus.FINISHED -> 1f
            readingStatus == ReadingStatus.NOT_STARTED -> 0f
            else -> progress.coerceIn(0f, 1f)
        }

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
