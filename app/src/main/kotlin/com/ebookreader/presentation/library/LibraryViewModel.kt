package com.ebookreader.presentation.library

import android.net.Uri
import com.ebookreader.presentation.common.plural
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.library.BookImporter
import com.ebookreader.data.library.LibraryMaintenance
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.BookTextSearch
import com.ebookreader.domain.repository.CollectionRepository
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A place inside a book where the searched-for words occur. */
data class TextHit(val book: Book, val chapterIndex: Int, val snippet: String)

/** The card at the top of the library: the book to pick up, and how long it has left. */
data class Hero(
    val book: Book,
    val kind: Kind,
    val minutesLeftInChapter: Int?,
    val minutesLeftInBook: Int?,
) {
    enum class Kind { CONTINUE, NEXT_IN_SERIES }
}

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    /** Matches inside books' text, for the search query; titles are in [books]. */
    val textHits: List<TextHit> = emptyList(),
    val selectedFilter: FilterOption = FilterOption.ALL,
    /** A shelf being browsed instead of [selectedFilter]. */
    val selectedCollection: Long? = null,
    val collections: List<BookCollection> = emptyList(),
    val selectedSort: SortOption = SortOption.DATE_ADDED,
    val importProgress: Float? = null,
    /** Set when an imported/opened-from-outside book should be opened immediately. */
    val bookToOpen: Book? = null,
    val hero: Hero? = null,
    /** Books picked by a long press; non-empty means the library is in selection mode. */
    val selection: Set<Long> = emptySet(),
    /** One line for the snackbar, then cleared. */
    val message: String? = null,
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val collectionRepository: CollectionRepository,
    private val textSearch: BookTextSearch,
    private val importer: BookImporter,
    maintenance: LibraryMaintenance,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()


    init {
        maintenance.runOnce()

        // Seed sort/filter from the saved settings. They used to live only in memory,
        // so the "Default Sort" setting did nothing and both reset on every launch.
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            _uiState.update {
                it.copy(selectedFilter = settings.filterOption, selectedSort = settings.sortOption)
            }
        }

        viewModelScope.launch {
            // What the shelves show follows from the state alone: filter, sort, search, shelf.
            _uiState.map { Query(it.selectedFilter, it.selectedSort, it.searchQuery, it.selectedCollection) }
                .distinctUntilChanged()
                .flatMapLatest { q ->
                when {
                    q.search.isNotBlank() -> bookRepository.searchBooks(q.search)
                    q.shelf != null -> bookRepository.getBooksInCollection(q.shelf)
                    else -> bookRepository.getFilteredBooks(q.filter, q.sort)
                }
            }.collect { books ->
                _uiState.update { state ->
                    // A book removed elsewhere must not stay selected.
                    val ids = books.mapTo(HashSet()) { it.id }
                    state.copy(books = books, isLoading = false, selection = state.selection.filterTo(HashSet()) { it in ids })
                }
            }
        }

        viewModelScope.launch {
            _uiState.map { it.searchQuery }
                .distinctUntilChanged()
                .debounce(250)
                .mapLatest { query ->
                    if (query.trim().length < 2) emptyList() else {
                        val hits = textSearch.search(query)
                        val books = bookRepository.getBooksByIds(hits.map { it.bookId }.distinct())
                            .associateBy { it.id }
                        hits.mapNotNull { hit -> books[hit.bookId]?.let { TextHit(it, hit.chapterIndex, hit.snippet) } }
                    }
                }
                .collect { hits -> _uiState.update { it.copy(textHits = hits) } }
        }

        viewModelScope.launch {
            collectionRepository.getCollections().collect { shelves ->
                _uiState.update { state ->
                    val stillThere = state.selectedCollection?.takeIf { id -> shelves.any { it.id == id } }
                    state.copy(collections = shelves, selectedCollection = stillThere)
                }
            }
        }

        viewModelScope.launch {
            combine(
                bookRepository.getRecentBooks(),
                bookRepository.getAllBooks(SortOption.DATE_ADDED),
            ) { recent, all -> recent to all }
                .mapLatest { (recent, all) -> heroFor(recent, all) }
                .collect { hero -> _uiState.update { it.copy(hero = hero) } }
        }
    }

    private data class Query(val filter: FilterOption, val sort: SortOption, val search: String, val shelf: Long?)

    /**
     * The next book of a series the reader has just finished, if the library has it;
     * otherwise the book most recently read that is still in progress. Marking some other
     * book finished from the library does not take the card away from the one in hand.
     */
    private suspend fun heroFor(recent: List<Book>, all: List<Book>): Hero? {
        val pace = ReadingPace.charactersPerMinute(all)
        val latest = recent.firstOrNull() ?: return null
        if (latest.readingStatus == ReadingStatus.FINISHED) {
            nextInSeries(latest)?.let { next ->
                return Hero(next, Hero.Kind.NEXT_IN_SERIES, null, ReadingPace.minutesLeftInBook(next, pace))
            }
        }
        val reading = recent.firstOrNull { it.readingStatus == ReadingStatus.READING } ?: return null
        return Hero(
            book = reading,
            kind = Hero.Kind.CONTINUE,
            minutesLeftInChapter = ReadingPace.minutesLeftInChapter(reading, pace),
            minutesLeftInBook = ReadingPace.minutesLeftInBook(reading, pace),
        )
    }

    private suspend fun nextInSeries(finished: Book): Book? {
        val series = finished.series ?: return null
        val books = bookRepository.getBooksInSeries(series)
        val position = finished.seriesIndex
        return books.firstOrNull { candidate ->
            candidate.id != finished.id &&
                candidate.readingStatus != ReadingStatus.FINISHED &&
                (position == null || (candidate.seriesIndex ?: Float.MAX_VALUE) > position)
        }
    }

    // ── Browsing ─────────────────────────────────────────────────────────────

    fun setFilter(filter: FilterOption) {
        _uiState.update { it.copy(selectedFilter = filter, selectedCollection = null) }
        persistSettings { it.copy(filterOption = filter) }
    }

    fun openCollection(id: Long) {
        _uiState.update { it.copy(selectedCollection = id) }
    }

    fun setSort(sort: SortOption) {
        _uiState.update { it.copy(selectedSort = sort) }
        persistSettings { it.copy(sortOption = sort) }
    }

    private fun persistSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.editSettings(transform) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = !it.isSearchActive,
                searchQuery = if (it.isSearchActive) "" else it.searchQuery,
                textHits = if (it.isSearchActive) emptyList() else it.textHits,
            )
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    fun startSelection(book: Book) = _uiState.update { it.copy(selection = setOf(book.id)) }

    fun toggleSelection(book: Book) = _uiState.update {
        it.copy(selection = if (book.id in it.selection) it.selection - book.id else it.selection + book.id)
    }

    fun selectAll() = _uiState.update { s -> s.copy(selection = s.books.mapTo(HashSet()) { it.id }) }

    fun clearSelection() = _uiState.update { it.copy(selection = emptySet()) }

    private fun selectedBooks(): List<Book> {
        val ids = _uiState.value.selection
        return _uiState.value.books.filter { it.id in ids }
    }

    /** Favourites them all, or, when all of them already are, takes the ribbon off. */
    fun favoriteSelected() {
        val books = selectedBooks()
        val makeFavorite = !books.all { it.isFavorite }
        viewModelScope.launch {
            bookRepository.updateFavorite(books.map { it.id }, makeFavorite)
            say(if (makeFavorite) "Added ${books.count()} to favourites" else "Removed ${books.count()} from favourites")
        }
        clearSelection()
    }

    fun markSelectedFinished() {
        val books = selectedBooks()
        viewModelScope.launch {
            books.forEach { bookRepository.markBookFinished(it.id, it.totalPages.coerceAtLeast(1)) }
            say(plural(books.size, "book") + " marked finished")
        }
        clearSelection()
    }

    fun markSelectedUnread() {
        val books = selectedBooks()
        viewModelScope.launch {
            books.forEach { bookRepository.resetReadingProgress(it.id) }
            say(plural(books.size, "book") + " marked unread")
        }
        clearSelection()
    }

    fun deleteSelected() {
        val books = selectedBooks()
        viewModelScope.launch {
            books.forEach { bookRepository.deleteBook(it) }
            say(plural(books.size, "book") + " removed")
        }
        clearSelection()
    }

    fun addSelectedToCollection(collectionId: Long?, newName: String?) {
        val ids = _uiState.value.selection.toList()
        viewModelScope.launch {
            runCatching {
                val target = collectionId ?: collectionRepository.createCollection(newName.orEmpty())
                collectionRepository.addBooks(target, ids)
                val name = _uiState.value.collections.firstOrNull { it.id == target }?.name ?: newName
                say("Added ${plural(ids.size, "book")} to $name")
            }.onFailure { say(it.message ?: "Could not add to the collection") }
        }
        clearSelection()
    }

    fun removeSelectedFromCollection() {
        val shelf = _uiState.value.selectedCollection ?: return
        val ids = _uiState.value.selection.toList()
        viewModelScope.launch { collectionRepository.removeBooks(shelf, ids) }
        clearSelection()
    }

    // ── Shelves ──────────────────────────────────────────────────────────────

    fun createCollection(name: String) {
        viewModelScope.launch {
            runCatching { openCollection(collectionRepository.createCollection(name)) }
                .onFailure { say(it.message ?: "Could not create the collection") }
        }
    }

    fun renameCollection(id: Long, name: String) {
        viewModelScope.launch { collectionRepository.renameCollection(id, name) }
    }

    fun deleteCollection(id: Long) {
        // Leaving the shelf if it was open happens when the shelves come back without it;
        // the reader's saved filter stays as it was.
        viewModelScope.launch { collectionRepository.deleteCollection(id) }
    }

    // ── Single book ──────────────────────────────────────────────────────────

    fun toggleFavorite(book: Book) {
        viewModelScope.launch { bookRepository.updateFavorite(book.id, !book.isFavorite) }
    }

    fun updateBookDetails(book: Book, newTitle: String, newAuthor: String, series: String?, seriesIndex: Float?) {
        viewModelScope.launch {
            // Re-read first: the list snapshot can be stale, and writing the whole row
            // from it would roll back reading progress saved since the screen loaded.
            val fresh = bookRepository.getBookById(book.id) ?: book
            bookRepository.updateBook(fresh.copy(title = newTitle, author = newAuthor))
            bookRepository.updateSeries(book.id, series, seriesIndex)
        }
    }

    fun markAsFinished(book: Book) {
        viewModelScope.launch { bookRepository.markBookFinished(book.id, book.totalPages.coerceAtLeast(1)) }
    }

    fun markAsUnread(book: Book) {
        viewModelScope.launch { bookRepository.resetReadingProgress(book.id) }
    }

    fun consumeBookToOpen() = _uiState.update { it.copy(bookToOpen = null) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun say(message: String) = _uiState.update { it.copy(message = message) }

    // ── Import ───────────────────────────────────────────────────────────────

    /** Import from the picker. */
    fun importBook(uri: Uri) = importBook(uri, openWhenDone = false)

    /**
     * Import triggered by another app ("Open with"). The book is opened as soon as it
     * lands in the library; an already-imported file is opened directly.
     */
    fun importAndOpen(uri: Uri) = importBook(uri, openWhenDone = true)

    private fun importBook(uri: Uri, openWhenDone: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(importProgress = 0f) }
            val result = importer.import(uri) { p -> _uiState.update { it.copy(importProgress = p) } }
            // A book handed over to be read opens without a word; anything else is said.
            val opens = openWhenDone && result.book != null
            _uiState.update {
                it.copy(
                    importProgress = null,
                    message = if (opens) null else result.message,
                    bookToOpen = if (opens) result.book else null,
                )
            }
        }
    }
}

