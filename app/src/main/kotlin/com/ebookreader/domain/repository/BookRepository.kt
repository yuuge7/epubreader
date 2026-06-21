package com.ebookreader.domain.repository

import com.ebookreader.domain.model.*
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(sortOption: SortOption): Flow<List<Book>>
    fun getFilteredBooks(filter: FilterOption, sort: SortOption): Flow<List<Book>>
    fun searchBooks(query: String): Flow<List<Book>>
    fun getFavoriteBooks(): Flow<List<Book>>
    fun getRecentBooks(): Flow<List<Book>>
    suspend fun getBookById(id: Long): Book?
    suspend fun getBookByFilePath(filePath: String): Book?
    suspend fun insertBook(book: Book): Long
    suspend fun updateBook(book: Book)
    suspend fun deleteBook(book: Book)
    suspend fun updateReadingProgress(bookId: Long, page: Int, totalPages: Int)
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>
    suspend fun addBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(bookmark: Bookmark)
    suspend fun isPageBookmarked(bookId: Long, page: Int): Boolean
    suspend fun getBookmarkByPage(bookId: Long, page: Int): Bookmark?
    suspend fun addReadingSeconds(bookId: Long, seconds: Long)

    fun getAllReadingSessions(): Flow<List<ReadingSession>>
    fun getReadingTimeInRange(startTime: Long, endTime: Long): Flow<Long>
}
