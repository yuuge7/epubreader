package com.ebookreader.domain.model

import java.util.Date

/**
 * One sitting with a book: when it began, when it stopped, and how much of that span was
 * actually spent reading. [startedAt] and [endedAt] can be far apart while
 * [durationSeconds] is much smaller — a sitting survives short interruptions.
 */
data class ReadingSession(
    val id: Long = 0,
    val bookId: Long,
    val bookTitle: String = "",
    val durationSeconds: Long,
    val startedAt: Date = Date(),
    val endedAt: Date = Date()
)

data class BookReadingStat(
    val bookId: Long,
    val bookTitle: String,
    val durationSeconds: Long
)

data class MonthSummary(
    val monthName: String,
    val year: Int,
    val totalSeconds: Long,
    val startTimestamp: Long
)

data class YearSummary(
    val year: Int,
    val totalSeconds: Long,
    val startTimestamp: Long
)
