package com.ebookreader.presentation.reader.pdf

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import com.rajat.pdfviewer.PdfRendererView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: Long,
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = settings.theme == AppTheme.DARK

    var currentPage by remember { mutableIntStateOf(0) }
    var totalPages  by remember { mutableIntStateOf(0) }
    val pdfViewRef  = remember { mutableStateOf<PdfRendererView?>(null) }

    // Session timer — counts up every second
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); sessionSeconds++ }
    }

    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    LaunchedEffect(uiState.book) {
        uiState.book?.let { book ->
            if (currentPage == 0) currentPage = book.currentPage
            if (totalPages  == 0) totalPages  = book.totalPages.coerceAtLeast(1)
        }
    }

    // External jump requests (markAsFinished, bookmark tap)
    LaunchedEffect(uiState.jumpToPage) {
        uiState.jumpToPage?.let { page ->
            pdfViewRef.value?.scrollToPage(page, animate = true)
            viewModel.clearJumpRequest()
        }
    }

    // Save session time on exit
    DisposableEffect(Unit) { onDispose { viewModel.saveSessionTime() } }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Opening PDF…")
                    }
                }
            }

            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Text(uiState.error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onNavigateBack) { Text("Go Back") }
                    }
                }
            }

            uiState.book != null -> {
                val book = uiState.book!!

                PdfViewerWidget(
                    file = File(book.filePath),
                    startPage = book.currentPage,
                    isDark = isDark,
                    pdfViewRef = pdfViewRef,
                    onTap = viewModel::toggleControls,
                    onPageChanged = { page, total ->
                        currentPage = page
                        totalPages  = total
                        viewModel.onPageChanged(page, total)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── Top bar ───────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn() + slideInVertically(),
                    exit  = fadeOut() + slideOutVertically(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    book.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Page ${currentPage + 1} of $totalPages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (sessionSeconds > 0) {
                                        Text(
                                            "⏱ ${formatSessionTime(sessionSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                if (uiState.isCurrentPageBookmarked)
                                    viewModel.removeBookmarkFromPage(currentPage)
                                else viewModel.showBookmarkDialog()
                            }) {
                                Icon(
                                    if (uiState.isCurrentPageBookmarked) Icons.Default.Bookmark
                                    else Icons.Default.BookmarkBorder, "Bookmark",
                                    tint = if (uiState.isCurrentPageBookmarked)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = viewModel::showBookmarksSheet) {
                                Icon(Icons.Default.Bookmarks, "Bookmarks")
                            }
                            val isFinished = book.readingStatus == ReadingStatus.FINISHED
                            IconButton(onClick = { viewModel.markAsFinished() }) {
                                Icon(
                                    Icons.Default.CheckCircle, "Mark as Finished",
                                    tint = if (isFinished) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // ── Bottom bar ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn() + slideInVertically { it },
                    exit  = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (totalPages > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "${currentPage + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 28.dp)
                                    )
                                    Slider(
                                        value = currentPage.toFloat(),
                                        onValueChange = { v ->
                                            pdfViewRef.value?.scrollToPage(
                                                v.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                            )
                                        },
                                        valueRange = 0f..(totalPages - 1).toFloat().coerceAtLeast(0f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "$totalPages",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 28.dp)
                                    )
                                }
                            }
                            val fraction = if (totalPages > 0)
                                (currentPage + 1).toFloat() / totalPages else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${(fraction * 100).roundToInt()}% read",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val totalFormatted = formatTotalReadingTime(book.totalReadingSeconds)
                                if (totalFormatted.isNotEmpty()) {
                                    Text(
                                        "Total: $totalFormatted",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBookmarkDialog,
            title = { Text("Add Bookmark") },
            text = {
                Column {
                    Text("Page ${currentPage + 1}")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.bookmarkNote,
                        onValueChange = viewModel::setBookmarkNote,
                        label = { Text("Note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.addBookmark(currentPage) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBookmarkDialog) { Text("Cancel") }
            }
        )
    }

    if (uiState.showBookmarksSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::hideBookmarksSheet,
            sheetState = rememberModalBottomSheetState()
        ) {
            BookmarksPanel(
                bookmarks = uiState.bookmarks,
                currentPage = currentPage,
                onJumpToPage = { page ->
                    pdfViewRef.value?.scrollToPage(page, animate = true)
                    viewModel.hideBookmarksSheet()
                },
                onDeleteBookmark = viewModel::deleteBookmark
            )
        }
    }
}

// ── Page navigation helper ────────────────────────────────────────────────────
// PdfRendererView extends ConstraintLayout and contains a private RecyclerView.
// We walk the child view hierarchy to find it and navigate.

private fun PdfRendererView.scrollToPage(page: Int, animate: Boolean = false) {
    fun findRecyclerView(v: android.view.View): RecyclerView? {
        if (v is RecyclerView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findRecyclerView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
    findRecyclerView(this)?.let { rv ->
        if (animate) rv.smoothScrollToPosition(page)
        else rv.scrollToPosition(page)
    }
}

// ── PdfRendererView widget ─────────────────────────────────────────────────────

@Composable
private fun PdfViewerWidget(
    file: File,
    startPage: Int,
    isDark: Boolean,
    pdfViewRef: MutableState<PdfRendererView?>,
    onTap: () -> Unit,
    onPageChanged: (page: Int, total: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)

    AndroidView(
        factory = { ctx ->
            PdfRendererView(ctx).also { view ->
                pdfViewRef.value = view

                // Dark mode: tint the area around pages
                if (isDark) view.setBackgroundColor(0xFF1C1B1F.toInt())

                view.statusListener = object : PdfRendererView.StatusCallBack {
                    override fun onPdfLoadStart() {}
                    override fun onPdfLoadProgress(progress: Int, downloadedBytes: Long, totalBytes: Long?) {}
                    override fun onPdfLoadSuccess(absolutePath: String) {
                        if (startPage > 0) view.scrollToPage(startPage)
                    }
                    override fun onPageChanged(currentPage: Int, totalPage: Int) {
                        latestOnPageChanged(currentPage, totalPage)
                    }
                }

                view.setOnClickListener { latestOnTap() }
                view.initWithFile(file)
            }
        },
        modifier = modifier
    )
}

// ── Bookmarks panel ────────────────────────────────────────────────────────────

@Composable
private fun BookmarksPanel(
    bookmarks: List<Bookmark>,
    currentPage: Int,
    onJumpToPage: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Bookmarks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp))
        if (bookmarks.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No bookmarks yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(bookmarks, key = { it.id }) { bm ->
                    Card(
                        onClick = { onJumpToPage(bm.page) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (bm.page == currentPage)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = if (bm.page == currentPage)
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null,
                                tint = if (bm.page == currentPage)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Page ${bm.page + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                if (bm.note.isNotBlank())
                                    Text(bm.note, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDeleteBookmark(bm) }) {
                                Icon(Icons.Default.Delete, "Delete",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
