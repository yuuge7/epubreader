package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ebookreader.data.local.entity.BookTextEntity

data class BookTextMatch(
    val bookId: Long,
    val chapterIndex: Int,
    val snippet: String,
)

@Dao
interface BookTextDao {
    @Insert
    suspend fun insertAll(chapters: List<BookTextEntity>)

    @Query("DELETE FROM book_text WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /**
     * Chapters whose text matches [match], an FTS4 query, best first, each with a few
     * words of context around the match marked between [open] and [close].
     */
    @Query(
        """
        SELECT bookId, chapterIndex,
               snippet(book_text, :open, :close, '…', -1, 14) AS snippet
        FROM book_text WHERE book_text MATCH :match
        LIMIT :limit
        """
    )
    suspend fun search(match: String, open: String, close: String, limit: Int): List<BookTextMatch>
}
