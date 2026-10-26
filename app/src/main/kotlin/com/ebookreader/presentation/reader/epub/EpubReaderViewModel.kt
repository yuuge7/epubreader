package com.ebookreader.presentation.reader.epub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.ChapterLayoutMigrator
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.epub.visibleText
import com.ebookreader.data.readaloud.ReadAloudBook
import com.ebookreader.data.readaloud.ReadAloudController
import com.ebookreader.data.readaloud.ReadAloudState
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.BookReadingSettings
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.ChapterWeights
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.Highlight
import com.ebookreader.domain.model.HighlightColor
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.TextFinder
import com.ebookreader.domain.model.TocEntry
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.HighlightRepository
import com.ebookreader.presentation.reader.ReadingTimeRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.abs

/** Where in a chapter to put the reader. */
sealed interface PagePosition {
    /** 0 = top, 1 = bottom. */
    data class Fraction(val value: Float) : PagePosition
    /** An element id (or name) inside the chapter. */
    data class Anchor(val id: String) : PagePosition
    /** The [occurrence]th match of [query] in the chapter's text, marked on the page. */
    data class Search(val query: String, val occurrence: Int = 0) : PagePosition
    /**
     * A character offset into the chapter's text: where a highlight starts (a little way
     * down the screen), or a bookmark (at the top, [atTop]).
     */
    data class TextOffset(val offset: Int, val atTop: Boolean = false) : PagePosition
}

/**
 * A request to show the open chapter at [position]. [id] tells two identical requests
 * apart, so turning back into a chapter that is already loaded still moves it.
 */
data class PositionRequest(val position: PagePosition, val id: Long)

/** A place in this book where searched-for words occur. */
data class InBookHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    /** Which match in the chapter this is, so the page marks the same one. */
    val occurrence: Int,
    val snippet: TextFinder.Snippet,
)

data class BookSearchState(
    val query: String = "",
    val results: List<InBookHit> = emptyList(),
    val isSearching: Boolean = false,
    /** True once a search has run, so "no results" is told apart from "not searched". */
    val searched: Boolean = false,
)

/** A passage to set as a quote card and share. */
data class Quote(val text: String, val title: String, val author: String)

/** A bookmark about to be made: where, and the first words there. */
data class BookmarkDraft(val chapterIndex: Int, val place: PagePlace?, val note: String = "")

