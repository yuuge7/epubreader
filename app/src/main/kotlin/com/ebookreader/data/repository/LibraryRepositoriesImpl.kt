package com.ebookreader.data.repository

import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.epub.visibleText
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookTextDao
import com.ebookreader.data.local.dao.CollectionDao
import com.ebookreader.data.local.dao.HighlightDao
import com.ebookreader.data.local.entity.BookCollectionEntity
import com.ebookreader.data.local.entity.BookTextEntity
import com.ebookreader.data.local.entity.toColumn
import com.ebookreader.data.local.entity.CollectionEntity
import com.ebookreader.data.local.entity.HighlightEntity
import com.ebookreader.domain.model.BookCollection
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.Highlight
import com.ebookreader.domain.model.TextSearchHit
import com.ebookreader.domain.repository.BookTextSearch
import com.ebookreader.domain.repository.CollectionRepository
import com.ebookreader.domain.repository.HighlightRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HighlightRepositoryImpl @Inject constructor(
    private val dao: HighlightDao,
) : HighlightRepository {
    override fun getHighlights(bookId: Long): Flow<List<Highlight>> =
        dao.getForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addHighlight(highlight: Highlight): Long =
        dao.insert(HighlightEntity.fromDomain(highlight))

    override suspend fun updateHighlight(highlight: Highlight) =
        dao.update(HighlightEntity.fromDomain(highlight))

    override suspend fun deleteHighlight(highlight: Highlight) =
        dao.delete(HighlightEntity.fromDomain(highlight))
}

@Singleton
class CollectionRepositoryImpl @Inject constructor(
    private val dao: CollectionDao,
) : CollectionRepository {
    override fun getCollections(): Flow<List<BookCollection>> =
        dao.getCollections().map { list -> list.map { BookCollection(it.id, it.name, it.bookCount) } }

    override suspend fun createCollection(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A collection needs a name" }
        return dao.findByName(clean)?.id ?: dao.insert(CollectionEntity(name = clean))
    }

    override suspend fun renameCollection(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) dao.rename(id, clean)
    }

    override suspend fun deleteCollection(id: Long) = dao.delete(id)

    override suspend fun addBooks(collectionId: Long, bookIds: List<Long>) =
        dao.addBooks(bookIds.map { BookCollectionEntity(it, collectionId) })

    override suspend fun removeBooks(collectionId: Long, bookIds: List<Long>) {
        if (bookIds.isNotEmpty()) dao.removeBooks(collectionId, bookIds)
    }
}

/**
 * The full-text index, in an FTS4 table. Each chapter file is indexed once, under the
 * first contents entry that points to it, so a file listed by several entries does not
 * show up once per entry.
 */
@Singleton
class BookTextSearchImpl @Inject constructor(
    private val bookDao: BookDao,
    private val textDao: BookTextDao,
    private val epubParser: EpubParser,
) : BookTextSearch {

    /** One book at a time: indexing parses the whole book into memory. */
    private val indexing = Mutex()

    override suspend fun indexBook(bookId: Long) = indexing.withLock {
        val book = bookDao.getBookById(bookId)?.toDomain() ?: return@withLock
        if (book.format != BookFormat.EPUB) {
            bookDao.setTextIndexed(bookId, true)
            return@withLock
        }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val result = epubParser.parseAndExtract(book.filePath)
                val seen = HashSet<String>()
                val rows = result.book.chapters.mapIndexedNotNull { index, chapter ->
                    val path = result.chapterPaths[chapter.id] ?: return@mapIndexedNotNull null
                    if (!seen.add(path)) return@mapIndexedNotNull null
                    val text = visibleText(result.chapterContents.getValue(chapter.id))
                    if (text.isBlank()) null else BookTextEntity(bookId = bookId, chapterIndex = index, content = text)
                }
                rows to result.chapterWeights
            }.getOrNull()
        }
        // bookId is not an indexed column of the FTS table, so this delete reads the whole
        // index: only worth it for a book that is in there.
        if (book.textIndexed) textDao.deleteForBook(bookId)
        parsed?.let { (rows, weights) ->
            textDao.insertAll(rows)
            // Measured here too, so "time left" is known before the book is first opened.
            bookDao.updateTextMetrics(bookId, weights.sum(), weights.toColumn())
        }
        // A book that cannot be parsed is marked too, or it would be retried on every launch.
        bookDao.setTextIndexed(bookId, true)
    }

    override suspend fun indexPending() {
        bookDao.getBooksNeedingTextIndex().forEach { indexBook(it.id) }
    }

    override suspend fun search(query: String, limit: Int): List<TextSearchHit> {
        val match = ftsMatchQuery(query) ?: return emptyList()
        return runCatching {
            textDao.search(
                match,
                TextSearchHit.SNIPPET_OPEN.toString(),
                TextSearchHit.SNIPPET_CLOSE.toString(),
                limit,
            )
        }.getOrDefault(emptyList()).map { TextSearchHit(it.bookId, it.chapterIndex, it.snippet) }
    }
}

/**
 * A user's words as an FTS4 query that cannot fail to parse: every word is matched as a
 * term, the last as a prefix so results come while typing. Operators and quotes in the
 * input are dropped rather than interpreted. Null when nothing searchable is left.
 */
internal fun ftsMatchQuery(input: String): String? {
    val words = input.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return words.mapIndexed { i, w -> if (i == words.lastIndex) "$w*" else w }.joinToString(" ")
}
