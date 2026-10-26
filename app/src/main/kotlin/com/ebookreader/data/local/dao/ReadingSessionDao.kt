package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ebookreader.data.local.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insertSession(session: ReadingSessionEntity)

    @Insert
    suspend fun insertSessions(sessions: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ReadingSessionEntity>>

    /** The most recent sitting for a book — the candidate to extend rather than replace. */
    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSessionForBook(bookId: Long): ReadingSessionEntity?

    @Query(
        "UPDATE reading_sessions SET durationSeconds = :durationSeconds, timestamp = :endedAt " +
            "WHERE id = :id"
    )
    suspend fun extendSession(id: Long, durationSeconds: Long, endedAt: Long)

    /** One-shot read for export; the Flow variant never completes. */
    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    suspend fun getAllSessionsOnce(): List<ReadingSessionEntity>

    @Query("DELETE FROM reading_sessions")
    suspend fun deleteAllSessions()

    /** Seconds read in sittings that began at or after [since]. */
    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM reading_sessions WHERE startedAt >= :since")
    suspend fun secondsSince(since: Long): Long

    /** Changes whenever a sitting is added, extended or removed: the stats' trigger. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(timestamp), 0) || ':' || COALESCE(SUM(durationSeconds), 0) FROM reading_sessions")
    fun changeKey(): Flow<String>

    /** Seconds per local day, as "yyyy-MM-dd". */
    @Query(
        """
        SELECT date(startedAt / 1000, 'unixepoch', 'localtime') AS day, SUM(durationSeconds) AS seconds
        FROM reading_sessions GROUP BY day
        """
    )
    suspend fun secondsByDay(): List<DayTotal>

    @Query(
        """
        SELECT bookId, MAX(bookTitle) AS bookTitle, SUM(durationSeconds) AS seconds
        FROM reading_sessions WHERE startedAt >= :since
        GROUP BY bookId ORDER BY seconds DESC LIMIT :limit
        """
    )
    suspend fun topBooks(since: Long, limit: Int): List<BookTotal>

    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ReadingSessionEntity>
}

data class DayTotal(val day: String, val seconds: Long)

data class BookTotal(val bookId: Long, val bookTitle: String, val seconds: Long)
