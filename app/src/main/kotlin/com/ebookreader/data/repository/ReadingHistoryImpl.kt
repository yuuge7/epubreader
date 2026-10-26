package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.domain.model.BookReadingStat
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.repository.ReadingHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reading history summed by SQLite rather than in memory: a year of daily sittings is
 * thousands of rows, and the stats screen used to regroup all of them on every write.
 * Days are the finest grain anything shows, so the rows are summed per local day in SQL
 * and months and years are built from those few hundred days.
 */
@Singleton
class ReadingHistoryImpl @Inject constructor(
    private val dao: ReadingSessionDao,
) : ReadingHistory {

    override fun changes(): Flow<Unit> = dao.changeKey().distinctUntilChanged().map { }

    override suspend fun secondsByDay(): Map<LocalDate, Long> =
        dao.secondsByDay().mapNotNull { row ->
            runCatching { LocalDate.parse(row.day) to row.seconds }.getOrNull()
        }.toMap()

    override suspend fun totalSeconds(since: Long): Long = dao.secondsSince(since)

    override suspend fun topBooks(since: Long, limit: Int): List<BookReadingStat> =
        dao.topBooks(since, limit).map { BookReadingStat(it.bookId, it.bookTitle.ifBlank { "Unknown Book" }, it.seconds) }

    override suspend fun recent(limit: Int): List<ReadingSession> = dao.recent(limit).map { it.toSession() }

    override suspend fun allSessions(): List<ReadingSession> = dao.getAllSessionsOnce().map { it.toSession() }
}

internal fun com.ebookreader.data.local.entity.ReadingSessionEntity.toSession() = ReadingSession(
    id = id,
    bookId = bookId,
    bookTitle = bookTitle.ifBlank { "Unknown Book" },
    durationSeconds = durationSeconds,
    startedAt = Date(startedAt.takeIf { it > 0L } ?: (timestamp - durationSeconds * 1000L)),
    endedAt = Date(timestamp),
)
