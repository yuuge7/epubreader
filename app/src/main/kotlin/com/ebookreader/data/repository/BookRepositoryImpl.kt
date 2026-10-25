package com.ebookreader.data.repository

import android.content.Context
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.ReadingSessionEntity
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val readingSessionDao: ReadingSessionDao,
    private val epubParser: EpubParser,
    @ApplicationContext private val context: Context
) : BookRepository {

    override fun getAllBooks(sortOption: SortOption): Flow<List<Book>> {
        return when (sortOption) {
            SortOption.TITLE -> bookDao.getAllBooksSortedByTitle()
            SortOption.AUTHOR -> bookDao.getAllBooksSortedByAuthor()
            SortOption.DATE_ADDED -> bookDao.getAllBooks()
            SortOption.LAST_READ -> bookDao.getAllBooksSortedByLastRead()
        }.map { entities -> entities.map { it.toDomain() } }
    }

    override fun getFilteredBooks(filter: FilterOption, sort: SortOption): Flow<List<Book>> {
        return when (filter) {
            FilterOption.ALL -> getAllBooks(sort)
            FilterOption.PDF -> bookDao.getBooksByFormat(BookFormat.PDF.name)
                .map { it.map { entity -> entity.toDomain() } }
            FilterOption.EPUB -> bookDao.getBooksByFormat(BookFormat.EPUB.name)
                .map { it.map { entity -> entity.toDomain() } }
            FilterOption.FAVORITES -> getFavoriteBooks()
            FilterOption.READING -> bookDao.getBooksByStatus(ReadingStatus.READING.name)
                .map { it.map { entity -> entity.toDomain() } }
            FilterOption.FINISHED -> bookDao.getBooksByStatus(ReadingStatus.FINISHED.name)
                .map { it.map { entity -> entity.toDomain() } }
        }
    }

    override fun searchBooks(query: String): Flow<List<Book>> =
        bookDao.searchBooks(query).map { it.map { entity -> entity.toDomain() } }

    override fun getFavoriteBooks(): Flow<List<Book>> =
        bookDao.getFavoriteBooks().map { it.map { entity -> entity.toDomain() } }

    override fun getRecentBooks(): Flow<List<Book>> =
        bookDao.getRecentBooks().map { it.map { entity -> entity.toDomain() } }

    override suspend fun getBookById(id: Long): Book? =
        bookDao.getBookById(id)?.toDomain()

    override suspend fun getBookByFilePath(filePath: String): Book? =
        bookDao.getBookByFilePath(filePath)?.toDomain()

    override suspend fun insertBook(book: Book): Long =
        bookDao.insertBook(BookEntity.fromDomain(book))

    override suspend fun updateBook(book: Book) =
        bookDao.updateBook(BookEntity.fromDomain(book))

    /**
     * Removes the book and everything the app created for it.
     *
     * Bookmarks go with it through the foreign key; reading sessions deliberately do not,
     * so the history a book produced survives its removal. The files are not covered by
     * either rule and used to be left behind forever: the copy made at import, the
     * extracted cover, and the EPUB extraction cache.
     */
    override suspend fun deleteBook(book: Book) {
        bookDao.deleteBook(BookEntity.fromDomain(book))
        withContext(Dispatchers.IO) {
            // Only ever delete inside the app's own storage. If a future version starts
            // referencing books where the user keeps them, this must not reach out and
            // delete the original.
            deleteIfOwned(book.filePath)
            book.coverPath?.let(::deleteIfOwned)
            runCatching { epubParser.clearCache(book.filePath) }
        }
    }

    /** Deletes [path] only when it lives under the app's private files directory. */
    private fun deleteIfOwned(path: String) {
        runCatching {
            val target = File(path).canonicalFile
            val owned = context.filesDir.canonicalFile.toPath()
            if (target.toPath().startsWith(owned)) target.delete()
        }
    }

    override suspend fun updateReadingProgress(
        bookId: Long,
        page: Int,
        totalPages: Int,
        scrollFraction: Float
    ) {
        bookDao.updateReadingPosition(
            bookId = bookId,
            page = page.coerceAtLeast(0),
            scrollFraction = scrollFraction.coerceIn(0f, 1f),
            lastPageIndex = (totalPages - 1).coerceAtLeast(0),
            timestamp = Date().time
        )
        if (totalPages > 0) {
            bookDao.updateTotalPages(bookId, totalPages)
        }
    }

    override suspend fun markBookFinished(bookId: Long, totalPages: Int) {
        val lastPage = (totalPages - 1).coerceAtLeast(0)
        bookDao.markFinished(bookId, lastPage, Date().time)
        if (totalPages > 0) {
            bookDao.updateTotalPages(bookId, totalPages)
        }
    }

    override suspend fun resetReadingProgress(bookId: Long) = bookDao.resetProgress(bookId)

    override suspend fun updateFavorite(bookId: Long, isFavorite: Boolean) =
        bookDao.updateFavorite(bookId, isFavorite)

    override fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarksForBook(bookId).map { it.map { entity -> entity.toDomain() } }

    override suspend fun addBookmark(bookmark: Bookmark): Long =
        bookmarkDao.insertBookmark(BookmarkEntity.fromDomain(bookmark))

    override suspend fun deleteBookmark(bookmark: Bookmark) =
        bookmarkDao.deleteBookmark(BookmarkEntity.fromDomain(bookmark))

    override suspend fun isPageBookmarked(bookId: Long, page: Int): Boolean =
        bookmarkDao.isPageBookmarked(bookId, page) > 0

    override suspend fun getBookmarkByPage(bookId: Long, page: Int): Bookmark? =
        bookmarkDao.getBookmarkByPage(bookId, page)?.toDomain()

    /**
     * Records a stretch of reading time.
     *
     * The reader flushes often — every `ON_PAUSE`, and once a minute while it is open —
     * because that is what makes an unexpected kill lose nothing. Writing a row per flush
     * is what turned one evening into a list of fragments, so a stretch that continues the
     * sitting already in progress extends that row instead of starting another. See
     * [ReadingSessionPolicy].
     */
    override suspend fun addReadingSeconds(bookId: Long, seconds: Long) {
        if (seconds <= 0) return
        val now = System.currentTimeMillis()
        val open = readingSessionDao.getLatestSessionForBook(bookId)

        if (open != null &&
            ReadingSessionPolicy.continuesSitting(open.startedAt, open.timestamp, now)
        ) {
            bookDao.addReadingSeconds(bookId, seconds)
            readingSessionDao.extendSession(
                id = open.id,
                durationSeconds = open.durationSeconds + seconds,
                endedAt = now
            )
            return
        }

        // Nothing to attach to. A stretch this short on its own is someone opening a book
        // and backing straight out, which should leave no trace at all — including in the
        // book's running total, so the total and the history cannot disagree.
        if (seconds < ReadingSessionPolicy.MIN_SESSION_SECONDS) return

        bookDao.addReadingSeconds(bookId, seconds)
        readingSessionDao.insertSession(
            ReadingSessionEntity(
                bookId = bookId,
                // Snapshot the title so the session survives the book being removed.
                bookTitle = bookDao.getBookById(bookId)?.title ?: "Unknown Book",
                durationSeconds = seconds,
                startedAt = now - seconds * 1000L,
                timestamp = now
            )
        )
    }

    override fun getAllReadingSessions(): Flow<List<ReadingSession>> =
        readingSessionDao.getAllSessions().map { sessions ->
            sessions.map { entity ->
                ReadingSession(
                    id = entity.id,
                    bookId = entity.bookId,
                    bookTitle = entity.bookTitle.ifBlank { "Unknown Book" },
                    durationSeconds = entity.durationSeconds,
                    // Rows written before startedAt existed were backfilled by the v4 -> v5
                    // migration; a zero here would only survive a hand-edited database.
                    startedAt = Date(
                        entity.startedAt.takeIf { it > 0L }
                            ?: (entity.timestamp - entity.durationSeconds * 1000L)
                    ),
                    endedAt = Date(entity.timestamp)
                )
            }
        }
}
