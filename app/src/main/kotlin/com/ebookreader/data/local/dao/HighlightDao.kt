package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ebookreader.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, startOffset")
    fun getForBook(bookId: Long): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insert(highlight: HighlightEntity): Long

    @Update
    suspend fun update(highlight: HighlightEntity)

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    /** One-shot read for sync; the Flow variant never completes. */
    @Query("SELECT * FROM highlights")
    suspend fun getAllOnce(): List<HighlightEntity>

    @Query("UPDATE highlights SET chapterIndex = :chapterIndex WHERE id = :id")
    suspend fun setChapter(id: Long, chapterIndex: Int)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId")
    suspend fun getForBookOnce(bookId: Long): List<HighlightEntity>
}
