package com.ebookreader.presentation.catalog

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.library.BookImporter
import com.ebookreader.data.opds.CatalogException
import com.ebookreader.data.opds.OpdsClient
import com.ebookreader.data.opds.OpdsEntry
import com.ebookreader.data.opds.OpdsLink
import com.ebookreader.data.opds.OpdsParser
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.OpdsCatalog
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** One page of a catalogue, as the reader walked to it. */
data class CatalogPage(
    /** Which visit this is: the same address opened twice is two pages. */
    val id: Long,
    val url: String,
    val title: String,
    val entries: List<OpdsEntry> = emptyList(),
    val next: String? = null,
    val searchTemplate: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

sealed interface DownloadState {
    data class Running(val progress: Float?) : DownloadState
    data class Done(val book: Book) : DownloadState
}

data class CatalogUiState(
    /** Pages opened so far, the current one last. Empty shows the list of catalogues. */
    val pages: List<CatalogPage> = emptyList(),
    /** By the address of the file being downloaded. */
    val downloads: Map<String, DownloadState> = emptyMap(),
    val message: String? = null,
    val isAddingCatalog: Boolean = false,
) {
    val current: CatalogPage? get() = pages.lastOrNull()
}

/**
 * Browsing an online catalogue: a stack of pages from the catalogue's start page down to
 * a book, searching where the catalogue allows it, and downloads straight into the library.
 */
@HiltViewModel
class CatalogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OpdsClient,
    private val importer: BookImporter,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    val catalogs: StateFlow<List<OpdsCatalog>> = settingsRepository.getSettings()
        .map { BuiltInCatalogs + it.catalogs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuiltInCatalogs)

    /** Loading the open page, or more of it; only ever one at a time. */
    private var loadJob: Job? = null
    private var nextPageId = 0L

    fun openCatalog(catalog: OpdsCatalog) = push(catalog.url, catalog.title)

    fun openEntry(entry: OpdsEntry) {
        entry.navigation?.let { push(it.href, entry.title) }
    }

    fun openLink(link: OpdsLink) = push(link.href, link.title?.trimEnd('…', '.') ?: "Catalogue")

    fun search(query: String) {
        val template = _uiState.value.current?.searchTemplate ?: return
        if (query.isBlank()) return
        push(OpdsParser.searchUrl(template, query), "“${query.trim()}”")
    }

    /** One page back; false when already at the list of catalogues. */
    fun back(): Boolean {
        if (_uiState.value.pages.isEmpty()) return false
        loadJob?.cancel()
        _uiState.update { it.copy(pages = it.pages.dropLast(1)) }
        return true
    }

    fun retry() {
        val page = _uiState.value.current ?: return
        _uiState.update { it.copy(pages = it.pages.dropLast(1)) }
        push(page.url, page.title)
    }

    private fun push(url: String, title: String) {
        val inherited = _uiState.value.current?.searchTemplate
        val id = nextPageId++
        _uiState.update { it.copy(pages = it.pages + CatalogPage(id, url, title, searchTemplate = inherited)) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = runCatching { client.feed(url) }
            val feed = result.getOrElse { e ->
                if (e is CancellationException) throw e
                updatePage(id) { it.copy(isLoading = false, error = e.message ?: "This page could not be loaded") }
                return@launch
            }
            updatePage(id) {
                it.copy(
                    title = feed.title.ifBlank { it.title },
                    entries = feed.entries,
                    next = feed.next,
                    isLoading = false,
                )
            }
            // The search address can take one more request to find; the page shows first.
            client.searchTemplate(feed)?.let { template -> updatePage(id) { it.copy(searchTemplate = template) } }
        }
    }

    fun loadMore() {
        val page = _uiState.value.current ?: return
        val next = page.next ?: return
        if (page.isLoadingMore || page.isLoading) return
        updatePage(page.id) { it.copy(isLoadingMore = true) }
        loadJob = viewModelScope.launch {
            val feed = runCatching { client.feed(next) }.getOrElse { e ->
                if (e is CancellationException) throw e
                updatePage(page.id) { it.copy(isLoadingMore = false) }
                _uiState.update { it.copy(message = e.message) }
                return@launch
            }
            updatePage(page.id) { p ->
                val seen = p.entries.mapTo(HashSet()) { it.id }
                p.copy(entries = p.entries + feed.entries.filter { it.id !in seen }, next = feed.next, isLoadingMore = false)
            }
        }
    }

    /** Changes page [id] if it is still open; a page the reader has left stays gone. */
    private fun updatePage(id: Long, transform: (CatalogPage) -> CatalogPage) = _uiState.update { state ->
        state.copy(pages = state.pages.map { p -> if (p.id == id) transform(p) else p })
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    /**
     * Downloads [link] and adds it to the library. Runs on the app's scope, so a download
     * the reader leaves the catalogue during still lands.
     */
    fun download(entry: OpdsEntry, link: OpdsLink) {
        val key = link.href
        if (_uiState.value.downloads[key] is DownloadState.Running) return
        setDownload(key, DownloadState.Running(null))
        appScope.launch {
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val downloaded = runCatching {
                client.download(link, entry.title, dir) { p -> setDownload(key, DownloadState.Running(p)) }
            }.getOrElse { e ->
                setDownload(key, null)
                _uiState.update { it.copy(message = e.message ?: "The download failed") }
                return@launch
            }
            val result = try {
                importer.import(downloaded.file, downloaded.fileName, downloaded.kind)
            } finally {
                downloaded.file.delete()
            }
            setDownload(key, result.book?.let(DownloadState::Done))
            _uiState.update { it.copy(message = result.message) }
        }
    }

    private fun setDownload(key: String, state: DownloadState?) = _uiState.update {
        it.copy(downloads = if (state == null) it.downloads - key else it.downloads + (key to state))
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // ── Catalogues the reader adds ───────────────────────────────────────────

    /** Checks that [url] is a catalogue, then keeps it under its own title. */
    fun addCatalog(url: String) {
        val address = url.trim().let { if ("://" in it) it else "https://$it" }
        _uiState.update { it.copy(isAddingCatalog = true) }
        viewModelScope.launch {
            val feed = runCatching { client.feed(address) }.getOrElse { e ->
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isAddingCatalog = false, message = e.message) }
                return@launch
            }
            val title = feed.title.ifBlank { address.substringAfter("://").substringBefore('/') }
            settingsRepository.editSettings { s ->
                if (s.catalogs.any { it.url == address }) s else s.copy(catalogs = s.catalogs + OpdsCatalog(title, address))
            }
            _uiState.update { it.copy(isAddingCatalog = false, message = "Added “$title”") }
        }
    }

    fun removeCatalog(catalog: OpdsCatalog) {
        viewModelScope.launch {
            settingsRepository.editSettings { s -> s.copy(catalogs = s.catalogs - catalog) }
        }
    }

    fun isBuiltIn(catalog: OpdsCatalog) = catalog in BuiltInCatalogs

    companion object {
        val BuiltInCatalogs = listOf(
            OpdsCatalog("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/"),
        )
    }
}
