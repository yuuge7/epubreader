package com.ebookreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookTextDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.CollectionDao
import com.ebookreader.data.local.dao.HighlightDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.data.local.entity.BookCollectionEntity
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookTextEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.CollectionEntity
import com.ebookreader.data.local.entity.HighlightEntity
import com.ebookreader.data.local.entity.ReadingSessionEntity

@Database(
    entities = [
        BookEntity::class, BookmarkEntity::class, ReadingSessionEntity::class,
        HighlightEntity::class, CollectionEntity::class, BookCollectionEntity::class,
        BookTextEntity::class,
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun highlightDao(): HighlightDao
    abstract fun collectionDao(): CollectionDao
    abstract fun bookTextDao(): BookTextDao
}
