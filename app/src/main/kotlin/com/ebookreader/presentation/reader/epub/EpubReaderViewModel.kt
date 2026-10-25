package com.ebookreader.presentation.reader.epub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.ChapterWeights
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

/** Where in a chapter to put the reader. */
sealed interface PagePosition {
    /** 0 = top, 1 = bottom. */
    data class Fraction(val value: Float) : PagePosition
    /** An element id (or name) inside the chapter. */
    data class Anchor(val id: String) : PagePosition
}

/**
 * A request to show the open chapter at [position]. [id] tells two identical requests
 * apart, so turning back into a chapter that is already loaded still moves it.
 */
data class PositionRequest(val position: PagePosition, val id: Long)

data class EpubReaderUiState(
    val bookTitle: String = "",
    val chapters: List<EpubChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    /** Where the book is unpacked; the pages are served from here. */
    val bookDir: String? = null,
    val language: String? = null,
    /** Waiting to be applied by the page on screen. */
    val positionRequest: PositionRequest? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val isCurrentChapterBookmarked: Boolean = false,
    val showControls: Boolean = true,
    val showTocSheet: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    val showBookmarkDialog: Boolean = false,
    val showReadingSheet: Boolean = false,
    val bookmarkNote: String = "",
    val totalChapters: Int = 0,
    val isFinished: Boolean = false,
    /** Share of the book read, weighted by how much text each chapter holds. */
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
        const val MISSING_CHAPTER = "<html><head></head><body><p>Chapter content unavailable.</p></body></html>"
    }

    private val _uiState = MutableStateFlow(EpubReaderUiState())
    val uiState: StateFlow<EpubReaderUiState> = _uiState.asStateFlow()

    private val sessionTracker = ReadingSessionTracker()

    private var bookId: Long = -1L
    private var chapterContents: Map<String, String> = emptyMap()
    private var chapterPaths: Map<String, String> = emptyMap()
    private var weights = ChapterWeights(emptyList())

    private var flushJob: Job? = null
    private var currentScrollFraction: Float = 0f
    private var lastPositionSaveMs: Long = 0L
    private var isBookLoaded: Boolean = false
    private var nextRequestId = 0L

    fun loadBook(id: Long) {
        if (bookId == id && isBookLoaded) return
        bookId = id
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
                chapterPaths = result.chapterPaths
                weights = ChapterWeights(result.chapterWeights)

                val savedPage = book.currentPage.coerceIn(
                    0, (result.book.chapters.size - 1).coerceAtLeast(0)
                )
                _uiState.update {
                    it.copy(
                        bookTitle = result.book.title,
                        chapters = result.book.chapters,
                        totalChapters = result.book.chapters.size,
                        currentChapterIndex = savedPage,
                        bookDir = result.extractedDir.absolutePath,
                        language = result.book.language,
                        isLoading = false,
                        isFinished = book.readingStatus == ReadingStatus.FINISHED,
                        totalReadingSeconds = book.totalReadingSeconds
                    )
                }
                isBookLoaded = true
                // Resume exactly where the reader left off: chapter *and* offset in it.
                goToChapter(savedPage, PagePosition.Fraction(book.scrollFraction.coerceIn(0f, 1f)))
                loadBookmarks()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to open EPUB: ${e.message}", isLoading = false) }
            }
        }
    }

    /** The chapter at [index] as the page shows it. Stable for as long as the book is open. */
    fun chapterDocument(index: Int): ChapterDocument? {
        val chapter = _uiState.value.chapters.getOrNull(index) ?: return null
        val path = chapterPaths[chapter.id]
        return ChapterDocument(
            html = chapterContents[chapter.id] ?: MISSING_CHAPTER,
            url = ReaderOrigin.bookUrl(path ?: chapter.href)
        )
    }

    private fun goToChapter(index: Int, position: PagePosition = PagePosition.Fraction(0f)) {
        if (index !in 0 until _uiState.value.totalChapters) return
        currentScrollFraction = (position as? PagePosition.Fraction)?.value?.coerceIn(0f, 1f) ?: 0f
        val request = PositionRequest(position, ++nextRequestId)
        _uiState.update {
            it.copy(
                currentChapterIndex = index,
                positionRequest = request,
                overallReadingProgress = weights.progressAt(index, currentScrollFraction)
            )
        }
        savePosition(force = true)
        viewModelScope.launch { checkChapterBookmarked(index) }
    }

    /**
     * The page for [index] has loaded. If it is the open chapter and has nothing waiting
     * to be applied, it is a new WebView for a chapter that was already open: the
     * activity was recreated (a rotation, a dark-mode switch) while this ViewModel lived
     * on. Without this it would show the chapter from the top, and its first scroll
     * report would overwrite the saved place.
     */
    fun onPageReady(index: Int) {
        val state = _uiState.value
        if (!isBookLoaded || index != state.currentChapterIndex || state.positionRequest != null) return
        _uiState.update {
            it.copy(
                positionRequest = PositionRequest(PagePosition.Fraction(currentScrollFraction), ++nextRequestId)
            )
        }
    }

    fun onPositionApplied(requestId: Long) = _uiState.update {
        if (it.positionRequest?.id == requestId) it.copy(positionRequest = null) else it
    }

    /**
     * Called by the page on screen as the reader scrolls.
     * [fraction] is 0.0 (top) to 1.0 (bottom) of the chapter content.
     */
    fun updateScrollProgress(fraction: Float) {
        if (!isBookLoaded) return
        val f = fraction.coerceIn(0f, 1f)
        currentScrollFraction = f
        val chapter = _uiState.value.currentChapterIndex
        _uiState.update { it.copy(overallReadingProgress = weights.progressAt(chapter, f)) }
        savePosition()

        val isLastChapter = chapter >= _uiState.value.totalChapters - 1
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
        val progress = weights.progressAt(page, fraction)
        // appScope: this must still complete when the screen is being torn down.
        appScope.launch {
            bookRepository.updateReadingProgress(bookId, page, total, fraction, progress)
        }
    }

    /**
     * Called when the page follows a link to another file in the book. Matches the
     * chapter by its path in the book, then by file name for books whose contents list
     * and links disagree about directories.
     */
    fun navigateToInternalLink(path: String, fragment: String?) {
        val chapters = _uiState.value.chapters
        val fileName = path.substringAfterLast("/")
        val index = chapters.indexOfFirst { chapterPaths[it.id].equals(path, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: chapters.indexOfFirst { chapter ->
                chapter.href.substringAfterLast("/").substringBefore("#")
                    .equals(fileName, ignoreCase = true)
            }
        if (index < 0) return
        goToChapter(
            index,
            if (fragment.isNullOrEmpty()) PagePosition.Fraction(0f) else PagePosition.Anchor(fragment)
        )
    }

    fun navigateToChapter(index: Int) {
        val target = index.coerceIn(0, (_uiState.value.totalChapters - 1).coerceAtLeast(0))
        if (target != _uiState.value.currentChapterIndex) goToChapter(target)
        _uiState.update { it.copy(showTocSheet = false) }
    }

    fun nextChapter() {
        val c = _uiState.value.currentChapterIndex
        if (c < _uiState.value.totalChapters - 1) goToChapter(c + 1)
    }

    fun previousChapter() {
        val c = _uiState.value.currentChapterIndex
        if (c > 0) goToChapter(c - 1)
    }

    /**
     * A page turn ran off the end of the chapter. Forwards opens the next one at its top;
     * backwards opens the previous one at its end, where reading back into it continues.
     */
    fun onChapterEdge(direction: Int) {
        val c = _uiState.value.currentChapterIndex
        when {
            direction > 0 && c < _uiState.value.totalChapters - 1 -> goToChapter(c + 1)
            direction < 0 && c > 0 -> goToChapter(c - 1, PagePosition.Fraction(1f))
        }
    }

    fun onSwipe(direction: Int) = if (direction > 0) nextChapter() else previousChapter()

    /**
     * A tap on the page. With the controls up, any tap puts them away; with them down,
     * the centre brings them back and the outer thirds turn the page. Returns whether
     * the page should turn. Safe to call from the WebView's bridge thread.
     */
    fun onPageTap(zone: Int): Boolean {
        var turn = false
        _uiState.update {
            turn = !it.showControls && zone != 0
            when {
                it.showControls -> it.copy(showControls = false)
                zone == 0 -> it.copy(showControls = true)
                else -> it
            }
        }
        return turn
    }

    fun showTocSheet() = _uiState.update { it.copy(showTocSheet = true) }
    fun hideTocSheet() = _uiState.update { it.copy(showTocSheet = false) }
    fun showBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = true) }
    fun hideBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = false) }
    /** The bars go away so the page shows as much text as possible while it is restyled. */
    fun showReadingSheet() = _uiState.update { it.copy(showReadingSheet = true, showControls = false) }
    fun hideReadingSheet() = _uiState.update { it.copy(showReadingSheet = false) }
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
            goToChapter(lastIndex)
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
