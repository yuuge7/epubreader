package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.ReadingSessionEntity
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val readingSessionDao: ReadingSessionDao
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

    override suspend fun deleteBook(book: Book) =
        bookDao.deleteBook(BookEntity.fromDomain(book))

    override suspend fun updateReadingProgress(bookId: Long, page: Int, totalPages: Int) {
        val status = when {
            totalPages > 0 && page >= totalPages - 1 -> ReadingStatus.FINISHED
            else -> ReadingStatus.READING
        }
        bookDao.updateReadingProgress(bookId, page, Date().time, status.name)
        if (totalPages > 0) {
            bookDao.updateTotalPages(bookId, totalPages)
        }
    }

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

    override suspend fun addReadingSeconds(bookId: Long, seconds: Long) {
        if (seconds > 0) {
            bookDao.addReadingSeconds(bookId, seconds)
            readingSessionDao.insertSession(
                ReadingSessionEntity(bookId = bookId, durationSeconds = seconds)
            )
        }
    }

    override fun getAllReadingSessions(): Flow<List<ReadingSession>> {
        return combine(
            readingSessionDao.getAllSessions(),
            bookDao.getAllBooks()
        ) { sessions, books ->
            val bookMap = books.associateBy { it.id }
            sessions.map { entity ->
                ReadingSession(
                    id = entity.id,
                    bookId = entity.bookId,
                    bookTitle = bookMap[entity.bookId]?.title ?: "Unknown Book",
                    durationSeconds = entity.durationSeconds,
                    timestamp = Date(entity.timestamp)
                )
            }
        }
    }

    override fun getReadingTimeInRange(startTime: Long, endTime: Long): Flow<Long> =
        readingSessionDao.getReadingTimeInRange(startTime, endTime).map { it ?: 0L }
}
