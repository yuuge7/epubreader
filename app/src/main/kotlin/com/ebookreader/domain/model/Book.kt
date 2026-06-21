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
            totalPages > 0 -> ((currentPage + 1).toFloat() / totalPages).coerceIn(0f, 1f)
            else -> 0f
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
