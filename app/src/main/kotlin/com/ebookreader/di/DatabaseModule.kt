package com.ebookreader.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.domain.model.SittingSpan
import com.ebookreader.domain.model.planSittingMerge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v3 -> v4
     *  - books.scrollFraction: remembers where inside a chapter/page the reader was.
     *  - reading_sessions: drops the CASCADE foreign key and snapshots the book title,
     *    so deleting a book no longer wipes the reading history it produced.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `scrollFraction` REAL NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reading_sessions_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `bookTitle` TEXT NOT NULL DEFAULT '',
                    `durationSeconds` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `reading_sessions_new` (`id`, `bookId`, `bookTitle`, `durationSeconds`, `timestamp`)
                SELECT s.`id`, s.`bookId`, COALESCE(b.`title`, ''), s.`durationSeconds`, s.`timestamp`
                FROM `reading_sessions` s LEFT JOIN `books` b ON b.`id` = s.`bookId`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `reading_sessions`")
            db.execSQL("ALTER TABLE `reading_sessions_new` RENAME TO `reading_sessions`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_sessions_bookId` ON `reading_sessions` (`bookId`)"
            )
        }
    }

    /**
     * v4 -> v5
     *  - reading_sessions.startedAt: sittings now record when they began, not only when
     *    they were last written.
     *  - Existing history is stitched back together. The reader flushed on every
     *    `ON_PAUSE`, so one evening was stored as a run of fragments — a ten-second row
     *    followed by another beginning the instant it ended. Fragments of the same book
     *    that are within the grace window and on the same day become one sitting.
     *
     * Only durations are summed, so every book's total is exactly what it was before.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `reading_sessions` ADD COLUMN `startedAt` INTEGER NOT NULL DEFAULT 0"
            )
            // The old timestamp was the moment the row was written, i.e. the end of the
            // stretch. Working backwards from it is the best estimate available here.
            db.execSQL(
                "UPDATE `reading_sessions` SET `startedAt` = `timestamp` - `durationSeconds` * 1000"
            )
            mergeFragmentedSittings(db)
        }
    }

    /** Reads the fragments, asks [planSittingMerge] what to do, applies it. */
    private fun mergeFragmentedSittings(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<SittingSpan>()
        db.query(
            "SELECT `id`, `bookId`, `startedAt`, `timestamp`, `durationSeconds` " +
                "FROM `reading_sessions`"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += SittingSpan(
                    id = cursor.getLong(0),
                    bookId = cursor.getLong(1),
                    startedAt = cursor.getLong(2),
                    endedAt = cursor.getLong(3),
                    durationSeconds = cursor.getLong(4)
                )
            }
        }

        val plan = planSittingMerge(rows)

        plan.extended.forEach { sitting ->
            db.execSQL(
                "UPDATE `reading_sessions` SET `durationSeconds` = ?, `timestamp` = ? WHERE `id` = ?",
                arrayOf<Any>(sitting.durationSeconds, sitting.endedAt, sitting.id)
            )
        }
        // Chunked so a long history cannot exceed SQLite's bound-variable limit.
        plan.absorbedIds.chunked(400).forEach { ids ->
            db.execSQL(
                "DELETE FROM `reading_sessions` WHERE `id` IN (" +
                    ids.joinToString(",") { "?" } + ")",
                ids.toTypedArray<Any>()
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ebook_reader_db"
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            // Only the pre-release schemas (1, 2) may be thrown away. Everything from v3 on
            // must migrate: a destructive fallback here deletes the user's whole library,
            // reading progress, bookmarks and stats on an app update.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
            .build()

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideReadingSessionDao(db: AppDatabase): ReadingSessionDao = db.readingSessionDao()
}
