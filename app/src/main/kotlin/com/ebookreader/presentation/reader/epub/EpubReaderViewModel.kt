package com.ebookreader.presentation.reader.epub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubBook
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class EpubReaderUiState(
    val bookTitle: String = "",
    val chapters: List<EpubChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterHtml: String = "",
    val currentChapterBaseUrl: String = "",
    val pendingScrollAnchor: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val isCurrentChapterBookmarked: Boolean = false,
    val showControls: Boolean = true,
    val showTocSheet: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    val showBookmarkDialog: Boolean = false,
    val bookmarkNote: String = "",
    val totalChapters: Int = 0,
    val fontSize: Float = 16f,
    // Combined progress: (chapterIndex + inChapterScrollFraction) / totalChapters
    val overallReadingProgress: Float = 0f,
    val totalReadingSeconds: Long = 0L
)

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val epubParser: EpubParser
) : ViewModel() {

    private val _uiState = MutableStateFlow(EpubReaderUiState())
    val uiState: StateFlow<EpubReaderUiState> = _uiState.asStateFlow()

    private var bookId: Long = -1L
    private var sessionStartMs: Long = 0L
    private var chapterContents: Map<String, String> = emptyMap()
    private var chapterBaseUrls: Map<String, String> = emptyMap()
    private var opfDirPath: String = ""

    fun loadBook(id: Long, fontSize: Float) {
        bookId = id
        sessionStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(fontSize = fontSize) }
        viewModelScope.launch {
            val book = bookRepository.getBookById(id) ?: run {
                _uiState.update { it.copy(error = "Book not found", isLoading = false) }
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    epubParser.parseAndExtract(book.filePath)
                }
                chapterContents = result.chapterContents
                chapterBaseUrls = result.chapterBaseUrls
                opfDirPath = result.opfDirPath

                val savedPage = book.currentPage.coerceIn(
                    0, (result.book.chapters.size - 1).coerceAtLeast(0)
                )
                _uiState.update {
                    it.copy(
                        bookTitle = result.book.title,
                        chapters = result.book.chapters,
                        totalChapters = result.book.chapters.size,
                        currentChapterIndex = savedPage,
                        isLoading = false,
                        totalReadingSeconds = book.totalReadingSeconds
                    )
                }
                loadChapterContent(savedPage)
                loadBookmarks()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to open EPUB: ${e.message}", isLoading = false) }
            }
        }
    }

    private fun loadChapterContent(chapterIndex: Int) {
        val chapters = _uiState.value.chapters
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return
        val chapter = chapters[chapterIndex]
        val html = chapterContents[chapter.id]
            ?: "<html><body><p>Chapter content unavailable.</p></body></html>"
        val baseUrl = chapterBaseUrls[chapter.id] ?: "file://$opfDirPath/"
        val styledHtml = applyFontSize(html, _uiState.value.fontSize)
        val seedProgress = chapterIndex.toFloat() /
            _uiState.value.totalChapters.coerceAtLeast(1).toFloat()
        _uiState.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentChapterHtml = styledHtml,
                currentChapterBaseUrl = baseUrl,
                pendingScrollAnchor = null,
                overallReadingProgress = seedProgress
            )
        }
        viewModelScope.launch {
            bookRepository.updateReadingProgress(bookId, chapterIndex, chapters.size)
            checkChapterBookmarked(chapterIndex)
        }
    }

    /**
     * Called by WebView JS bridge when the user scrolls within a chapter.
     * [fraction] is 0.0 (top) to 1.0 (bottom) of the chapter content.
     */
    fun updateScrollProgress(fraction: Float) {
        val total = _uiState.value.totalChapters.coerceAtLeast(1).toFloat()
        val chapterIdx = _uiState.value.currentChapterIndex.toFloat()
        val overall = (chapterIdx + fraction.coerceIn(0f, 1f)) / total
        _uiState.update { it.copy(overallReadingProgress = overall) }
    }

    /**
     * Called when the WebView intercepts an internal EPUB link tap.
     * Finds the matching chapter by file path and navigates to it,
     * optionally scrolling to a fragment anchor.
     */
    fun navigateToInternalLink(path: String, fragment: String?) {
        val normalizedPath = path.substringAfterLast("/").substringBefore("#")
        val index = _uiState.value.chapters.indexOfFirst { chapter ->
            chapter.href.substringAfterLast("/").substringBefore("#")
                .equals(normalizedPath, ignoreCase = true)
        }
        if (index >= 0) {
            loadChapterContent(index)
            if (fragment != null) {
                _uiState.update { it.copy(pendingScrollAnchor = fragment) }
            }
        }
    }

    fun clearPendingAnchor() = _uiState.update { it.copy(pendingScrollAnchor = null) }

    fun navigateToChapter(index: Int) {
        loadChapterContent(index.coerceIn(0, (_uiState.value.totalChapters - 1).coerceAtLeast(0)))
        _uiState.update { it.copy(showTocSheet = false) }
    }

    fun nextChapter() {
        val c = _uiState.value.currentChapterIndex
        if (c < _uiState.value.totalChapters - 1) loadChapterContent(c + 1)
    }

    fun previousChapter() {
        val c = _uiState.value.currentChapterIndex
        if (c > 0) loadChapterContent(c - 1)
    }

    fun toggleControls() = _uiState.update { it.copy(showControls = !it.showControls) }
    fun showTocSheet() = _uiState.update { it.copy(showTocSheet = true) }
    fun hideTocSheet() = _uiState.update { it.copy(showTocSheet = false) }
    fun showBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = true) }
    fun hideBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = false) }
    fun showBookmarkDialog() = _uiState.update { it.copy(showBookmarkDialog = true, bookmarkNote = "") }
    fun dismissBookmarkDialog() = _uiState.update { it.copy(showBookmarkDialog = false) }
    fun setBookmarkNote(note: String) = _uiState.update { it.copy(bookmarkNote = note) }

    fun addBookmark() {
        viewModelScope.launch {
            val state = _uiState.value
            val chapter = state.chapters.getOrNull(state.currentChapterIndex)
            bookRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    page = state.currentChapterIndex,
                    chapterId = chapter?.id,
                    chapterTitle = chapter?.title,
                    note = state.bookmarkNote
                )
            )
            _uiState.update { it.copy(showBookmarkDialog = false, isCurrentChapterBookmarked = true) }
            loadBookmarks()
        }
    }

    fun removeBookmarkFromChapter(chapterIndex: Int) {
        viewModelScope.launch {
            bookRepository.getBookmarkByPage(bookId, chapterIndex)?.let {
                bookRepository.deleteBookmark(it)
                _uiState.update { s -> s.copy(isCurrentChapterBookmarked = false) }
                loadBookmarks()
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookRepository.deleteBookmark(bookmark)
            if (bookmark.page == _uiState.value.currentChapterIndex)
                _uiState.update { it.copy(isCurrentChapterBookmarked = false) }
            loadBookmarks()
        }
    }

    fun updateFontSize(fontSize: Float) {
        _uiState.update { it.copy(fontSize = fontSize) }
        loadChapterContent(_uiState.value.currentChapterIndex)
    }

    private fun applyFontSize(html: String, fontSize: Float): String {
        val style = "<style id=\"reader-font-override\">body { font-size: ${fontSize.toInt()}px !important; }</style>"
        return if (html.contains("<head>", ignoreCase = true))
            html.replace(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$style")
        else "<html><head>$style</head><body>$html</body></html>"
    }

    private suspend fun loadBookmarks() {
        bookRepository.getBookmarksForBook(bookId).first().let { bookmarks ->
            _uiState.update { it.copy(bookmarks = bookmarks) }
            checkChapterBookmarked(_uiState.value.currentChapterIndex)
        }
    }

    fun markAsFinished() {
        viewModelScope.launch {
            val total = _uiState.value.totalChapters.coerceAtLeast(1)
            bookRepository.updateReadingProgress(bookId, total - 1, total)
            _uiState.update { it.copy(
                currentChapterIndex = total - 1,
                overallReadingProgress = 1f
            )}
        }
    }

    /** Save accumulated reading time for this session */
    fun saveSessionTime() {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000L
        if (elapsed > 5) {
            viewModelScope.launch {
                bookRepository.addReadingSeconds(bookId, elapsed)
            }
        }
    }

    private suspend fun checkChapterBookmarked(index: Int) {
        _uiState.update {
            it.copy(isCurrentChapterBookmarked = bookRepository.isPageBookmarked(bookId, index))
        }
    }


    override fun onCleared() {
        super.onCleared()
        saveSessionTime()
    }
}
