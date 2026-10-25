package com.ebookreader.presentation.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.data.library.SourceKind
import com.ebookreader.data.library.label
import com.ebookreader.data.opds.OpdsEntry
import com.ebookreader.data.opds.OpdsLink
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.OpdsCatalog
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.CoverArt

/**
 * Online catalogues: Project Gutenberg built in, and any OPDS address the reader adds.
 * Pages are walked like folders; a book downloads straight into the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onNavigateBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val page = state.current
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var addingCatalog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            viewModel.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    fun goBack() {
        when {
            searching -> searching = false
            !viewModel.back() -> onNavigateBack()
        }
    }
    BackHandler(enabled = searching || state.pages.isNotEmpty()) { goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Title, author or subject") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                viewModel.search(query)
                                searching = false
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(page?.title ?: "Catalogues", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (!searching && page?.searchTemplate != null) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search this catalogue") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                page == null -> CatalogList(
                    catalogs = catalogs,
                    isBuiltIn = viewModel::isBuiltIn,
                    onOpen = viewModel::openCatalog,
                    onRemove = viewModel::removeCatalog,
                    onAdd = { addingCatalog = true },
                    isAdding = state.isAddingCatalog,
                )
                page.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                page.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(page.error, style = Bindery.type.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = viewModel::retry) { Text("Try again") }
                }
                page.entries.isEmpty() -> Text(
                    "Nothing here",
                    Modifier.align(Alignment.Center),
                    style = Bindery.type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(page.entries, key = { it.id }) { entry ->
                        if (entry.isBook) {
                            BookEntry(
                                entry = entry,
                                downloads = state.downloads,
                                onDownload = { link -> viewModel.download(entry, link) },
                                onOpenBook = onOpenBook,
                                onOpenLink = viewModel::openLink,
                                onOpenEntry = { viewModel.openEntry(entry) },
                            )
                        } else {
                            NavigationEntry(entry, onClick = { viewModel.openEntry(entry) })
                        }
                    }
                    if (page.next != null) {
                        item(key = "more") {
                            // Reaching the foot of the list asks for the next page.
                            LaunchedEffect(page.entries.size) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingCatalog) {
        AddCatalogDialog(
            onDismiss = { addingCatalog = false },
            onAdd = { url ->
                viewModel.addCatalog(url)
                addingCatalog = false
            },
        )
    }
}

@Composable
private fun CatalogList(
    catalogs: List<OpdsCatalog>,
    isBuiltIn: (OpdsCatalog) -> Boolean,
    onOpen: (OpdsCatalog) -> Unit,
    onRemove: (OpdsCatalog) -> Unit,
    onAdd: () -> Unit,
    isAdding: Boolean,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Free books to download straight into your library. The app goes online only " +
                    "while you browse here.",
                style = Bindery.type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(catalogs, key = { it.url }) { catalog ->
            BinderyCard(Modifier.fillMaxWidth(), onClick = { onOpen(catalog) }) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(catalog.title, style = Bindery.type.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            catalog.url.substringAfter("://").substringBefore('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isBuiltIn(catalog)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        IconButton(onClick = { onRemove(catalog) }) { Icon(Icons.Default.Close, "Remove ${catalog.title}") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, enabled = !isAdding) {
                if (isAdding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isAdding) "Checking the address…" else "Add a catalogue")
            }
        }
    }
}

@Composable
private fun NavigationEntry(entry: OpdsEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = Bindery.type.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (entry.author ?: entry.summary)?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(top = 12.dp))
}

@Composable
private fun BookEntry(
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsLink) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenLink: (OpdsLink) -> Unit,
    onOpenEntry: () -> Unit,
) {
    val formats = remember(entry) { readableFormats(entry.acquisitions) }
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    BinderyCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CoverArt(entry.title, entry.author.orEmpty(), entry.cover, Modifier.width(72.dp), typesetWhileLoading = true)
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title, style = Bindery.type.bookTitle, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = if (entry.navigation != null) Modifier.clickable(onClick = onOpenEntry) else Modifier,
                    )
                    entry.author?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            entry.summary?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .animateContentSize()
                        .clickable(onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded },
                )
            }
            if (formats.isNotEmpty()) DownloadRow(formats, downloads, onDownload, onOpenBook)
            if (entry.related.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entry.related.take(6), key = { it.href }) { link ->
                        AssistChip(
                            onClick = { onOpenLink(link) },
                            label = { Text(link.title?.trimEnd('…') ?: "Related", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

/** The best format as the main button; the others, by their catalogue names, in a menu. */
@Composable
private fun DownloadRow(
    formats: List<Pair<OpdsLink, SourceKind>>,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsLink) -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val done = formats.firstNotNullOfOrNull { (link, _) -> downloads[link.href] as? DownloadState.Done }
    val running = formats.firstNotNullOfOrNull { (link, _) -> downloads[link.href] as? DownloadState.Running }
    when {
        done != null -> Button(onClick = { onOpenBook(done.book) }) { Text("Read now") }
        running != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Downloading…", style = MaterialTheme.typography.labelLarge)
            val progress = running.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            val (best, kind) = formats.first()
            Button(onClick = { onDownload(best) }) { Text("Download ${kind.label}") }
            if (formats.size > 1) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Other formats") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        formats.forEach { (link, k) ->
                            DropdownMenuItem(
                                text = { Text(link.title ?: k.label) },
                                onClick = {
                                    menu = false
                                    onDownload(link)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Downloads this app can open, the most faithful format first; within a format, in the
 * catalogue's own order, which is how catalogues rank their editions.
 */
private fun readableFormats(links: List<OpdsLink>): List<Pair<OpdsLink, SourceKind>> {
    val preference = listOf(
        SourceKind.EPUB, SourceKind.CBZ, SourceKind.PDF, SourceKind.AZW3, SourceKind.MOBI,
        SourceKind.FB2, SourceKind.FB2_ZIP, SourceKind.CBR, SourceKind.MARKDOWN, SourceKind.TXT,
    )
    return links.mapNotNull { link -> link.kind?.let { link to it } }
        .sortedBy { preference.indexOf(it.second) }
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a catalogue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The address of an OPDS catalogue, as a library or bookshop publishes it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Address") },
                    placeholder = { Text("https://example.org/opds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onAdd(url) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
