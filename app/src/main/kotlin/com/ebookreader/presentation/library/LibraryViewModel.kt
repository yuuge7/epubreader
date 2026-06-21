package com.ebookreader.presentation.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    val isGridView: Boolean = true,
    val importProgress: Float? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val filterFlow = MutableStateFlow(FilterOption.ALL)
    private val sortFlow = MutableStateFlow(SortOption.DATE_ADDED)
    private val searchFlow = MutableStateFlow("")

    init {
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
    }

    fun setSort(sort: SortOption) {
        sortFlow.value = sort
        _uiState.update { it.copy(selectedSort = sort) }
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

    fun toggleViewMode() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
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
            bookRepository.updateBook(book.copy(title = newTitle, author = newAuthor))
        }
    }

    fun markAsFinished(book: Book) {
        viewModelScope.launch {
            val total = book.totalPages.coerceAtLeast(1)
            bookRepository.updateReadingProgress(book.id, total - 1, total)
        }
    }

    fun markAsUnread(book: Book) {
        viewModelScope.launch {
            bookRepository.updateReadingProgress(book.id, 0, book.totalPages)
            // Also reset to NOT_STARTED explicitly
            bookRepository.updateBook(
                book.copy(
                    currentPage = 0,
                    readingStatus = com.ebookreader.domain.model.ReadingStatus.NOT_STARTED,
                    lastRead = null
                )
            )
        }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(importProgress = 0f) }

                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: ""

                val format = when {
                    mimeType.contains("pdf") || uri.path?.endsWith(".pdf", true) == true -> BookFormat.PDF
                    mimeType.contains("epub") || uri.path?.endsWith(".epub", true) == true -> BookFormat.EPUB
                    else -> {
                        _uiState.update { it.copy(error = "Unsupported file format", importProgress = null) }
                        return@launch
                    }
                }

                // Get filename
                var fileName = "Unknown"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                // Check if already imported
                val booksDir = File(context.filesDir, "books").also { it.mkdirs() }
                val destFile = File(booksDir, fileName)

                if (bookRepository.getBookByFilePath(destFile.absolutePath) != null) {
                    _uiState.update { it.copy(error = "Book already in library", importProgress = null) }
                    return@launch
                }

                // Copy file to app storage
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        val totalBytes = input.available().toLong().takeIf { it > 0 } ?: 1L
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            _uiState.update {
                                it.copy(importProgress = (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                // Extract metadata
                val titleWithoutExt = fileName.substringBeforeLast(".")
                val book = Book(
                    title = titleWithoutExt,
                    author = "Unknown Author",
                    filePath = destFile.absolutePath,
                    format = format,
                    fileSize = destFile.length()
                )

                bookRepository.insertBook(book)
                _uiState.update { it.copy(importProgress = null) }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to import: ${e.message}", importProgress = null)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
