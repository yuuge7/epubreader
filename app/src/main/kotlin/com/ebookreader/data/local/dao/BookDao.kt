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

    /**
     * Persists the reading position. Status becomes READING, except for a book already
     * marked FINISHED that is still sitting on its last page — re-opening a finished book
     * to glance at the ending must not silently un-finish it.
     */
    @Query(
        """
        UPDATE books
        SET currentPage = :page,
            scrollFraction = :scrollFraction,
            lastRead = :timestamp,
            readingStatus = CASE
                WHEN readingStatus = 'FINISHED' AND :page >= :lastPageIndex THEN 'FINISHED'
                ELSE 'READING'
            END
        WHERE id = :bookId
        """
    )
    suspend fun updateReadingPosition(
        bookId: Long,
        page: Int,
        scrollFraction: Float,
        lastPageIndex: Int,
        timestamp: Long
    )

    @Query(
        """
        UPDATE books
        SET currentPage = :page, scrollFraction = 1.0, lastRead = :timestamp,
            readingStatus = 'FINISHED'
        WHERE id = :bookId
        """
    )
    suspend fun markFinished(bookId: Long, page: Int, timestamp: Long)

    @Query(
        """
        UPDATE books
        SET currentPage = 0, scrollFraction = 0, lastRead = NULL,
            readingStatus = 'NOT_STARTED'
        WHERE id = :bookId
        """
    )
    suspend fun resetProgress(bookId: Long)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    @Query("UPDATE books SET totalPages = :totalPages WHERE id = :bookId")
    suspend fun updateTotalPages(bookId: Long, totalPages: Int)

    @Query("UPDATE books SET totalReadingSeconds = totalReadingSeconds + :seconds WHERE id = :bookId")
    suspend fun addReadingSeconds(bookId: Long, seconds: Long)

    /** Overwrites the running total. Used by a REPLACE stats import, which rebuilds it from sessions. */
    @Query("UPDATE books SET totalReadingSeconds = :seconds WHERE id = :bookId")
    suspend fun setReadingSeconds(bookId: Long, seconds: Long)

    /** One-shot read for stats export/import matching; the Flow variants never complete. */
    @Query("SELECT * FROM books")
    suspend fun getAllBooksOnce(): List<BookEntity>

    /**
     * Restores a reading position from a stats backup. Deliberately touches only the
     * progress columns — filePath, coverPath and fileSize describe *this* device's copy
     * of the file and must survive the import.
     */
    @Query(
        """
        UPDATE books
        SET currentPage = :page, scrollFraction = :scrollFraction,
            readingStatus = :status, lastRead = :lastRead, isFavorite = :isFavorite
        WHERE id = :bookId
        """
    )
    suspend fun restoreProgress(
        bookId: Long,
        page: Int,
        scrollFraction: Float,
        status: String,
        lastRead: Long?,
        isFavorite: Boolean
    )

    @Query("SELECT COUNT(*) FROM books")
    fun getBookCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByFilePath(filePath: String): BookEntity?
}
