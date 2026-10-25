package com.ebookreader.domain.repository

import com.ebookreader.domain.model.BookCollection
import com.ebookreader.domain.model.Highlight
import com.ebookreader.domain.model.TextSearchHit
import kotlinx.coroutines.flow.Flow

interface HighlightRepository {
    fun getHighlights(bookId: Long): Flow<List<Highlight>>
    suspend fun addHighlight(highlight: Highlight): Long
    suspend fun updateHighlight(highlight: Highlight)
    suspend fun deleteHighlight(highlight: Highlight)
}

interface CollectionRepository {
    fun getCollections(): Flow<List<BookCollection>>
    /** Creates a shelf, or returns the one already called [name] (case aside). */
    suspend fun createCollection(name: String): Long
    suspend fun renameCollection(id: Long, name: String)
    suspend fun deleteCollection(id: Long)
    suspend fun addBooks(collectionId: Long, bookIds: List<Long>)
    suspend fun removeBooks(collectionId: Long, bookIds: List<Long>)
}

/** Search inside the text of books, across the library. */
interface BookTextSearch {
    /** Puts [bookId]'s chapters in the index, replacing anything there for it. */
    suspend fun indexBook(bookId: Long)
    /** Indexes every book not yet indexed; for books from before the index existed. */
    suspend fun indexPending()
    suspend fun search(query: String, limit: Int = 60): List<TextSearchHit>
}
