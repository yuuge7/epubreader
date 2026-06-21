package com.ebookreader.data.local.dao

import androidx.room.*
import com.ebookreader.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY lastRead DESC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE lastRead IS NOT NULL ORDER BY lastRead DESC LIMIT 20")
    fun getRecentBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE format = :format ORDER BY dateAdded DESC")
    fun getBooksByFormat(format: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE readingStatus = :status ORDER BY lastRead DESC")
    fun getBooksByStatus(status: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooksSortedByTitle(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY author ASC")
    fun getAllBooksSortedByAuthor(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY CASE WHEN lastRead IS NULL THEN 1 ELSE 0 END, lastRead DESC")
    fun getAllBooksSortedByLastRead(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("UPDATE books SET currentPage = :page, lastRead = :timestamp, readingStatus = :status WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: Long, page: Int, timestamp: Long, status: String)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    @Query("UPDATE books SET totalPages = :totalPages WHERE id = :bookId")
    suspend fun updateTotalPages(bookId: Long, totalPages: Int)

    @Query("UPDATE books SET totalReadingSeconds = totalReadingSeconds + :seconds WHERE id = :bookId")
    suspend fun addReadingSeconds(bookId: Long, seconds: Long)

    @Query("SELECT COUNT(*) FROM books")
    fun getBookCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByFilePath(filePath: String): BookEntity?
}
