package com.ebookreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.ReadingSessionEntity

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, ReadingSessionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
}
