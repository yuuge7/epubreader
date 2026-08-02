package com.ebookreader.presentation.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedFilter: FilterOption = FilterOption.ALL,
    val selectedSort: SortOption = SortOption.DATE_ADDED,
    val importProgress: Float? = null,
    /** Set when an imported/opened-from-outside book should be opened immediately. */
    val bookToOpen: Book? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val epubParser: EpubParser,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val filterFlow = MutableStateFlow(FilterOption.ALL)
    private val sortFlow = MutableStateFlow(SortOption.DATE_ADDED)
    private val searchFlow = MutableStateFlow("")

    init {
        // Seed sort/filter from the saved settings. They used to live only in memory,
        // so the "Default Sort" setting did nothing and both reset on every launch.
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            filterFlow.value = settings.filterOption
            sortFlow.value = settings.sortOption
            _uiState.update {
                it.copy(selectedFilter = settings.filterOption, selectedSort = settings.sortOption)
            }
        }

        viewModelScope.launch {
            combine(filterFlow, sortFlow, searchFlow) { filter, sort, search ->
                Triple(filter, sort, search)
            }.flatMapLatest { (filter, sort, search) ->
                if (search.isNotBlank()) {
                    bookRepository.searchBooks(search)
                } else {
                    bookRepository.getFilteredBooks(filter, sort)
                }
            }.collect { books ->
                _uiState.update { it.copy(books = books, isLoading = false) }
            }
        }
    }

    fun setFilter(filter: FilterOption) {
        filterFlow.value = filter
        _uiState.update { it.copy(selectedFilter = filter) }
        persistSettings { it.copy(filterOption = filter) }
    }

    fun setSort(sort: SortOption) {
        sortFlow.value = sort
        _uiState.update { it.copy(selectedSort = sort) }
        persistSettings { it.copy(sortOption = sort) }
    }

    private fun persistSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val current = settingsRepository.getSettings().first()
            settingsRepository.updateSettings(transform(current))
        }
    }

    fun setSearchQuery(query: String) {
        searchFlow.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = !it.isSearchActive,
                searchQuery = if (it.isSearchActive) "" else it.searchQuery
            )
        }
        if (!_uiState.value.isSearchActive) {
            searchFlow.value = ""
        }
    }

    fun toggleFavorite(book: Book) {
        viewModelScope.launch {
            bookRepository.updateFavorite(book.id, !book.isFavorite)
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
        }
    }

    fun updateBookDetails(book: Book, newTitle: String, newAuthor: String) {
        viewModelScope.launch {
            // Re-read first: the list snapshot can be stale, and writing the whole row
            // from it would roll back reading progress saved since the screen loaded.
            val fresh = bookRepository.getBookById(book.id) ?: book
            bookRepository.updateBook(fresh.copy(title = newTitle, author = newAuthor))
        }
    }

    fun markAsFinished(book: Book) {
        viewModelScope.launch {
            bookRepository.markBookFinished(book.id, book.totalPages.coerceAtLeast(1))
        }
    }

    fun markAsUnread(book: Book) {
        viewModelScope.launch {
            bookRepository.resetReadingProgress(book.id)
        }
    }

    fun consumeBookToOpen() = _uiState.update { it.copy(bookToOpen = null) }

    /** Import from the picker. */
    fun importBook(uri: Uri) = importBook(uri, openWhenDone = false)

    /**
     * Import triggered by another app ("Open with"). The book is opened as soon as it
     * lands in the library; an already-imported file is opened directly.
     */
    fun importAndOpen(uri: Uri) = importBook(uri, openWhenDone = true)

    private fun importBook(uri: Uri, openWhenDone: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(importProgress = 0f) }

                val contentResolver = context.contentResolver
                // File I/O and ContentResolver queries must stay off the main thread —
                // copying a large book here used to freeze the UI long enough to ANR.
                val imported = withContext<ImportResult>(Dispatchers.IO) {
                    val mimeType = contentResolver.getType(uri) ?: ""
                    val format = when {
                        mimeType.contains("pdf") || uri.path?.endsWith(".pdf", true) == true ->
                            BookFormat.PDF
                        mimeType.contains("epub") || uri.path?.endsWith(".epub", true) == true ->
                            BookFormat.EPUB
                        else -> return@withContext ImportResult.Unsupported
                    }

                    var fileName = "Unknown"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                    fileName = fileName.replace(Regex("[/\\\\]"), "_")

                    val booksDir = File(context.filesDir, "books").also { it.mkdirs() }
                    val destFile = uniqueDestination(booksDir, fileName)

                    bookRepository.getBookByFilePath(destFile.absolutePath)?.let {
                        return@withContext ImportResult.AlreadyImported(it)
                    }

                    val totalBytes = contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { it.length }
                        ?.takeIf { it > 0 } ?: -1L

                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalBytes > 0) {
                                    val progress = (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                                    _uiState.update { it.copy(importProgress = progress) }
                                }
                            }
                        }
                    } ?: return@withContext ImportResult.Failed("Could not read the selected file")

                    val fallbackTitle = fileName.substringBeforeLast(".")
                    // Pull real metadata out of the EPUB instead of showing the filename
                    // and "Unknown Author" for every book.
                    val metadata = if (format == BookFormat.EPUB) {
                        epubParser.readMetadata(destFile.absolutePath)
                    } else null

                    val book = Book(
                        title = metadata?.title?.takeIf { it.isNotBlank() && it != "Unknown Title" }
                            ?: fallbackTitle,
                        author = metadata?.author?.takeIf { it.isNotBlank() } ?: "Unknown Author",
                        filePath = destFile.absolutePath,
                        format = format,
                        // Copied out of the extraction cache, which Android may clear.
                        coverPath = metadata?.coverImagePath?.let { copyCover(it, destFile.name) },
                        fileSize = destFile.length()
                    )
                    val id = bookRepository.insertBook(book)
                    ImportResult.Imported(book.copy(id = id))
                }

                when (imported) {
                    is ImportResult.Unsupported -> _uiState.update { state ->
                        state.copy(error = "Unsupported file format", importProgress = null)
                    }

                    is ImportResult.Failed -> _uiState.update { state ->
                        state.copy(error = imported.message, importProgress = null)
                    }

                    is ImportResult.AlreadyImported -> _uiState.update { state ->
                        state.copy(
                            error = if (openWhenDone) null else "Book already in library",
                            importProgress = null,
                            bookToOpen = if (openWhenDone) imported.book else null
                        )
                    }

                    is ImportResult.Imported -> _uiState.update { state ->
                        state.copy(
                            importProgress = null,
                            bookToOpen = if (openWhenDone) imported.book else null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to import: ${e.message}", importProgress = null)
                }
            }
        }
    }

    /**
     * Two unrelated books can share a file name. Reuse the path only when it is the same
     * file already in the library; otherwise pick a free name instead of overwriting.
     */
    private suspend fun uniqueDestination(booksDir: File, fileName: String): File {
        val candidate = File(booksDir, fileName)
        if (!candidate.exists()) return candidate
        if (bookRepository.getBookByFilePath(candidate.absolutePath) != null) return candidate

        val base = fileName.substringBeforeLast(".", fileName)
        val ext = fileName.substringAfterLast(".", "")
        var index = 2
        while (index < 1000) {
            val next = File(booksDir, if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext")
            if (!next.exists()) return next
            index++
        }
        return candidate
    }

    private fun copyCover(sourcePath: String, bookFileName: String): String? = try {
        val source = File(sourcePath)
        if (!source.exists()) null else {
            val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
            val ext = source.extension.ifBlank { "jpg" }
            val dest = File(coversDir, "${bookFileName.substringBeforeLast(".")}_cover.$ext")
            source.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    } catch (_: Exception) {
        null
    }

    private sealed interface ImportResult {
        data object Unsupported : ImportResult
        data class Failed(val message: String) : ImportResult
        data class Imported(val book: Book) : ImportResult
        data class AlreadyImported(val book: Book) : ImportResult
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