data class EpubReaderUiState(
    val bookId: Long = 0,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val highlights: List<Highlight> = emptyList(),
    /** A highlight tapped on the page: its sheet is open. */
    val openHighlight: Highlight? = null,
    /** A highlight whose note is being written. */
    val noteFor: Highlight? = null,
    val showSearch: Boolean = false,
    val search: BookSearchState = BookSearchState(),
    /** Waiting for the screen to render and share. */
    val quote: Quote? = null,
    val chapters: List<EpubChapter> = emptyList(),
    /** The book's own contents, nested. */
    val toc: List<TocEntry> = emptyList(),
    val currentChapterIndex: Int = 0,
    /** Where the book is unpacked; the pages are served from here. */
    val bookDir: String? = null,
    val language: String? = null,
    /** Waiting to be applied by the page on screen. */
    val positionRequest: PositionRequest? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    /** The bookmark on the screen being read, if there is one: the ribbon shows filled. */
    val bookmarkHere: Bookmark? = null,
    val showControls: Boolean = true,
    val showTocSheet: Boolean = false,
    val showBookmarksSheet: Boolean = false,
    /** A bookmark being made, its note being written. */
    val bookmarkDraft: BookmarkDraft? = null,
    val showReadingSheet: Boolean = false,
    val totalChapters: Int = 0,
    val isFinished: Boolean = false,
    /** Share of the book read, weighted by how much text each chapter holds. */
    val overallReadingProgress: Float = 0f,
    /** Where in the open chapter, 0..1, and how much of it a screen shows. */
    val chapterFraction: Float = 0f,
    val screenSpan: Float = 1f,
    val totalReadingSeconds: Long = 0L,
    /** Type and page for this book alone, or null to follow the app. */
    val bookSettings: BookReadingSettings? = null,
    /** The page is scrolling, or turning, by itself. */
    val autoScrolling: Boolean = false,
)

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val highlightRepository: HighlightRepository,
    private val epubParser: EpubParser,
    private val layouts: ChapterLayoutMigrator,
    private val readAloudController: ReadAloudController,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private companion object {
        /** Don't hit the DB on every scroll callback. */
        const val POSITION_SAVE_INTERVAL_MS = 2_000L
        /** Scrolled this far into the final chapter counts as "read to the end". */
        const val END_OF_BOOK_FRACTION = 0.98f
        const val MAX_SEARCH_RESULTS = 200
        const val MISSING_CHAPTER = "<html><head></head><body><p>Chapter content unavailable.</p></body></html>"
    }

    private val _uiState = MutableStateFlow(EpubReaderUiState())
    val uiState: StateFlow<EpubReaderUiState> = _uiState.asStateFlow()

    private val readingTime = ReadingTimeRecorder(bookRepository, appScope)

    private var bookId: Long = -1L
    private var parsed: EpubParser.EpubParseResult? = null
    /** Chapter weights; uniform until the chapters have been measured. */
    private var weights = ChapterWeights(emptyList())
    /**
     * Whether [weights] are the measured ones. Until they are, the progress saved is the
     * one the book had: a figure from uniform weights would overwrite a better one.
     */
    private var weightsKnown = false
    private var storedProgress = 0f

    private var currentScrollFraction: Float = 0f
    private var lastPositionSaveMs: Long = 0L
    private var isBookLoaded: Boolean = false
    private var nextRequestId = 0L
    /** With a screen reader on, the controls stay: there is no tap to bring them back. */
    private var keepControls = false

    /** Reading aloud, for this book or another; the screen shows it only for this one. */
    val readAloud: StateFlow<ReadAloudState> = readAloudController.state

    init {
        // The page follows the voice into the next chapter.
        viewModelScope.launch {
            readAloudController.state
                .filter { it.isActive && it.bookId == bookId && isBookLoaded }
                .map { it.chapterIndex }
                .distinctUntilChanged()
                .collect { chapter -> if (chapter != _uiState.value.currentChapterIndex) goToChapter(chapter) }
        }
    }

    /**
     * @param startChapter open at the top of this chapter instead of the saved place, for
     *   a search hit; -1 for the saved place.
     * @param searchQuery the words that hit, to be found on the page.
     */
    fun loadBook(id: Long, startChapter: Int = -1, searchQuery: String? = null) {
        if (bookId == id && isBookLoaded) return
        bookId = id
        readingTime.bookId = id
        viewModelScope.launch {
            // Positions below are read in the current chapter numbering.
            runCatching { layouts.ensureCurrent(id) }
            val book = bookRepository.getBookById(id) ?: run {
                _uiState.update { it.copy(error = "Book not found", isLoading = false) }
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) { epubParser.parseAndExtract(book.filePath) }
                parsed = result
                val chapters = result.book.chapters
                val known = book.chapterWeights.takeIf { it.size == chapters.size }
                weights = ChapterWeights(known ?: List(chapters.size) { 1 })
                weightsKnown = known != null
                storedProgress = book.progress
                if (known == null) measureChapters(result)

                val savedPage = book.currentPage.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                _uiState.update {
                    it.copy(
                        bookId = id,
                        bookTitle = result.book.title.takeIf { t -> t != "Unknown Title" } ?: book.title,
                        bookAuthor = book.author,
                        chapters = chapters,
                        toc = result.book.toc,
                        totalChapters = chapters.size,
                        currentChapterIndex = savedPage,
                        bookDir = result.extractedDir.absolutePath,
                        language = result.book.language,
                        isLoading = false,
                        isFinished = book.readingStatus == ReadingStatus.FINISHED,
                        totalReadingSeconds = book.totalReadingSeconds,
                        bookSettings = book.readingSettings,
                        overallReadingProgress = book.readingProgress,
                    )
                }
                isBookLoaded = true
                if (startChapter in chapters.indices) {
                    goToChapter(
                        startChapter,
                        searchQuery?.let { PagePosition.Search(it) } ?: PagePosition.Fraction(0f)
                    )
                } else {
                    // Resume exactly where the reader left off: chapter *and* offset in it.
                    goToChapter(savedPage, PagePosition.Fraction(book.scrollFraction.coerceIn(0f, 1f)))
                }
                launch {
                    bookRepository.getBookmarksForBook(id).collect { list ->
                        _uiState.update { it.copy(bookmarks = list) }
                        refreshBookmarkHere()
                    }
                }
                launch {
                    highlightRepository.getHighlights(id).collect { list ->
                        _uiState.update { s ->
                            s.copy(
                                highlights = list,
                                openHighlight = s.openHighlight?.let { open -> list.firstOrNull { it.id == open.id } },
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to open EPUB: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * Weighs the chapters by their text, in the background: the book opens on the saved
     * place straight away, and progress becomes exact a moment later. Kept with the book,
     * so this happens once.
     */
    private fun measureChapters(result: EpubParser.EpubParseResult) {
        viewModelScope.launch {
            val measured = withContext(Dispatchers.IO) { runCatching { result.measureChapters() }.getOrNull() } ?: return@launch
            weights = ChapterWeights(measured)
            weightsKnown = true
            val chapter = _uiState.value.currentChapterIndex
            _uiState.update { it.copy(overallReadingProgress = weights.progressAt(chapter, currentScrollFraction)) }
            appScope.launch { bookRepository.updateTextMetrics(bookId, measured.sum(), measured) }
        }
    }

    /** The chapter at [index] as the page shows it. Stable for as long as the book is open. */
    fun chapterDocument(index: Int): ChapterDocument? {
        val result = parsed ?: return null
        val chapter = _uiState.value.chapters.getOrNull(index) ?: return null
        val path = result.chapterPaths.getOrNull(index)
        return ChapterDocument(
            html = result.chapterHtml(index) ?: MISSING_CHAPTER,
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
                chapterFraction = currentScrollFraction,
                overallReadingProgress = weights.progressAt(index, currentScrollFraction)
            )
        }
        savePosition(force = true)
        refreshBookmarkHere()
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

    // ── Highlights and notes ─────────────────────────────────────────────────

    private var lastColor = HighlightColor.YELLOW

    fun onHighlightTapped(id: Long) = _uiState.update { s ->
        s.copy(openHighlight = s.highlights.firstOrNull { it.id == id })
    }

    fun closeHighlight() = _uiState.update { it.copy(openHighlight = null) }

    /** What the reader picked from the toolbar over selected text. */
    fun onSelection(action: SelectionAction, selection: TextSelection) {
        val text = selection.text.trim()
        if (text.isEmpty()) return
        when (action) {
            SelectionAction.HIGHLIGHT -> addHighlight(selection, openNote = false)
            SelectionAction.NOTE -> addHighlight(selection, openNote = true)
            SelectionAction.QUOTE -> shareQuote(text)
            SelectionAction.SEARCH -> openSearch(text.take(80))
        }
    }

    private fun addHighlight(selection: TextSelection, openNote: Boolean) {
        val chapter = _uiState.value.currentChapterIndex
        viewModelScope.launch {
            val highlight = Highlight(
                bookId = bookId,
                chapterIndex = chapter,
                startOffset = selection.start,
                endOffset = selection.end,
                text = selection.text.trim(),
                color = lastColor,
            )
            val id = highlightRepository.addHighlight(highlight)
            if (openNote) _uiState.update { it.copy(noteFor = highlight.copy(id = id)) }
        }
    }

    fun setHighlightColor(highlight: Highlight, color: HighlightColor) {
        lastColor = color
        viewModelScope.launch { highlightRepository.updateHighlight(highlight.copy(color = color)) }
    }

    fun editNote(highlight: Highlight) = _uiState.update { it.copy(noteFor = highlight, openHighlight = null) }

    fun saveNote(highlight: Highlight, note: String) {
        viewModelScope.launch { highlightRepository.updateHighlight(highlight.copy(note = note.trim())) }
        _uiState.update { it.copy(noteFor = null) }
    }

    fun dismissNote() = _uiState.update { it.copy(noteFor = null) }

    fun deleteHighlight(highlight: Highlight) {
        viewModelScope.launch { highlightRepository.deleteHighlight(highlight) }
        _uiState.update { it.copy(openHighlight = null) }
    }

    fun goToHighlight(highlight: Highlight) {
        _uiState.update { it.copy(showBookmarksSheet = false, openHighlight = null) }
        goToChapter(highlight.chapterIndex, PagePosition.TextOffset(highlight.startOffset))
    }

    fun shareQuote(text: String) = _uiState.update {
        it.copy(quote = Quote(text, it.bookTitle, it.bookAuthor), openHighlight = null)
    }

    fun consumeQuote() = _uiState.update { it.copy(quote = null) }

    // ── Search in the book ───────────────────────────────────────────────────

    /** Each chapter's visible text, made when first searched or read aloud. */
    private val chapterTexts = ConcurrentHashMap<Int, String>()

    private fun chapterText(index: Int): String = chapterTexts.getOrPut(index) {
        visibleText(parsed?.chapterHtml(index).orEmpty())
    }
    private var searchJob: Job? = null

    fun openSearch(query: String? = null) {
        _uiState.update { it.copy(showSearch = true, showControls = keepControls) }
        if (!query.isNullOrBlank()) searchBook(query)
    }

    fun closeSearch() = _uiState.update { it.copy(showSearch = false) }

    fun searchBook(query: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(search = it.search.copy(query = query)) }
        if (query.trim().length < 2) {
            _uiState.update { it.copy(search = BookSearchState(query = query)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(200)
            _uiState.update { it.copy(search = it.search.copy(isSearching = true)) }
            val chapters = _uiState.value.chapters
            val paths = parsed?.chapterPaths.orEmpty()
            val results = withContext(Dispatchers.Default) {
                val seen = HashSet<String>()
                val hits = ArrayList<InBookHit>()
                for ((index, chapter) in chapters.withIndex()) {
                    // A file the spine lists twice is searched once.
                    if (!seen.add(paths.getOrNull(index) ?: chapter.id)) continue
                    val text = chapterText(index)
                    TextFinder.findAll(text, query, limit = MAX_SEARCH_RESULTS - hits.size)
                        .forEachIndexed { occurrence, range ->
                            hits += InBookHit(index, chapter.title, occurrence, TextFinder.snippet(text, range))
                        }
                    if (hits.size >= MAX_SEARCH_RESULTS) break
                }
                hits
            }
            _uiState.update {
                it.copy(search = BookSearchState(query, results, isSearching = false, searched = true))
            }
        }
    }

    fun goToHit(hit: InBookHit) {
        _uiState.update { it.copy(showSearch = false) }
        goToChapter(hit.chapterIndex, PagePosition.Search(_uiState.value.search.query, hit.occurrence))
    }

    fun onPositionApplied(requestId: Long) = _uiState.update {
        if (it.positionRequest?.id == requestId) it.copy(positionRequest = null) else it
    }

    /**
     * Called by the page on screen as the reader scrolls or turns.
     * [fraction] is 0.0 (top) to 1.0 (bottom) of the chapter; [span] how much a screen shows.
     */
    fun updateScrollProgress(fraction: Float, span: Float = _uiState.value.screenSpan) {
        if (!isBookLoaded) return
        val f = fraction.coerceIn(0f, 1f)
        currentScrollFraction = f
        val chapter = _uiState.value.currentChapterIndex
        _uiState.update {
            it.copy(
                overallReadingProgress = weights.progressAt(chapter, f),
                chapterFraction = f,
                screenSpan = span.coerceIn(0.001f, 1f),
            )
        }
        savePosition()
        refreshBookmarkHere()

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
        val progress = if (weightsKnown) weights.progressAt(page, fraction) else storedProgress
        val label = _uiState.value.chapters.getOrNull(page)?.title
        // appScope: this must still complete when the screen is being torn down.
        appScope.launch {
            bookRepository.updateReadingProgress(bookId, page, total, fraction, progress, label)
        }
    }

    /** Called when the page follows a link to another file in the book. */
    fun navigateToInternalLink(path: String, fragment: String?) {
        val index = parsed?.chapterOfPath(path) ?: return
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

    /** An entry of the contents: its chapter, and the place inside it the entry names. */
    fun navigateToEntry(entry: TocEntry) {
        _uiState.update { it.copy(showTocSheet = false) }
        goToChapter(
            entry.chapterIndex,
            entry.fragment?.let { PagePosition.Anchor(it) } ?: PagePosition.Fraction(0f)
        )
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
     * Auto-scroll that runs off the end of the book stops there.
     */
    fun onChapterEdge(direction: Int) {
        val c = _uiState.value.currentChapterIndex
        when {
            direction > 0 && c < _uiState.value.totalChapters - 1 -> goToChapter(c + 1)
            direction < 0 && c > 0 -> goToChapter(c - 1, PagePosition.Fraction(1f))
            direction > 0 -> stopAutoScroll()
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
                it.showControls && !keepControls -> it.copy(showControls = false)
                zone == 0 -> it.copy(showControls = true)
                else -> it
            }
        }
        return turn
    }

    fun setControlsVisible(visible: Boolean) = _uiState.update { it.copy(showControls = visible || keepControls) }

    /** A screen reader came on or went: with it on, the controls never hide. */
    fun setScreenReader(on: Boolean) {
        keepControls = on
        if (on) _uiState.update { it.copy(showControls = true) }
    }

    fun showTocSheet() = _uiState.update { it.copy(showTocSheet = true) }
    fun hideTocSheet() = _uiState.update { it.copy(showTocSheet = false) }
    fun showBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = true) }
    fun hideBookmarksSheet() = _uiState.update { it.copy(showBookmarksSheet = false) }
    /** The bars go away so the page shows as much text as possible while it is restyled. */
    fun showReadingSheet() = _uiState.update { it.copy(showReadingSheet = true, showControls = keepControls) }
    fun hideReadingSheet() = _uiState.update { it.copy(showReadingSheet = false) }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    /**
     * The bookmark that is on the screen now: in the open chapter, at a place this screen
     * shows. Several can sit in one chapter, so the ribbon is about this screen only.
     */
    private fun refreshBookmarkHere() {
        val s = _uiState.value
        val window = (s.screenSpan * 0.6f).coerceAtLeast(0.004f)
        val here = s.bookmarks
            .filter { it.page == s.currentChapterIndex }
            .minByOrNull { abs(it.scrollFraction - s.chapterFraction) }
            ?.takeIf { abs(it.scrollFraction - s.chapterFraction) <= window }
        if (here != s.bookmarkHere) _uiState.update { it.copy(bookmarkHere = here) }
    }

    /** Starts a bookmark at [place], the top of the screen as the page reported it. */
    fun startBookmark(place: PagePlace?) = _uiState.update {
        it.copy(bookmarkDraft = BookmarkDraft(it.currentChapterIndex, place))
    }

    fun setBookmarkNote(note: String) = _uiState.update { s ->
        s.copy(bookmarkDraft = s.bookmarkDraft?.copy(note = note))
    }

    fun dismissBookmarkDialog() = _uiState.update { it.copy(bookmarkDraft = null) }

    fun saveBookmark() {
        val draft = _uiState.value.bookmarkDraft ?: return
        val chapter = _uiState.value.chapters.getOrNull(draft.chapterIndex)
        _uiState.update { it.copy(bookmarkDraft = null) }
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    page = draft.chapterIndex,
                    chapterId = chapter?.id,
                    chapterTitle = chapter?.title,
                    note = draft.note.trim(),
                    scrollFraction = draft.place?.fraction ?: currentScrollFraction,
                    textOffset = draft.place?.offset ?: -1,
                    excerpt = draft.place?.text.orEmpty(),
                )
            )
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { bookRepository.deleteBookmark(bookmark) }
    }

    fun goToBookmark(bookmark: Bookmark) {
        _uiState.update { it.copy(showBookmarksSheet = false) }
        goToChapter(
            bookmark.page,
            if (bookmark.textOffset >= 0) PagePosition.TextOffset(bookmark.textOffset, atTop = true)
            else PagePosition.Fraction(bookmark.scrollFraction)
        )
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

    // ── This book's own settings ─────────────────────────────────────────────

    /** Null goes back to the app's settings. */
    fun setBookSettings(settings: BookReadingSettings?) {
        val clean = settings?.takeUnless { it.isEmpty }
        _uiState.update { it.copy(bookSettings = clean) }
        appScope.launch { bookRepository.updateReadingSettings(bookId, clean) }
    }

    // ── Auto-scroll ──────────────────────────────────────────────────────────

    fun startAutoScroll() = _uiState.update { it.copy(autoScrolling = true, showControls = keepControls) }
    fun stopAutoScroll() = _uiState.update { it.copy(autoScrolling = false) }

    // ── Session time ─────────────────────────────────────────────────────────

    fun onScreenResumed() = readingTime.resume(viewModelScope)

    /** Screen backgrounded or closed: stop the clock and persist what was read. */
    fun onScreenPaused() {
        readingTime.pause()
        savePosition(force = true)
    }

    fun currentSessionSeconds(): Long = readingTime.elapsedSeconds()

    // ── Read aloud ───────────────────────────────────────────────────────────

    /** Starts reading this book aloud from the open chapter, at [fromText] if it is found. */
    fun startReadAloud(fromText: String?, rate: Float) {
        val state = _uiState.value
        if (!isBookLoaded || state.chapters.isEmpty()) return
        // Holds on to this ViewModel only while reading aloud: closing the book stops it.
        val chapters = state.chapters
        val paths = parsed?.chapterPaths.orEmpty()
        fun fileOf(index: Int) = paths.getOrNull(index) ?: chapters.getOrNull(index)?.id
        val book = object : ReadAloudBook {
            override val bookId = this@EpubReaderViewModel.bookId
            override val title = state.bookTitle
            override val author = state.bookAuthor
            override val language = state.language
            override val chapterCount = chapters.size
            override fun chapterTitle(index: Int) = chapters.getOrNull(index)?.title.orEmpty()
            // A file the spine lists twice in a row is read once.
            override suspend fun chapterText(index: Int) =
                if (index > 0 && fileOf(index) == fileOf(index - 1)) ""
                else withContext(Dispatchers.Default) { this@EpubReaderViewModel.chapterText(index) }
        }
        readAloudController.start(book, state.currentChapterIndex, fromText, rate)
    }

    fun toggleReadAloud() = readAloudController.toggle()
    fun skipSentence(by: Int) = readAloudController.skip(by)
    fun stopReadAloud() = readAloudController.stop()
    fun setReadAloudRate(rate: Float) = readAloudController.setRate(rate)
    fun clearReadAloudError() = readAloudController.clearError()

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here, so these run on appScope.
        onScreenPaused()
        // Closing the book ends reading it aloud.
        if (readAloudController.state.value.bookId == bookId) readAloudController.stop()
    }
}
