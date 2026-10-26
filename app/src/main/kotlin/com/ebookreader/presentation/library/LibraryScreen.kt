package com.ebookreader.presentation.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.data.library.SourceKind
import com.ebookreader.domain.model.*
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.BookCover
import com.ebookreader.presentation.common.formatTotalReadingTime
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToCatalog: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenBookAt: (book: Book, chapterIndex: Int, query: String) -> Unit,
    onOpenBookStats: (Book) -> Unit,
    onOpenBookDetails: (Book) -> Unit,
    pendingImportUri: Uri? = null,
    onPendingImportHandled: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let { viewModel.importBook(it) } }
    val launchImport = { filePicker.launch(SourceKind.pickerMimeTypes) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { viewModel.importFolder(it) } }

    // A file handed to us by another app ("Open with"): import it, then open it.
    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let {
            viewModel.importAndOpen(it)
            onPendingImportHandled()
        }
    }

    LaunchedEffect(uiState.bookToOpen) {
        uiState.bookToOpen?.let { book ->
            onOpenBook(book)
            viewModel.consumeBookToOpen()
        }
    }

    // Errors and confirmations pass by at the foot of the screen instead of shoving the
    // shelves down; they used to sit in the layout as a card until dismissed.
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            viewModel.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    BackHandler(enabled = uiState.isSelecting) { viewModel.clearSelection() }
    BackHandler(enabled = uiState.isSearchActive && !uiState.isSelecting) { viewModel.toggleSearch() }

    var bookForOptions by remember { mutableStateOf<Book?>(null) }
    var bookToEdit by remember { mutableStateOf<Book?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCollection by remember { mutableStateOf(false) }
    var newCollection by remember { mutableStateOf(false) }
    var collectionToEdit by remember { mutableStateOf<BookCollection?>(null) }

    val onBookClick: (Book) -> Unit = { book ->
        if (uiState.isSelecting) viewModel.toggleSelection(book) else onOpenBook(book)
    }
    val onBookLongClick: (Book) -> Unit = { book ->
        if (uiState.isSelecting) viewModel.toggleSelection(book) else viewModel.startSelection(book)
    }
    // The card stays put while selecting: taking it away would slide the grid under the
    // finger that just long-pressed it.
    val browsingAll = !uiState.isSearchActive && uiState.selectedCollection == null &&
        uiState.selectedFilter == FilterOption.ALL

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                uiState.isSelecting -> SelectionBar(
                    count = uiState.selection.size,
                    inCollection = uiState.selectedCollection != null,
                    allFavorite = uiState.books.filter { it.id in uiState.selection }.all { it.isFavorite },
                    onClose = viewModel::clearSelection,
                    onFavorite = viewModel::favoriteSelected,
                    onAddToCollection = { pickCollection = true },
                    onRemoveFromCollection = viewModel::removeSelectedFromCollection,
                    onMarkFinished = viewModel::markSelectedFinished,
                    onMarkUnread = viewModel::markSelectedUnread,
                    onSelectAll = viewModel::selectAll,
                    onDelete = { confirmDelete = true },
                    onMore = if (uiState.selection.size == 1) {
                        { bookForOptions = uiState.books.firstOrNull { it.id in uiState.selection } }
                    } else null,
                )
                else -> LibraryTopBar(
                    searching = uiState.isSearchActive,
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onSearch = viewModel::toggleSearch,
                    isGridView = settings.isGridView,
                    selectedSort = uiState.selectedSort,
                    sortDescending = uiState.sortDescending,
                    onSort = viewModel::setSort,
                    onReverseSort = viewModel::toggleSortDirection,
                    onToggleView = { onEditSettings { s -> s.copy(isGridView = !s.isGridView) } },
                    onImportFolder = { folderPicker.launch(null) },
                    onStats = onNavigateToStats,
                    onCatalog = onNavigateToCatalog,
                    onSettings = onNavigateToSettings,
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelecting) {
                ExtendedFloatingActionButton(
                    onClick = launchImport,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Import") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!uiState.isSearchActive) {
                ShelfRow(
                    selectedFilter = uiState.selectedFilter,
                    selectedCollection = uiState.selectedCollection,
                    collections = uiState.collections,
                    onFilterSelected = viewModel::setFilter,
                    onCollectionSelected = viewModel::openCollection,
                    onCollectionLongPress = { collectionToEdit = it },
                    onNewCollection = { newCollection = true },
                )
            }

            uiState.importProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                uiState.importCount?.let { count ->
                    Text(
                        count,
                        style = Bindery.type.numeralCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            val hero = uiState.hero.takeIf { browsingAll }
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.books.isEmpty() && uiState.textHits.isEmpty() && hero == null -> when {
                    uiState.isSearchActive -> EmptyMessage(
                        if (uiState.searchQuery.isBlank()) "Search titles, authors and the text of your books"
                        else "Nothing matches “${uiState.searchQuery}”"
                    )
                    uiState.selectedCollection != null -> EmptyMessage(
                        "This collection is empty. Long-press books in the library to add them."
                    )
                    uiState.selectedFilter != FilterOption.ALL -> EmptyMessage("No books here yet")
                    else -> EmptyLibraryPlaceholder(onImport = launchImport)
                }
                settings.isGridView && !uiState.isSearchActive -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (hero != null) {
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueCard(hero, onOpen = { if (!uiState.isSelecting) onOpenBook(hero.book) })
                        }
                    }
                    items(uiState.books, key = { it.id }) { book ->
                        BookGridItem(
                            book = book,
                            selected = book.id in uiState.selection,
                            onClick = { onBookClick(book) },
                            onLongClick = { onBookLongClick(book) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (hero != null) {
                        item(key = "hero") { ContinueCard(hero, onOpen = { if (!uiState.isSelecting) onOpenBook(hero.book) }) }
                    }
                    if (uiState.isSearchActive && uiState.books.isNotEmpty()) sectionHeader("Titles and authors")
                    items(uiState.books, key = { it.id }) { book ->
                        BookListItem(
                            book = book,
                            selected = book.id in uiState.selection,
                            onClick = { onBookClick(book) },
                            onLongClick = { onBookLongClick(book) },
                            modifier = Modifier.animateItem()
                        )
                    }
                    if (uiState.isSearchActive && uiState.textHits.isNotEmpty()) {
                        sectionHeader("In the text")
                        items(uiState.textHits, key = { "hit-${it.book.id}-${it.chapterIndex}" }) { hit ->
                            TextHitItem(hit) { onOpenBookAt(hit.book, hit.chapterIndex, uiState.searchQuery) }
                        }
                    }
                }
            }
        }
    }

    bookForOptions?.let { book ->
        BookOptionsSheet(
            book = book,
            onEdit = { bookToEdit = book },
            onToggleFavorite = { viewModel.toggleFavorite(book) },
            onAddToCollection = { pickCollection = true },
            onShowStats = { onOpenBookStats(book) },
            onShowDetails = { onOpenBookDetails(book); viewModel.clearSelection() },
            onMarkAsFinished = { viewModel.markAsFinished(book) },
            onMarkAsUnread = { viewModel.markAsUnread(book) },
            onDelete = { confirmDelete = true },
        ) { bookForOptions = null }
    }

    bookToEdit?.let { book ->
        EditBookDialog(
            book = book,
            onSave = { title, author, series, index ->
                viewModel.updateBookDetails(book, title, author, series, index)
                bookToEdit = null
                viewModel.clearSelection()
            },
            onDismiss = { bookToEdit = null }
        )
    }

    if (confirmDelete) {
        val count = uiState.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (count == 1) "Remove book" else "Remove $count books") },
            text = {
                Text(
                    "They leave the library with their bookmarks and highlights. " +
                        "Your reading history stays in the stats."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(); confirmDelete = false }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    if (pickCollection) {
        CollectionPickerDialog(
            collections = uiState.collections,
            onPick = { id -> viewModel.addSelectedToCollection(id, null); pickCollection = false },
            onCreate = { name -> viewModel.addSelectedToCollection(null, name); pickCollection = false },
            onDismiss = { pickCollection = false },
        )
    }

    if (newCollection) {
        NameDialog(
            title = "New collection",
            initial = "",
            confirm = "Create",
            onConfirm = { viewModel.createCollection(it); newCollection = false },
            onDismiss = { newCollection = false },
        )
    }

    collectionToEdit?.let { shelf ->
        NameDialog(
            title = "Collection",
            initial = shelf.name,
            confirm = "Rename",
            onConfirm = { viewModel.renameCollection(shelf.id, it); collectionToEdit = null },
            onDismiss = { collectionToEdit = null },
            onDelete = { viewModel.deleteCollection(shelf.id); collectionToEdit = null },
        )
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title.uppercase(),
            style = Bindery.type.eyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
    }
}

// ── Bars ─────────────────────────────────────────────────────────────────────

/**
 * The library's bar: the search field itself, always there to type into, and one menu for
 * everything else. Five icons in a row used to fight for attention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isGridView: Boolean,
    selectedSort: SortOption,
    sortDescending: Boolean,
    onSort: (SortOption) -> Unit,
    onReverseSort: () -> Unit,
    onToggleView: () -> Unit,
    onImportFolder: () -> Unit,
    onStats: () -> Unit,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            if (searching) {
                IconButton(onClick = onSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
            }
        },
        title = {
            val colors = Bindery.colors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.page)
                    .border(1.dp, colors.rule, RoundedCornerShape(24.dp))
                    .then(if (searching) Modifier else Modifier.clickable(onClickLabel = "Search", onClick = onSearch))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (searching) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .semantics { contentDescription = "Search titles, authors and words in books" },
                        )
                    }
                    if (query.isEmpty()) {
                        Text(
                            if (searching) "Titles, authors, words in books" else "Search your library",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (searching && query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, "Clear search") }
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Library menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Sort by ${selectedSort.displayName.lowercase()}") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                        onClick = { menu = false; sortMenu = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isGridView) "Show as list" else "Show as grid") },
                        leadingIcon = {
                            Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, null)
                        },
                        onClick = { menu = false; onToggleView() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Import a folder") },
                        leadingIcon = { Icon(Icons.Default.DriveFolderUpload, null) },
                        onClick = { menu = false; onImportFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text("Browse catalogues") },
                        leadingIcon = { Icon(Icons.Default.Public, null) },
                        onClick = { menu = false; onCatalog() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Reading stats") },
                        leadingIcon = { Icon(Icons.Default.BarChart, null) },
                        onClick = { menu = false; onStats() },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { menu = false; onSettings() },
                    )
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOption.entries.forEach { sort ->
                        val selected = selectedSort == sort
                        DropdownMenuItem(
                            text = { Text(sort.displayName) },
                            // Picking the order already in use turns it around.
                            onClick = { if (selected) onReverseSort() else onSort(sort); sortMenu = false },
                            leadingIcon = { if (selected) Icon(Icons.Default.Check, null) },
                            trailingIcon = {
                                if (selected) Text(sort.directionLabel(sortDescending), style = Bindery.type.numeralCaption)
                            },
                            modifier = Modifier.semantics { this.selected = selected },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Reverse order") },
                        leadingIcon = {
                            Icon(if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null)
                        },
                        onClick = { onReverseSort(); sortMenu = false },
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    inCollection: Boolean,
    allFavorite: Boolean,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onRemoveFromCollection: () -> Unit,
    onMarkFinished: () -> Unit,
    onMarkUnread: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onMore: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Clear selection") } },
        title = { Text("$count selected", style = Bindery.type.numeral.copy(fontSize = Bindery.type.label.fontSize)) },
        actions = {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (allFavorite) Icons.Outlined.BookmarkBorder else Icons.Default.Bookmark,
                    if (allFavorite) "Remove from favourites" else "Add to favourites"
                )
            }
            if (inCollection) {
                IconButton(onClick = onRemoveFromCollection) {
                    Icon(Icons.Default.RemoveCircleOutline, "Remove from this collection")
                }
            } else {
                IconButton(onClick = onAddToCollection) {
                    Icon(Icons.Default.LibraryAdd, "Add to a collection")
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove from library") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Mark as finished") },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                        onClick = { menu = false; onMarkFinished() },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark as unread") },
                        leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                        onClick = { menu = false; onMarkUnread() },
                    )
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                        onClick = { menu = false; onSelectAll() },
                    )
                    if (onMore != null) {
                        DropdownMenuItem(
                            text = { Text("Details and stats") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menu = false; onMore() },
                        )
                    }
                }
            }
        }
    )
}

/** Status shelves first, then the reader's own collections, then a way to make one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(
    selectedFilter: FilterOption,
    selectedCollection: Long?,
    collections: List<BookCollection>,
    onFilterSelected: (FilterOption) -> Unit,
    onCollectionSelected: (Long) -> Unit,
    onCollectionLongPress: (BookCollection) -> Unit,
    onNewCollection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShelfOrder.forEach { filter ->
            val selected = selectedCollection == null && selectedFilter == filter
            FilterChip(
                selected = selected,
                onClick = { onFilterSelected(filter) },
                label = { Text(filter.displayName) },
            )
        }
        if (collections.isNotEmpty()) {
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
        }
        collections.forEach { shelf ->
            val selected = selectedCollection == shelf.id
            // A chip with a long press: rename or delete the collection.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    // Drawn 32dp tall like the chips beside it, touched at 48dp.
                    .minimumInteractiveComponentSize()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onCollectionSelected(shelf.id) },
                        onLongClick = { onCollectionLongPress(shelf) },
                        onLongClickLabel = "Rename or delete",
                    )
                    .semantics { this.selected = selected }
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shelf.name, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        shelf.bookCount.toString(),
                        style = Bindery.type.numeralCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        AssistChip(
            onClick = onNewCollection,
            label = { Text("New collection") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
        )
    }
}

// ── Continue reading ────────────────────────────────────────────────────────

/**
 * The one-tap resume: the book in hand, where it was left, and how long is left of the
 * chapter and the book at the reader's own pace.
 */
@Composable
private fun ContinueCard(hero: Hero, onOpen: () -> Unit) {
    val book = hero.book
    BinderyCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, Modifier.width(78.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    when (hero.kind) {
                        Hero.Kind.CONTINUE -> "CONTINUE"
                        Hero.Kind.NEXT_IN_SERIES -> "NEXT IN ${book.series?.uppercase() ?: "SERIES"}"
                    },
                    style = Bindery.type.eyebrow,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    book.title,
                    style = Bindery.type.title.copy(fontSize = Bindery.type.bookTitle.fontSize * 1.25f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val where = if (hero.kind == Hero.Kind.CONTINUE) book.positionLabel else book.author
                if (!where.isNullOrBlank()) {
                    Text(
                        where,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                hero.minutesLeftInChapter?.takeIf { it > 0 }?.let { minutes ->
                    Text(
                        "${formatMinutes(minutes)} left in this chapter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        "${(book.readingProgress * 100).roundToInt()}%".takeIf { hero.kind == Hero.Kind.CONTINUE },
                        formatTotalReadingTime(book.totalReadingSeconds).takeIf { it.isNotEmpty() }?.let { "$it in" },
                        hero.minutesLeftInBook?.takeIf { it > 0 }?.let { "${formatMinutes(it)} left" },
                    ).joinToString("  ·  "),
                    style = Bindery.type.numeralCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "18 min", "4h 20m". */
internal fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

// ── Books ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    book: Book,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
    ) {
        Box {
            BookCover(book, Modifier.fillMaxWidth())
            FormatBadge(book.formatLabel, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (selected) SelectedMark(Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = Bindery.type.bookTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: Book,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BinderyCard(
        selected = selected,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                BookCover(book, Modifier.width(52.dp), foreEdgeThickness = 3.dp)
                if (selected) SelectedMark(Modifier.align(Alignment.BottomStart).padding(3.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormatBadge(book.formatLabel)
                    Text(
                        book.readingStatus.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val readTime = formatTotalReadingTime(book.totalReadingSeconds)
                    if (readTime.isNotEmpty()) {
                        Text(
                            "· $readTime read",
                            style = Bindery.type.numeralCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        book.author,
                        book.seriesLabel,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.readingProgress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(book.readingProgress * 100).roundToInt()}%",
                        style = Bindery.type.numeralCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A place inside a book where the search words occur, with the words in context. */
@Composable
private fun TextHitItem(hit: TextHit, onClick: () -> Unit) {
    BinderyCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCover(hit.book, Modifier.width(34.dp), showDecorations = false)
            Column(Modifier.weight(1f)) {
                Text(
                    "${hit.book.title} · chapter ${hit.chapterIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    snippetText(hit.snippet, MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The index's snippet with its match markers turned into emphasis. */
internal fun snippetText(snippet: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var inMatch = false
    for (c in snippet) {
        when (c) {
            TextSearchHit.SNIPPET_OPEN -> inMatch = true
            TextSearchHit.SNIPPET_CLOSE -> inMatch = false
            else -> if (inMatch) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(c)
            } else append(c)
        }
    }
}

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = modifier.size(24.dp)) {
        Icon(
            Icons.Default.Check,
            contentDescription = "Selected",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(4.dp)
        )
    }
}

/** The file type, as a neutral ink label. */
@Composable
private fun FormatBadge(label: String, modifier: Modifier = Modifier) {
    val colors = Bindery.colors
    Surface(
        modifier = modifier,
        color = colors.ink.copy(alpha = 0.82f),
        contentColor = colors.paper,
        shape = RoundedCornerShape(3.dp)
    ) {
        Text(
            label,
            style = Bindery.type.eyebrow,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun CollectionPickerDialog(
    collections: List<BookCollection>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                collections.forEach { shelf ->
                    Surface(onClick = { onPick(shelf.id) }, shape = RoundedCornerShape(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null)
                            Spacer(Modifier.width(12.dp))
                            Text(shelf.name, modifier = Modifier.weight(1f))
                            Text(shelf.bookCount.toString(), style = Bindery.type.numeralCaption)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (collections.isEmpty()) "Name your first collection" else "Or a new one") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyLibraryPlaceholder(onImport: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                "Your library is empty",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Import EPUB, PDF, comics, Kindle, FB2 or text files, or browse a catalogue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Button(onClick = onImport) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Import a book")
            }
        }
    }
}

/** Reading state first, then file types. */
private val ShelfOrder = listOf(
    FilterOption.ALL, FilterOption.READING, FilterOption.FINISHED, FilterOption.FAVORITES,
    FilterOption.EPUB, FilterOption.PDF, FilterOption.COMIC,
)

internal val SortOption.displayName: String
    get() = when (this) {
        SortOption.TITLE -> "Title"
        SortOption.AUTHOR -> "Author"
        SortOption.DATE_ADDED -> "Date added"
        SortOption.LAST_READ -> "Last read"
        SortOption.PROGRESS -> "Progress"
        SortOption.READING_TIME -> "Reading time"
        SortOption.FILE_SIZE -> "File size"
    }

/** Which way round the order runs, in its own terms: "A–Z", "Newest", "Largest". */
private fun SortOption.directionLabel(descending: Boolean): String = when (this) {
    SortOption.TITLE, SortOption.AUTHOR -> if (descending) "Z–A" else "A–Z"
    SortOption.DATE_ADDED, SortOption.LAST_READ -> if (descending) "Newest" else "Oldest"
    SortOption.PROGRESS -> if (descending) "Most read" else "Least read"
    SortOption.READING_TIME -> if (descending) "Longest" else "Shortest"
    SortOption.FILE_SIZE -> if (descending) "Largest" else "Smallest"
}

private val FilterOption.displayName: String
    get() = when (this) {
        FilterOption.ALL -> "All"
        FilterOption.READING -> "Reading"
        FilterOption.FINISHED -> "Finished"
        FilterOption.FAVORITES -> "Favourites"
        FilterOption.EPUB -> "EPUB"
        FilterOption.PDF -> "PDF"
        FilterOption.COMIC -> "Comics"
    }

private val ReadingStatus.displayName: String
    get() = when (this) {
        ReadingStatus.NOT_STARTED -> "Not started"
        ReadingStatus.READING -> "Reading"
        ReadingStatus.FINISHED -> "Finished"
    }
