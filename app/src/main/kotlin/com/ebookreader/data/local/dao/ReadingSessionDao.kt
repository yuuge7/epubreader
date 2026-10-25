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
}
