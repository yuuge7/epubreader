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
            progress = :progress,
            positionLabel = COALESCE(:positionLabel, positionLabel),
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
        progress: Float,
        positionLabel: String?,
        lastPageIndex: Int,
        timestamp: Long
    )

    @Query(
        """
        UPDATE books
        SET currentPage = :page, scrollFraction = 1.0, progress = 1.0, lastRead = :timestamp,
            readingStatus = 'FINISHED'
        WHERE id = :bookId
        """
    )
    suspend fun markFinished(bookId: Long, page: Int, timestamp: Long)

    @Query(
        """
        UPDATE books
        SET currentPage = 0, scrollFraction = 0, progress = 0, lastRead = NULL,
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
        SET currentPage = :page, scrollFraction = :scrollFraction, progress = :progress,
            readingStatus = :status, lastRead = :lastRead, isFavorite = :isFavorite,
            positionLabel = :positionLabel
        WHERE id = :bookId
        """
    )
    suspend fun restoreProgress(
        bookId: Long,
        page: Int,
        scrollFraction: Float,
        progress: Float,
        status: String,
        lastRead: Long?,
        isFavorite: Boolean,
        /**
         * Where the reader is, in words. Null clears it: the place has moved, and a file
         * from before labels were kept cannot say what to call the new one.
         */
        positionLabel: String?,
    )

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByFilePath(filePath: String): BookEntity?

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getBooksByIds(ids: List<Long>): List<BookEntity>

    @Query("UPDATE books SET textLength = :textLength, chapterWeights = :chapterWeights WHERE id = :bookId")
    suspend fun updateTextMetrics(bookId: Long, textLength: Int, chapterWeights: String?)

    @Query("UPDATE books SET textIndexed = :indexed WHERE id = :bookId")
    suspend fun setTextIndexed(bookId: Long, indexed: Boolean)

    @Query("SELECT * FROM books WHERE format = 'EPUB' AND textIndexed = 0")
    suspend fun getBooksNeedingTextIndex(): List<BookEntity>

    @Query("SELECT * FROM books WHERE coverPath IS NULL AND format = :format")
    suspend fun getBooksWithoutCover(format: String): List<BookEntity>

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCover(bookId: Long, coverPath: String?)

    @Query("UPDATE books SET series = :series, seriesIndex = :seriesIndex WHERE id = :bookId")
    suspend fun updateSeries(bookId: Long, series: String?, seriesIndex: Float?)

    /** The rest of a series, in reading order. */
    @Query("SELECT * FROM books WHERE series = :series ORDER BY seriesIndex IS NULL, seriesIndex, title")
    suspend fun getBooksInSeries(series: String): List<BookEntity>

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id IN (:ids)")
    suspend fun updateFavorite(ids: List<Long>, isFavorite: Boolean)

    @Query(
        """
        SELECT books.* FROM books
        INNER JOIN book_collections ON book_collections.bookId = books.id
        WHERE book_collections.collectionId = :collectionId
        ORDER BY books.title
        """
    )
    fun getBooksInCollection(collectionId: Long): Flow<List<BookEntity>>
}
