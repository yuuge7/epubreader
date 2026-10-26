package com.ebookreader.domain.repository

import com.ebookreader.domain.model.*
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(sortOption: SortOption, descending: Boolean = sortOption.descendingByDefault): Flow<List<Book>>
    fun getFilteredBooks(filter: FilterOption, sort: SortOption, descending: Boolean): Flow<List<Book>>
    fun searchBooks(query: String): Flow<List<Book>>
    fun getFavoriteBooks(): Flow<List<Book>>
    fun getRecentBooks(): Flow<List<Book>>
    /** The book read most recently, finished or not. */
    fun getBooksInCollection(collectionId: Long): Flow<List<Book>>
    suspend fun getBooksInSeries(series: String): List<Book>
    suspend fun getBookById(id: Long): Book?
    suspend fun getBookByFilePath(filePath: String): Book?
    suspend fun getBookByContentHash(hash: String): Book?
    fun observeBook(id: Long): Flow<Book?>
    /** Finished books, most recently finished first. */
    fun getFinishedBooks(): Flow<List<Book>>
    suspend fun getRecentlyRead(limit: Int): List<Book>
    suspend fun updateTitleAndAuthor(bookId: Long, title: String, author: String)
    /** Sets the cover; a replaced cover the app made is deleted. */
    suspend fun updateCover(bookId: Long, coverPath: String?)
    suspend fun updateReadingSettings(bookId: Long, settings: BookReadingSettings?)
    suspend fun insertBook(book: Book): Long
    suspend fun updateBook(book: Book)
    suspend fun deleteBook(book: Book)
    /**
     * Saves where the reader is. [scrollFraction] is the position inside [page]
     * (0f top, 1f bottom) and is only meaningful for EPUB; [progress] is the share of
     * the whole book read, as the reader measures it.
     *
     * This never marks a book FINISHED on its own — call [markBookFinished] for that.
     */
    suspend fun updateReadingProgress(
        bookId: Long,
        page: Int,
        totalPages: Int,
        scrollFraction: Float = 0f,
        progress: Float,
        /** Where the reader is, in words; null leaves the stored label as it is. */
        positionLabel: String? = null,
    )

    /** What the reader measured of a book's text: total, and per chapter in order. */
    suspend fun updateTextMetrics(bookId: Long, textLength: Int, chapterWeights: List<Int>)
    suspend fun updateSeries(bookId: Long, series: String?, seriesIndex: Float?)
    suspend fun updateFavorite(bookIds: List<Long>, isFavorite: Boolean)
    suspend fun getBooksByIds(ids: List<Long>): List<Book>

    suspend fun markBookFinished(bookId: Long, totalPages: Int)
    suspend fun resetReadingProgress(bookId: Long)
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>
    suspend fun addBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(bookmark: Bookmark)
    suspend fun isPageBookmarked(bookId: Long, page: Int): Boolean
    suspend fun getBookmarkByPage(bookId: Long, page: Int): Bookmark?
    suspend fun updateBookmark(bookmark: Bookmark)
    suspend fun addReadingSeconds(bookId: Long, seconds: Long)

    fun getAllReadingSessions(): Flow<List<ReadingSession>>
}
