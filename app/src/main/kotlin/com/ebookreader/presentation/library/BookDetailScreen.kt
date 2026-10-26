package com.ebookreader.presentation.library

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.data.library.CoverMaker
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.seriesLabel
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BookCover
import com.ebookreader.presentation.common.formatTotalReadingTime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val coverMaker: CoverMaker,
    private val epubParser: EpubParser,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val bookId: Long = savedState.get<Long>("bookId") ?: -1L

    val book: StateFlow<Book?> = bookRepository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun save(title: String, author: String, series: String?, seriesIndex: Float?) {
        viewModelScope.launch {
            bookRepository.updateTitleAndAuthor(bookId, title, author)
            bookRepository.updateSeries(bookId, series, seriesIndex)
        }
    }

    fun toggleFavorite() {
        val b = book.value ?: return
        viewModelScope.launch { bookRepository.updateFavorite(b.id, !b.isFavorite) }
    }

    fun markFinished() {
        val b = book.value ?: return
        viewModelScope.launch { bookRepository.markBookFinished(b.id, b.totalPages.coerceAtLeast(1)) }
    }

    fun markUnread() {
        viewModelScope.launch { bookRepository.resetReadingProgress(bookId) }
    }

    /** An image the reader picked becomes the cover. */
    fun setCover(image: Uri) {
        val b = book.value ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(image)?.use { it.readBytes() }
                }.getOrNull()?.let { coverMaker.customCover(it, File(b.filePath).name) }
            }
            if (path == null) _message.value = "That image could not be used"
            else bookRepository.updateCover(b.id, path)
        }
    }

    /** Back to the cover the file carries, or the drawn one when it has none. */
    fun restoreCover() {
        val b = book.value ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val file = File(b.filePath)
                val epubCover = if (b.format == BookFormat.EPUB) epubParser.readMetadata(b.filePath)?.coverImage else null
                coverMaker.originalCover(file, epubCover)
            }
            bookRepository.updateCover(b.id, path)
        }
    }

    fun removeCover() {
        viewModelScope.launch { bookRepository.updateCover(bookId, null) }
    }

    fun resetReadingSettings() {
        viewModelScope.launch { bookRepository.updateReadingSettings(bookId, null) }
    }

    fun delete(onDone: () -> Unit) {
        val b = book.value ?: return
        viewModelScope.launch {
            bookRepository.deleteBook(b)
            onDone()
        }
    }
}

/**
 * One book in full: its cover (which can be replaced), what the publisher says about it,
 * where the reader is in it, and what the file is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onNavigateBack: () -> Unit,
    onRead: (Book) -> Unit,
    onShowStats: (Book) -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { viewModel.consumeMessage(); snackbar.showSnackbar(it) }
    }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setCover)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    book?.let { b ->
                        IconButton(
                            onClick = viewModel::toggleFavorite,
                            modifier = Modifier.semantics {
                                stateDescription = if (b.isFavorite) "In favourites" else "Not in favourites"
                            },
                        ) {
                            Icon(
                                if (b.isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                if (b.isFavorite) "Remove from favourites" else "Add to favourites",
                                tint = if (b.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit title, author and series") }
                    }
                },
            )
        },
    ) { padding ->
        val b = book ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box {
                    BookCover(
                        b,
                        Modifier
                            .width(120.dp)
                            .clickable(onClickLabel = "Change cover") { coverMenu = true },
                    )
                    DropdownMenu(expanded = coverMenu, onDismissRequest = { coverMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (b.coverPath == null) "Choose a cover" else "Replace cover") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = {
                                coverMenu = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        if (b.format != BookFormat.COMIC) {
                            DropdownMenuItem(
                                text = { Text("Use the book's own cover") },
                                leadingIcon = { Icon(Icons.Default.Restore, null) },
                                onClick = { coverMenu = false; viewModel.restoreCover() },
                            )
                        }
                        if (b.coverPath != null) {
                            DropdownMenuItem(
                                text = { Text("Use a plain cover") },
                                leadingIcon = { Icon(Icons.Default.HideImage, null) },
                                onClick = { coverMenu = false; viewModel.removeCover() },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(b.title, style = Bindery.type.title, modifier = Modifier.semantics { heading() })
                    Text(b.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    b.seriesLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onRead(b) }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (b.readingStatus) {
                                ReadingStatus.NOT_STARTED -> "Start reading"
                                ReadingStatus.READING -> "Continue"
                                ReadingStatus.FINISHED -> "Read again"
                            }
                        )
                    }
                    if (b.coverPath == null) {
                        TextButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choose a cover")
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            ProgressSummary(b)

            val description = b.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                Section("About this book")
                var expanded by remember { mutableStateOf(false) }
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .animateContentSize()
                        .clickable(onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded },
                )
            }

            Section("Reading")
            ActionRow(Icons.Default.QueryStats, "Reading stats", "Sittings, pace and finish date") { onShowStats(b) }
            if (b.readingStatus != ReadingStatus.FINISHED) {
                ActionRow(Icons.Default.CheckCircle, "Mark as finished") { viewModel.markFinished() }
            }
            if (b.readingStatus != ReadingStatus.NOT_STARTED) {
                ActionRow(Icons.Default.RestartAlt, "Mark as unread", "Reset reading progress") { viewModel.markUnread() }
            }
            if (b.readingSettings != null) {
                ActionRow(
                    Icons.Default.FormatSize,
                    "Uses its own type and page",
                    "Tap to follow your usual reading settings again",
                ) { viewModel.resetReadingSettings() }
            }

            Section("File")
            FileFacts(b)

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove from library")
            }
        }

        if (editing) {
            EditBookDialog(
                book = b,
                onSave = { title, author, series, index ->
                    viewModel.save(title, author, series, index)
                    editing = false
                },
                onDismiss = { editing = false },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Remove book") },
                text = { Text("It leaves the library with its bookmarks and highlights. Your reading history stays in the stats.") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; viewModel.delete(onNavigateBack) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ProgressSummary(book: Book) {
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val facts = buildList {
        add("${(book.readingProgress * 100).roundToInt()}%" to "read")
        formatTotalReadingTime(book.totalReadingSeconds).takeIf { it.isNotEmpty() }?.let { add(it to "reading") }
        if (book.readingStatus == ReadingStatus.FINISHED) book.finishedAt?.let { add(date.format(it) to "finished") }
        else book.lastRead?.let { add(date.format(it) to "last read") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        facts.forEach { (value, label) ->
            Column {
                Text(value, style = Bindery.type.numeral)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    book.positionLabel?.takeIf { book.readingStatus == ReadingStatus.READING }?.let {
        Spacer(Modifier.height(6.dp))
        Text("At: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileFacts(book: Book) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val file = remember(book.filePath) { File(book.filePath) }
    val rows = buildList {
        add("Format" to if (book.sourceFormat != null) "${book.format.label} (from ${book.sourceFormat})" else book.format.label)
        add("Size" to Formatter.formatShortFileSize(context, book.fileSize.takeIf { it > 0 } ?: file.length()))
        if (book.totalPages > 0) add((if (book.format == BookFormat.EPUB) "Chapters" else "Pages") to book.totalPages.toString())
        add("Added" to date.format(book.dateAdded))
        add("File" to file.name)
        book.contentHash?.let { add("SHA-256" to it.take(16) + "…") }
    }
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

private val BookFormat.label: String
    get() = when (this) {
        BookFormat.EPUB -> "EPUB"
        BookFormat.PDF -> "PDF"
        BookFormat.COMIC -> "Comic"
    }

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        title.uppercase(),
        style = Bindery.type.eyebrow,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
