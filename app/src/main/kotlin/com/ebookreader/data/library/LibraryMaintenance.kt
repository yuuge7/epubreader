package com.ebookreader.data.library

import com.ebookreader.data.epub.ChapterLayoutMigrator
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.repository.BookTextSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings books imported by older versions up to what import does now: chapters numbered
 * the current way, a rendered first page for PDFs that never had a cover, the blurb, the
 * content hash duplicates are found by, and a place in the full-text index. Runs once per
 * process, in the background; a book it cannot handle is left as it was.
 */
@Singleton
class LibraryMaintenance @Inject constructor(
    private val bookDao: BookDao,
    private val coverMaker: CoverMaker,
    private val textSearch: BookTextSearch,
    private val layouts: ChapterLayoutMigrator,
    private val epubParser: EpubParser,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun runOnce() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            // First: everything after it stores or reads chapter numbers.
            runCatching { layouts.migrateAll() }
            withContext(Dispatchers.IO) {
                bookDao.getBooksWithoutCover(BookFormat.PDF.name).forEach { book ->
                    val file = File(book.filePath)
                    if (file.isFile) coverMaker.pdfCover(file)?.let { bookDao.updateCover(book.id, it) }
                }
                bookDao.getEpubsWithoutDescription().forEach { book ->
                    val meta = epubParser.readMetadata(book.filePath, withCover = false)
                    // Empty, not null: looked for and not there, so it is not looked for again.
                    bookDao.updateDescription(book.id, meta?.description.orEmpty())
                }
                // Converted books are kept as the converted file, which is not what a second
                // import of the original would hash; only files kept as they came can match.
                bookDao.getBooksWithoutHash().forEach { book ->
                    val file = File(book.filePath)
                    if (file.isFile) runCatching { bookDao.updateContentHash(book.id, file.sha256()) }
                }
            }
            textSearch.indexPending()
        }
    }
}
