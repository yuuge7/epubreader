package com.ebookreader

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database migrations on the device's own SQLite, checked by Room against the
 * exported schema of each version: a migration that leaves a column, an index or a
 * default different from what the entities declare fails here, not on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun v7_to_v8_keeps_the_library_and_marks_epubs_for_the_new_chapter_numbering() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, filePath, format, totalPages, currentPage, isFavorite, " +
                    "readingStatus, dateAdded, lastRead, fileSize, totalReadingSeconds, scrollFraction, progress, textLength, textIndexed) " +
                    "VALUES (1, 'Emma', 'Jane Austen', '/x/emma.epub', 'EPUB', 40, 7, 0, 'FINISHED', 1, 5000, 10, 3600, 0.5, 1.0, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO bookmarks (id, bookId, page, chapterTitle, note, dateCreated) VALUES (1, 1, 7, 'VII', 'here', 2)"
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true, *DatabaseModule.ALL_MIGRATIONS).use { db ->
            db.query("SELECT chapterLayout, finishedAt, description, contentHash, currentPage FROM books WHERE id = 1").use { c ->
                c.moveToFirst()
                // Legacy numbering until the migrator carries the positions over.
                assertEquals(1, c.getInt(0))
                // Finished books get the last time they were read as the day they finished.
                assertEquals(5000L, c.getLong(1))
                assertNull(c.getString(2))
                assertNull(c.getString(3))
                assertEquals(7, c.getInt(4))
            }
            db.query("SELECT page, scrollFraction, textOffset, excerpt FROM bookmarks WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(7, c.getInt(0))
                assertEquals(0f, c.getFloat(1))
                assertEquals(-1, c.getInt(2))
                assertEquals("", c.getString(3))
            }
        }
    }

    @Test
    fun every_version_since_v4_migrates_to_the_current_one() {
        for (version in 4..7) {
            val name = "$dbName-$version"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, 8, true, *DatabaseModule.ALL_MIGRATIONS).close()
        }
    }
}
