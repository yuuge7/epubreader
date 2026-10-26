package com.ebookreader.data.epub

import androidx.room.withTransaction
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookTextDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.HighlightDao
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ChapterLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries EPUBs from the legacy chapter numbering to the current one (see [ChapterLayout]):
 * the saved place, every bookmark and every highlight is moved to the chapter that now
 * holds the same file. Offsets inside a chapter need no change, since a legacy chapter
 * already showed its whole file. The search index is rebuilt in the new numbering.
 *
 * Runs for the whole library in the background at start, and for a single book before it
 * opens or before a backup is applied to it, whichever comes first.
 */
@Singleton
class ChapterLayoutMigrator @Inject constructor(
    private val database: AppDatabase,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val bookTextDao: BookTextDao,
    private val parser: EpubParser,
) {
    private val lock = Mutex()

    suspend fun migrateAll() {
        bookDao.getEpubsWithLayoutBefore(ChapterLayout.CURRENT).forEach { ensureCurrent(it.id) }
    }

    /** Makes sure book [bookId] is in the current numbering. */
    suspend fun ensureCurrent(bookId: Long) = lock.withLock {
        val book = bookDao.getBookById(bookId) ?: return@withLock
        if (book.chapterLayout >= ChapterLayout.CURRENT) return@withLock
        if (BookFormat.fromName(book.format) != BookFormat.EPUB) {
            bookDao.setChapterLayout(book.id, ChapterLayout.CURRENT, book.currentPage)
            return@withLock
        }
        // A book that cannot be read now (its file missing, a card unmounted) stays in the
        // legacy numbering and is tried again next time: marking it current without its
        // map would leave its places pointing at the wrong chapters for good.
        val map = withContext(Dispatchers.IO) { parser.legacyChapterMap(book.filePath) } ?: return@withLock
        migrate(book, map)
    }

    /**
     * For each chapter of book [bookId] in the legacy numbering, the chapter it is now:
     * what a backup written before the change says, read on a device that has made it.
     * Null for a book that is not an EPUB or cannot be read.
     */
    suspend fun legacyMap(bookId: Long): IntArray? {
        val book = bookDao.getBookById(bookId) ?: return null
        if (BookFormat.fromName(book.format) != BookFormat.EPUB) return null
        return withContext(Dispatchers.IO) { parser.legacyChapterMap(book.filePath) }
    }

    private suspend fun migrate(book: BookEntity, map: IntArray) {
        database.withTransaction {
            val remap: (Int) -> Int = map::at
            bookDao.setChapterLayout(book.id, ChapterLayout.CURRENT, remap(book.currentPage))
            bookmarkDao.getForBookOnce(book.id).forEach { b ->
                val page = remap(b.page)
                if (page != b.page) bookmarkDao.setPage(b.id, page)
            }
            highlightDao.getForBookOnce(book.id).forEach { h ->
                val chapter = remap(h.chapterIndex)
                if (chapter != h.chapterIndex) highlightDao.setChapter(h.id, chapter)
            }
            // Indexed under the old numbers; setChapterLayout has marked it for indexing again.
            if (book.textIndexed) bookTextDao.deleteForBook(book.id)
        }
    }
}

private fun IntArray.at(index: Int): Int = when {
    isEmpty() -> index
    else -> this[index.coerceIn(0, size - 1)]
}
