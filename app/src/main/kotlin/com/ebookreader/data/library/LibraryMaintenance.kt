package com.ebookreader.data.library

import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.repository.BookTextSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings books imported by older versions up to what import does now: a rendered first
 * page for PDFs that never had a cover, and a place in the full-text index. Runs once
 * per process, in the background; a book it cannot handle is left as it was.
 */
@Singleton
class LibraryMaintenance @Inject constructor(
    private val bookDao: BookDao,
    private val coverMaker: CoverMaker,
    private val textSearch: BookTextSearch,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun runOnce() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            bookDao.getBooksWithoutCover(BookFormat.PDF.name).forEach { book ->
                val file = File(book.filePath)
                if (file.isFile) coverMaker.pdfCover(file)?.let { bookDao.updateCover(book.id, it) }
            }
            textSearch.indexPending()
        }
    }
}
