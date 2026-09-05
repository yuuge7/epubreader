package com.ebookreader.presentation.reader.epub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.ReadingSessionTracker
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EpubReaderUiState(
    val bookTitle: String = "",
    val chapters: List<EpubChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterHtml: String = "",
    val currentChapterBaseUrl: String = "",
    val pendingScrollAnchor: String? = null,
    /** Where to scroll once the chapter has rendered (0f..1f), null = leave at top. */
    val pendingRestoreFraction: Float? = null,
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
    val isFinished: Boolean = false,
    // Combined progress: (chapterIndex + inChapterScrollFraction) / totalChapters
    val overallReadingProgress: Float = 0f,
    val totalReadingSeconds: Long = 0L
)

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val epubParser: EpubParser,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private companion object {
        /** Don't hit the DB on every scroll callback. */
        const val POSITION_SAVE_INTERVAL_MS = 2_000L
        /** Scrolled this far into the final chapter counts as "read to the end". */
        const val END_OF_BOOK_FRACTION = 0.98f
        /** How often reading time is persisted while the screen stays open. */
        const val PERIODIC_FLUSH_MS = 60_000L
    }

    private val _uiState = MutableStateFlow(EpubReaderUiState())
    val uiState: StateFlow<EpubReaderUiState> = _uiState.asStateFlow()

    private val sessionTracker = ReadingSessionTracker()

    private var bookId: Long = -1L
    private var chapterContents: Map<String, String> = emptyMap()
    private var chapterBaseUrls: Map<String, String> = emptyMap()
    private var opfDirPath: String = ""

    private var flushJob: Job? = null
    private var currentScrollFraction: Float = 0f
    private var lastPositionSaveMs: Long = 0L
    private var isBookLoaded: Boolean = false

    fun loadBook(id: Long, fontSize: Float) {
        if (bookId == id && isBookLoaded) return
        bookId = id
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
                val savedFraction = book.scrollFraction.coerceIn(0f, 1f)
                _uiState.update {
                    it.copy(
                        bookTitle = result.book.title,
                        chapters = result.book.chapters,
                        totalChapters = result.book.chapters.size,
                        currentChapterIndex = savedPage,
                        isLoading = false,
                        isFinished = book.readingStatus == ReadingStatus.FINISHED,
                        totalReadingSeconds = book.totalReadingSeconds
                    )
                }
                isBookLoaded = true
                // Resume exactly where the reader left off: chapter *and* offset in it.
                loadChapterContent(savedPage, restoreFraction = savedFraction)
                loadBookmarks()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to open EPUB: ${e.message}", isLoading = false) }
            }
        }
    }

    private fun loadChapterContent(chapterIndex: Int, restoreFraction: Float = 0f) {
        val chapters = _uiState.value.chapters
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return
        val chapter = chapters[chapterIndex]
        val html = chapterContents[chapter.id]
            ?: "<html><body><p>Chapter content unavailable.</p></body></html>"
        val baseUrl = chapterBaseUrls[chapter.id] ?: "file://$opfDirPath/"
        val styledHtml = applyFontSize(html, _uiState.value.fontSize)
        val fraction = restoreFraction.coerceIn(0f, 1f)
        currentScrollFraction = fraction
        val seedProgress = (chapterIndex + fraction) /
            _uiState.value.totalChapters.coerceAtLeast(1).toFloat()
        _uiState.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentChapterHtml = styledHtml,
                currentChapterBaseUrl = baseUrl,
                pendingScrollAnchor = null,
                pendingRestoreFraction = fraction.takeIf { f -> f > 0.001f },
                overallReadingProgress = seedProgress
            )
        }
        savePosition(force = true)
        viewModelScope.launch { checkChapterBookmarked(chapterIndex) }
    }

    /**
     * Called by the WebView JS bridge when the user scrolls within a chapter.
     * [fraction] is 0.0 (top) to 1.0 (bottom) of the chapter content.
     */
    fun updateScrollProgress(fraction: Float) {
        if (!isBookLoaded) return
        val f = fraction.coerceIn(0f, 1f)
        currentScrollFraction = f
        val total = _uiState.value.totalChapters.coerceAtLeast(1).toFloat()
        val chapterIdx = _uiState.value.currentChapterIndex.toFloat()
        _uiState.update { it.copy(overallReadingProgress = (chapterIdx + f) / total) }
        savePosition()

        val isLastChapter = _uiState.value.currentChapterIndex >= _uiState.value.totalChapters - 1
        if (isLastChapter && f >= END_OF_BOOK_FRACTION && !_uiState.value.isFinished) {
            markAsFinished(jumpToEnd = false)
        }
    }

    /**
     * Writes the reading position. Throttled, because the WebView reports scrolling
     * dozens of times per second; [force] bypasses the throttle for chapter changes
     * and for leaving the screen.
     */
    private fun savePosition(force: Boolean = false) {
        if (!isBookLoaded || bookId <= 0L) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPositionSaveMs < POSITION_SAVE_INTERVAL_MS) return
        lastPositionSaveMs = now

        val page = _uiState.value.currentChapterIndex
        val total = _uiState.value.totalChapters
        val fraction = currentScrollFraction
        // appScope: this must still complete when the screen is being torn down.
        appScope.launch {
            bookRepository.updateReadingProgress(bookId, page, total, fraction)
        }
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
                // Applied by the screen once the new document has finished loading.
                _uiState.update { it.copy(pendingScrollAnchor = fragment, pendingRestoreFraction = null) }
            }
        }
    }

    fun clearPendingAnchor() = _uiState.update { it.copy(pendingScrollAnchor = null) }
    fun clearPendingRestore() = _uiState.update { it.copy(pendingRestoreFraction = null) }

    fun navigateToChapter(index: Int) {
        val target = index.coerceIn(0, (_uiState.value.totalChapters - 1).coerceAtLeast(0))
        if (target != _uiState.value.currentChapterIndex) loadChapterContent(target)
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
        if (fontSize == _uiState.value.fontSize) return
        _uiState.update { it.copy(fontSize = fontSize) }
        // Re-render the chapter, but come back to the same place in it.
        loadChapterContent(_uiState.value.currentChapterIndex, currentScrollFraction)
    }

    private fun applyFontSize(html: String, fontSize: Float): String {
        // Publisher stylesheets nearly always set font-size on p/div/span, which beats a
        // rule on <body> alone — hence the descendant selector.
        val style = """
            <style id="reader-font-override">
                body { font-size: ${fontSize.toInt()}px !important; }
                body *:not(h1):not(h2):not(h3):not(h4):not(h5):not(h6) {
                    font-size: inherit !important;
                }
            </style>
        """.trimIndent()
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

    fun markAsFinished(jumpToEnd: Boolean = true) {
        val total = _uiState.value.totalChapters.coerceAtLeast(1)
        val lastIndex = total - 1
        appScope.launch { bookRepository.markBookFinished(bookId, total) }
        _uiState.update { it.copy(isFinished = true, overallReadingProgress = 1f) }
        // Tapping "finished" from anywhere in the book must also *show* the ending,
        // otherwise the title bar and the page disagree.
        if (jumpToEnd && _uiState.value.currentChapterIndex != lastIndex) {
            loadChapterContent(lastIndex)
        }
    }

    private suspend fun checkChapterBookmarked(index: Int) {
        _uiState.update {
            it.copy(isCurrentChapterBookmarked = bookRepository.isPageBookmarked(bookId, index))
        }
    }

    // ── Session time ─────────────────────────────────────────────────────────

    fun onScreenResumed() {
        sessionTracker.resume()
        startPeriodicFlush()
    }

    /** Screen backgrounded or closed: stop the clock and persist what was read. */
    fun onScreenPaused() {
        stopPeriodicFlush()
        sessionTracker.pause()
        flushSessionTime()
        savePosition(force = true)
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

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here, so these run on appScope.
        onScreenPaused()
    }
}
