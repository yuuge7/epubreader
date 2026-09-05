package com.ebookreader.presentation.reader.pdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.ReadingSessionTracker
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private companion object {
        /** How long to wait for the viewer to reach the saved page before giving up. */
        const val RESTORE_TIMEOUT_MS = 10_000L
        /** How often reading time is persisted while the screen stays open. */
        const val PERIODIC_FLUSH_MS = 60_000L
    }

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private val sessionTracker = ReadingSessionTracker()

    private var flushJob: Job? = null
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
    private var userInteracted: Boolean = false

    fun loadBook(id: Long) {
        if (bookId == id && _uiState.value.book != null) return
        bookId = id
        viewModelScope.launch {
            val book = bookRepository.getBookById(id) ?: run {
                _uiState.update { it.copy(error = "Book not found", isLoading = false) }
                return@launch
            }
            currentPage = book.currentPage
            pageToRestore = book.currentPage
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
        viewModelScope.launch {
            bookRepository.updateReadingProgress(bookId, page, totalPages)
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
    fun shouldPinRestoredPage(): Boolean = !userInteracted && pageToRestore > 0

    /** The renderer could not open the file (deleted, corrupt, unsupported). */
    fun onRenderError(message: String) =
        _uiState.update { it.copy(error = message, isLoading = false) }

    fun toggleControls() = _uiState.update { it.copy(showControls = !it.showControls) }
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

    fun onScreenResumed() {
        sessionTracker.resume()
        startPeriodicFlush()
    }

    fun onScreenPaused() {
        stopPeriodicFlush()
        sessionTracker.pause()
        flushSessionTime()
        flushPosition()
    }

    /**
     * Reading time is written out every minute as well as on every pause. Losing at most
     * a minute to a crash or a battery pull is the point; the sitting is extended rather
     * than split, so flushing this often costs nothing in the history.
     */
    private fun startPeriodicFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            while (isActive) {
                delay(PERIODIC_FLUSH_MS)
                flushSessionTime()
            }
        }
    }

    private fun stopPeriodicFlush() {
        flushJob?.cancel()
        flushJob = null
    }

    fun currentSessionSeconds(): Long = sessionTracker.elapsedSeconds()

    private fun flushSessionTime() {
        val seconds = sessionTracker.takeUnsavedSeconds()
        if (seconds <= 0L || bookId <= 0L) return
        appScope.launch { bookRepository.addReadingSeconds(bookId, seconds) }
    }

    private fun flushPosition() {
        if (bookId <= 0L || !positionRestored) return
        val page = currentPage
        val total = currentTotalPages
        appScope.launch { bookRepository.updateReadingProgress(bookId, page, total) }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here, so these run on appScope.
        onScreenPaused()
    }
}
