package com.ebookreader.domain.model

import java.util.Date

data class ReadingSession(
    val id: Long = 0,
    val bookId: Long,
    val bookTitle: String = "",
    val durationSeconds: Long,
    val timestamp: Date = Date()
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
