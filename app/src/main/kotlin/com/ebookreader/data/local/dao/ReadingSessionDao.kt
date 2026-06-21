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

    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getSessionsForBook(bookId: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE timestamp >= :startTime")
    fun getTotalReadingTimeSince(startTime: Long): Flow<Long?>

    @Query("SELECT SUM(durationSeconds) FROM reading_sessions WHERE timestamp >= :startTime AND timestamp <= :endTime")
    fun getReadingTimeInRange(startTime: Long, endTime: Long): Flow<Long?>
}
