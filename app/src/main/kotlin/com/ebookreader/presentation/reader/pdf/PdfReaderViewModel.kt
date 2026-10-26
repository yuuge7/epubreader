package com.ebookreader.presentation.reader.pdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.pageProgress
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.presentation.reader.ReadingTimeRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PdfReaderUiState(
    val book: Book? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val isCurrentPageBookmarked: Boolean = false,
    val isFinished: Boolean = false,
    val showControls: Boolean = true,
    val showBookmarkDialog: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    val bookmarkNote: String = "",
    val jumpToPage: Int? = null,
    val showThumbnails: Boolean = false,
    /** The share of each page's width its text takes, for cropping margins; null until measured. */
    val contentBounds: PdfContentBounds? = null,
)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /** Page pictures for the thumbnail grid and for measuring margins; open while the book is. */
    var thumbnails: PdfThumbnails? = null
        private set

    private companion object {
        /** How long to wait for the viewer to reach the saved page before giving up. */
        const val RESTORE_TIMEOUT_MS = 10_000L
    }

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private val readingTime = ReadingTimeRecorder(bookRepository, appScope)

    private var bookId: Long = -1L
    private var currentPage: Int = 0
    private var currentTotalPages: Int = 0

    /**
     * The viewer opens at page 0 and only reaches the saved page once the renderer is
     * ready. Until then its page callbacks describe the *restore*, not the reader, and
     * writing them would overwrite the saved position with 0.
     */
    private var positionRestored: Boolean = false
    private var pageToRestore: Int = 0
    /** Where in the restored page the reader was, 0 top .. 1 bottom. */
    private var fractionToRestore: Float = 0f
    private var userInteracted: Boolean = false

    /**
     * The first page on screen and how far down it the screen starts. Saved instead of the
     * page alone, so a tall page reopens on the paragraph that was being read.
     */
    private var scrollPage: Int = -1
    private var scrollFraction: Float = 0f

    fun loadBook(id: Long) {
        if (bookId == id && _uiState.value.book != null) return
        bookId = id
        readingTime.bookId = id
        viewModelScope.launch {
            val book = bookRepository.getBookById(id) ?: run {
                _uiState.update { it.copy(error = "Book not found", isLoading = false) }
                return@launch
            }
            currentPage = book.currentPage
            pageToRestore = book.currentPage
            // 1 is "read to the end" (marking a book finished writes it), which for a page
            // would put the screen's top past its bottom edge.
            fractionToRestore = book.scrollFraction.takeIf { it in 0f..0.99f } ?: 0f
            thumbnails?.close()
            thumbnails = PdfThumbnails.open(java.io.File(book.filePath))
            currentTotalPages = book.totalPages
            positionRestored = book.currentPage <= 0
            if (!positionRestored) {
                // Safety net: if the file changed and the saved page no longer exists,
                // no callback will ever reach it. Without this the book would stop
                // recording progress entirely.
                viewModelScope.launch {
                    delay(RESTORE_TIMEOUT_MS)
                    positionRestored = true
                }
            }
            _uiState.update {
                it.copy(
                    book = book,
                    isLoading = false,
                    isFinished = book.readingStatus == ReadingStatus.FINISHED
                )
            }
            loadBookmarks()
        }
    }

    /** Page the viewer should open on. */
    fun startPage(): Int = pageToRestore

    /** How far down [startPage] to put the top of the screen. */
    fun startFraction(): Float = fractionToRestore

    /**
     * The list scrolled: [page] is the first page on screen, [fraction] how far down it the
     * top of the screen is. Only kept once the reader has taken over from the restore.
     */
    fun onScrollPosition(page: Int, fraction: Float) {
        if (!userInteracted) return
        scrollPage = page
        scrollFraction = fraction.coerceIn(0f, 1f)
    }

    /** The place to save: the exact one when known, else the top of the current page. */
    private fun savedPlace(page: Int): Pair<Int, Float> =
        if (scrollPage >= 0 && kotlin.math.abs(scrollPage - page) <= 1) scrollPage to scrollFraction else page to 0f

    fun onPageChanged(page: Int, totalPages: Int) {
        currentTotalPages = totalPages
        if (!positionRestored) {
            // The viewer starts at page 0 and reports its way up to the restored page as
            // it lays out. Saving any of that would drag the stored position backwards,
            // a little further on every reopen. Only the target itself ends the wait.
            if (page >= pageToRestore) {
                positionRestored = true
                currentPage = page
            }
            return
        }
        // Until the reader actually touches the screen, nothing that happens can be
        // reading. The list slides backwards on its own while pages render (placeholder
        // rows are short, so the layout manager scrolls back to fill the viewport), and
        // saving that would walk the position backwards on every reopen. Opening a book
        // and closing it untouched must leave the saved page exactly as it was.
        if (!userInteracted && page != pageToRestore) return
        currentPage = page
        val (savePage, fraction) = savedPlace(page)
        viewModelScope.launch {
            bookRepository.updateReadingProgress(
                bookId, savePage, totalPages, scrollFraction = fraction, progress = pageProgress(page, totalPages),
                positionLabel = pageLabel(page, totalPages),
            )
            checkPageBookmarked(page)
        }
        if (totalPages > 0 && page >= totalPages - 1 && !_uiState.value.isFinished) {
            markAsFinished(jumpToEnd = false)
        }
    }

    /**
     * The reader touched the screen, so anything that happens next is intentional.
     * Ends the post-restore pinning immediately so it can never fight a deliberate scroll.
     */
    fun onUserInteraction() {
        userInteracted = true
        positionRestored = true
    }

    /**
     * Whether the restored page should still be re-asserted. The viewer drifts backwards
     * while page bitmaps load, so the jump is repeated until the layout stops moving —
     * but never once the reader has taken over.
     */
    fun shouldPinRestoredPage(fraction: Float = 0f): Boolean =
        !userInteracted && (pageToRestore > 0 || fraction > 0f)

    /** The renderer could not open the file (deleted, corrupt, unsupported). */
    fun onRenderError(message: String) =
        _uiState.update { it.copy(error = message, isLoading = false) }

    fun toggleControls() = _uiState.update { it.copy(showControls = !it.showControls) }
    fun showThumbnails() = _uiState.update { it.copy(showThumbnails = true) }
    fun hideThumbnails() = _uiState.update { it.copy(showThumbnails = false) }

    /** A page picked from the grid. */
    fun jumpTo(page: Int) {
        onUserInteraction()
        _uiState.update { it.copy(jumpToPage = page, showThumbnails = false) }
    }

    /** Measures where the text sits on the pages, once, for cropping the margins. */
    fun measureMargins() {
        if (_uiState.value.contentBounds != null) return
        val thumbs = thumbnails ?: return
        viewModelScope.launch {
            val bounds = thumbs.contentBounds()
            _uiState.update { it.copy(contentBounds = bounds) }
        }
    }
    fun showBookmarkDialog() = _uiState.update { it.copy(showBookmarkDialog = true, bookmarkNote = "") }
    fun dismissBookmarkDialog() = _uiState.update { it.copy(showBookmarkDialog = false) }
    fun showBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = true) }
    fun hideBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = false) }
    fun setBookmarkNote(note: String) = _uiState.update { it.copy(bookmarkNote = note) }

    fun addBookmark(currentPage: Int) {
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(bookId = bookId, page = currentPage, note = _uiState.value.bookmarkNote)
            )
            _uiState.update { it.copy(showBookmarkDialog = false, isCurrentPageBookmarked = true) }
            loadBookmarks()
        }
    }

    fun removeBookmarkFromPage(page: Int) {
        viewModelScope.launch {
            bookRepository.getBookmarkByPage(bookId, page)?.let {
                bookRepository.deleteBookmark(it)
                _uiState.update { s -> s.copy(isCurrentPageBookmarked = false) }
                loadBookmarks()
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookRepository.deleteBookmark(bookmark)
            loadBookmarks()
        }
    }

    fun markAsFinished(jumpToEnd: Boolean = true) {
        val total = currentTotalPages.coerceAtLeast(1)
        appScope.launch { bookRepository.markBookFinished(bookId, total) }
        _uiState.update { it.copy(isFinished = true) }
        if (jumpToEnd) _uiState.update { it.copy(jumpToPage = total - 1) }
    }

    fun clearJumpRequest() = _uiState.update { it.copy(jumpToPage = null) }

    private suspend fun loadBookmarks() {
        bookRepository.getBookmarksForBook(bookId).first().let { bookmarks ->
            _uiState.update { it.copy(bookmarks = bookmarks) }
        }
    }

    private suspend fun checkPageBookmarked(page: Int) {
        _uiState.update {
            it.copy(isCurrentPageBookmarked = bookRepository.isPageBookmarked(bookId, page))
        }
    }

    // ── Session time ─────────────────────────────────────────────────────────

    fun onScreenResumed() = readingTime.resume(viewModelScope)

    fun onScreenPaused() {
        readingTime.pause()
        flushPosition()
    }

    fun currentSessionSeconds(): Long = readingTime.elapsedSeconds()

    private fun flushPosition() {
        if (bookId <= 0L || !positionRestored) return
        val page = currentPage
        val total = currentTotalPages
        val (savePage, fraction) = savedPlace(page)
        appScope.launch {
            bookRepository.updateReadingProgress(
                bookId, savePage, total, scrollFraction = fraction,
                progress = pageProgress(page, total), positionLabel = pageLabel(page, total)
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here, so these run on appScope.
        onScreenPaused()
        thumbnails?.close()
        thumbnails = null
    }
}

/** "Page 12 of 40", as the library shows where a paged book was left. */
internal fun pageLabel(page: Int, totalPages: Int): String =
    if (totalPages > 0) "Page ${page + 1} of $totalPages" else "Page ${page + 1}"
