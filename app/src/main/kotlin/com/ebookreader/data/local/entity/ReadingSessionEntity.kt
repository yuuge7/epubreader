package com.ebookreader.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * A finished reading session. Deliberately has **no** foreign key to [BookEntity]:
 * removing a book from the library must not erase the hours you already read.
 * The title is snapshotted here so history survives the delete.
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("bookId")]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    @ColumnInfo(defaultValue = "''")
    val bookTitle: String = "",
    val durationSeconds: Long,
    val timestamp: Long = Date().time
)
