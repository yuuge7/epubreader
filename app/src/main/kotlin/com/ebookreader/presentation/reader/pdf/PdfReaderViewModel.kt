package com.ebookreader.presentation.reader.pdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PdfReaderUiState(
    val book: Book? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val isCurrentPageBookmarked: Boolean = false,
    val showControls: Boolean = true,
    val showBookmarkDialog: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    val bookmarkNote: String = "",
    val jumpToPage: Int? = null,
)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private var bookId: Long = -1L
    private var currentTotalPages: Int = 0
    private var sessionStartMs: Long = 0L

    fun loadBook(id: Long) {
        bookId = id
        sessionStartMs = System.currentTimeMillis()
        viewModelScope.launch {
            val book = bookRepository.getBookById(id) ?: run {
                _uiState.update { it.copy(error = "Book not found", isLoading = false) }
                return@launch
            }
            _uiState.update { it.copy(book = book, isLoading = false) }
            loadBookmarks()
        }
    }

    fun onPageChanged(page: Int, totalPages: Int) {
        currentTotalPages = totalPages
        viewModelScope.launch {
            bookRepository.updateReadingProgress(bookId, page, totalPages)
            checkPageBookmarked(page)
        }
    }

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

    fun markAsFinished() {
        val total = currentTotalPages.coerceAtLeast(1)
        viewModelScope.launch {
            bookRepository.updateReadingProgress(bookId, total - 1, total)
        }
        _uiState.update { it.copy(jumpToPage = total - 1) }
    }

    fun clearJumpRequest() = _uiState.update { it.copy(jumpToPage = null) }

    /** Called by the screen when the user exits — saves session reading time */
    fun saveSessionTime() {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000L
        if (elapsed > 5) { // only save if read for at least 5 seconds
            viewModelScope.launch {
                bookRepository.addReadingSeconds(bookId, elapsed)
            }
        }
    }

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

    override fun onCleared() {
        super.onCleared()
        saveSessionTime()
    }
}
