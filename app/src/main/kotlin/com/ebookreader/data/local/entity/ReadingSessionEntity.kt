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
    /** Seconds actually spent reading. Excludes any gaps inside the sitting. */
    val durationSeconds: Long,
    /**
     * When the sitting began. Older rows predate this column and were backfilled from
     * [timestamp] minus the duration, which is the best estimate available for them.
     */
    @ColumnInfo(defaultValue = "0")
    val startedAt: Long = 0L,
    /** When the sitting last stopped. Moves forward each time the sitting is extended. */
    val timestamp: Long = Date().time
)
