package com.ebookreader.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookTextDao
import com.ebookreader.data.local.dao.CollectionDao
import com.ebookreader.data.local.dao.HighlightDao
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

    /**
     * v5 -> v6
     *  - books.progress: the share of the book read, stored rather than derived. EPUB
     *    progress is now weighted by how much text each chapter holds, which the
     *    library cannot work out from a chapter index.
     *
     * Existing rows are seeded with the estimate the app showed until now, so no book's
     * progress jumps on upgrade; the reader replaces it with the weighted figure the next
     * time the book is opened.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `progress` REAL NOT NULL DEFAULT 0")
            db.execSQL(
                """
                UPDATE `books` SET `progress` = CASE
                    WHEN `readingStatus` = 'FINISHED' THEN 1.0
                    WHEN `readingStatus` = 'NOT_STARTED' OR `totalPages` <= 0 THEN 0.0
                    WHEN `format` = 'PDF' THEN MIN(1.0, (`currentPage` + 1) * 1.0 / `totalPages`)
                    ELSE MIN(1.0, (`currentPage` + `scrollFraction`) / `totalPages`)
                END
                """.trimIndent()
            )
        }
    }

    /**
     * v6 -> v7: the library and reading features of phases 4-6.
     *  - books: the text measurements behind "time left" (textLength, chapterWeights),
     *    the place in words (positionLabel), series, the original format of converted
     *    books, and whether the text is in the search index.
     *  - highlights, collections and book_collections: new, start empty.
     *  - book_text: the full-text index, filled in the background after the upgrade.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "`textLength` INTEGER NOT NULL DEFAULT 0",
                "`chapterWeights` TEXT",
                "`positionLabel` TEXT",
                "`series` TEXT",
                "`seriesIndex` REAL",
                "`sourceFormat` TEXT",
                "`textIndexed` INTEGER NOT NULL DEFAULT 0",
            ).forEach { db.execSQL("ALTER TABLE `books` ADD COLUMN $it") }

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `startOffset` INTEGER NOT NULL, " +
                    "`endOffset` INTEGER NOT NULL, `text` TEXT NOT NULL, `note` TEXT NOT NULL DEFAULT '', " +
                    "`color` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookId` ON `highlights` (`bookId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_name` ON `collections` (`name`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `book_collections` (`bookId` INTEGER NOT NULL, " +
                    "`collectionId` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `collectionId`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_collections_collectionId` ON `book_collections` (`collectionId`)"
            )

            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `book_text` USING FTS4(`bookId` INTEGER NOT NULL, " +
                    "`chapterIndex` INTEGER NOT NULL, `content` TEXT NOT NULL, tokenize=unicode61, " +
                    "notindexed=`bookId`, notindexed=`chapterIndex`)"
            )
        }
    }

    /**
     * v7 -> v8: what the remaining library and reader work needed.
     *  - books.chapterLayout: EPUB chapters used to be numbered by contents entry; they
     *    are numbered by file of the reading order now. Existing rows are marked LEGACY (1)
     *    and their positions, bookmarks and highlights are carried across by
     *    [com.ebookreader.data.epub.ChapterLayoutMigrator] the first time it sees them.
     *  - books.description, contentHash, readingSettings: the detail screen, duplicate
     *    detection by content, and settings kept per book. Filled in later, or by the user.
     *  - books.finishedAt: when a book was finished, for the timeline. Finished books get
     *    the last time they were read, which is when they were marked finished in practice.
     *  - bookmarks.scrollFraction, textOffset, excerpt: several bookmarks per chapter, each
     *    knowing its place and showing its first words. Old bookmarks point at the top.
     *  - indices on reading_sessions.startedAt (stats summed in SQL) and books.contentHash.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "`chapterLayout` INTEGER NOT NULL DEFAULT 1",
                "`description` TEXT",
                "`contentHash` TEXT",
                "`readingSettings` TEXT",
                "`finishedAt` INTEGER",
            ).forEach { db.execSQL("ALTER TABLE `books` ADD COLUMN $it") }
            db.execSQL("UPDATE `books` SET `finishedAt` = `lastRead` WHERE `readingStatus` = 'FINISHED'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_contentHash` ON `books` (`contentHash`)")

            listOf(
                "`scrollFraction` REAL NOT NULL DEFAULT 0",
                "`textOffset` INTEGER NOT NULL DEFAULT -1",
                "`excerpt` TEXT NOT NULL DEFAULT ''",
            ).forEach { db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN $it") }

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_sessions_startedAt` ON `reading_sessions` (`startedAt`)"
            )
        }
    }

    /** Every migration, in order; the database tests run them too. */
    val ALL_MIGRATIONS: Array<Migration>
        get() = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

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
            .addMigrations(*ALL_MIGRATIONS)
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

    @Provides
    fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()

    @Provides
    fun provideCollectionDao(db: AppDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideBookTextDao(db: AppDatabase): BookTextDao = db.bookTextDao()
}
