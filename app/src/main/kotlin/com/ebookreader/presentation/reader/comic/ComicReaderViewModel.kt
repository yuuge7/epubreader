package com.ebookreader.presentation.reader.comic

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.convert.ComicArchives
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.pageProgress
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.presentation.reader.ReadingTimeRecorder
import com.ebookreader.presentation.reader.pdf.pageLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

data class ComicReaderUiState(
    val book: Book? = null,
    val pageCount: Int = 0,
    /** The page the book opens on; read once, by the pager's initial state. */
    val startPage: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showControls: Boolean = false,
    val isFinished: Boolean = false,
)

/**
 * Reads a CBZ in place: pages are taken out of the archive one at a time as the reader
 * reaches them, into a cache folder for this book only, so a 300-page comic does not
 * have to be unpacked before it opens.
 */
@HiltViewModel
class ComicReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val bookId: Long = savedState["bookId"] ?: -1L

    private val _uiState = MutableStateFlow(ComicReaderUiState())
    val uiState: StateFlow<ComicReaderUiState> = _uiState.asStateFlow()

    private val readingTime = ReadingTimeRecorder(bookRepository, appScope).also { it.bookId = bookId }

    private var zip: ZipFile? = null
    private var pages: List<ZipEntry> = emptyList()
    private val extracting = Mutex()
    private val pageDir = File(context.cacheDir, "comic/$bookId")
    private var currentPage = 0

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val book = bookRepository.getBookById(bookId) ?: run {
            _uiState.update { it.copy(isLoading = false, error = "Book not found") }
            return
        }
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                // Pages of the comic read before this one are of no further use.
                File(context.cacheDir, "comic").listFiles()?.filter { it.name != "$bookId" }?.forEach { it.deleteRecursively() }
                pageDir.mkdirs()
                val file = ZipFile(File(book.filePath))
                file to ComicArchives.pages(file)
            }
        }
        opened.onFailure {
            _uiState.update { it.copy(isLoading = false, error = "This comic could not be opened") }
        }
        val (file, entries) = opened.getOrNull() ?: return
        if (entries.isEmpty()) {
            file.close()
            _uiState.update { it.copy(isLoading = false, error = "There are no pages in this comic") }
            return
        }
        zip = file
        pages = entries
        currentPage = book.currentPage.coerceIn(0, entries.lastIndex)
        _uiState.update {
            it.copy(
                book = book,
                pageCount = entries.size,
                startPage = currentPage,
                isLoading = false,
                isFinished = book.readingStatus == ReadingStatus.FINISHED,
            )
        }
    }

    /** Page [index] as a file the image loader can read, taken out of the archive once. */
    suspend fun pageFile(index: Int): File? = withContext(Dispatchers.IO) {
        val entry = pages.getOrNull(index) ?: return@withContext null
        val ext = entry.name.substringAfterLast('.', "jpg").lowercase()
        val out = File(pageDir, "$index.$ext")
        extracting.withLock {
            if (!out.isFile) runCatching {
                val partial = File(pageDir, "$index.part")
                zip?.getInputStream(entry)?.use { input -> partial.outputStream().use { input.copyTo(it) } }
                partial.renameTo(out)
            }
        }
        out.takeIf { it.isFile }
    }

    fun onPageSettled(page: Int) {
        val total = pages.size
        if (total == 0 || page == currentPage) return
        currentPage = page
        appScope.launch {
            bookRepository.updateReadingProgress(
                bookId, page, total, progress = pageProgress(page, total), positionLabel = pageLabel(page, total),
            )
        }
        if (page == total - 1 && !_uiState.value.isFinished) markAsFinished()
    }

    fun markAsFinished() {
        val total = pages.size.coerceAtLeast(1)
        appScope.launch { bookRepository.markBookFinished(bookId, total) }
        _uiState.update { it.copy(isFinished = true) }
    }

    fun toggleControls() = _uiState.update { it.copy(showControls = !it.showControls) }

    // ── Session time ─────────────────────────────────────────────────────────

    fun onScreenResumed() = readingTime.resume(viewModelScope)
    fun onScreenPaused() = readingTime.pause()
    fun currentSessionSeconds(): Long = readingTime.elapsedSeconds()

    override fun onCleared() {
        super.onCleared()
        readingTime.pause()
        runCatching { zip?.close() }
    }
}
