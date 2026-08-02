package com.ebookreader.presentation.reader.pdf

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.Bookmark
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
    // AppTheme.SYSTEM must follow the device setting, not fall back to light.
    val isDark = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        else -> false
    }

    var currentPage by remember { mutableIntStateOf(0) }
    var totalPages  by remember { mutableIntStateOf(0) }
    val pdfViewRef  = remember { mutableStateOf<PdfRendererView?>(null) }

    // Session timer — the ViewModel owns the clock so it pauses with the screen.
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); sessionSeconds = viewModel.currentSessionSeconds() }
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
            pdfViewRef.value?.jumpToPage(page)
            viewModel.clearJumpRequest()
        }
    }

    // Only count time while the screen is in front of the user; flush position and
    // elapsed time on every pause so a swipe-away kill cannot lose them.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenPaused()
        }
    }

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
                    startPage = viewModel.startPage(),
                    isDark = isDark,
                    pdfViewRef = pdfViewRef,
                    onTap = viewModel::toggleControls,
                    onPageChanged = { page, total ->
                        currentPage = page
                        totalPages  = total
                        viewModel.onPageChanged(page, total)
                    },
                    onRestored = viewModel::onRestoreComplete,
                    onError = viewModel::onRenderError,
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
                            val isFinished = uiState.isFinished
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
                                // Track the drag locally and jump once on release —
                                // re-rendering a PDF page per pixel of slider travel
                                // makes the scrubber unusable on long documents.
                                var scrubTarget by remember { mutableStateOf<Float?>(null) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "${(scrubTarget?.toInt() ?: currentPage) + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 28.dp)
                                    )
                                    Slider(
                                        value = scrubTarget ?: currentPage.toFloat(),
                                        onValueChange = { v -> scrubTarget = v },
                                        onValueChangeFinished = {
                                            scrubTarget?.let { v ->
                                                pdfViewRef.value?.jumpToPage(
                                                    v.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                                )
                                            }
                                            scrubTarget = null
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
                    pdfViewRef.value?.jumpToPage(page)
                    viewModel.hideBookmarksSheet()
                },
                onDeleteBookmark = viewModel::deleteBookmark
            )
        }
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
    onRestored: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnRestored by rememberUpdatedState(onRestored)
    val latestOnError by rememberUpdatedState(onError)

    AndroidView(
        factory = { ctx ->
            PdfRendererView(ctx).also { view ->
                pdfViewRef.value = view

                // Dark mode: tint the area around pages
                if (isDark) view.setBackgroundColor(0xFF1C1B1F.toInt())

                view.statusListener = object : PdfRendererView.StatusCallBack {
                    override fun onPageChanged(currentPage: Int, totalPage: Int) {
                        latestOnPageChanged(currentPage, totalPage)
                    }

                    override fun onError(error: Throwable) {
                        latestOnError(error.message ?: "This PDF could not be opened")
                    }
                }

                // A deleted or corrupt file throws out of init; without this the whole
                // screen crashes instead of showing the error state.
                runCatching {
                    view.initWithFile(file)

                    // Resume where the reader left off. This used to sit in
                    // StatusCallBack.onPdfLoadSuccess, which the library only fires on
                    // the *download* path — with initWithFile it never ran, so every
                    // PDF reopened at page 1. jumpToPage queues itself internally
                    // until the renderer is ready.
                    if (startPage > 0) {
                        view.jumpToPage(startPage)
                        view.recyclerView.post { latestOnRestored() }
                    } else {
                        latestOnRestored()
                    }

                    // The pinch-zoom RecyclerView consumes touches, so a click listener
                    // on the parent never fires. Watch taps on the list itself instead,
                    // without intercepting (scroll and double-tap zoom keep working).
                    val tapDetector = GestureDetector(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                latestOnTap()
                                return false
                            }
                        }
                    )
                    view.recyclerView.addOnItemTouchListener(
                        object : RecyclerView.SimpleOnItemTouchListener() {
                            override fun onInterceptTouchEvent(
                                rv: RecyclerView,
                                e: MotionEvent
                            ): Boolean {
                                tapDetector.onTouchEvent(e)
                                return false
                            }
                        }
                    )
                }.onFailure {
                    latestOnError(it.message ?: "This PDF could not be opened")
                }
            }
        },
        // Releases the PdfRenderer and its file descriptor; without this every book
        // opened leaked one until the process died.
        onRelease = { view -> runCatching { view.closePdfRender() } },
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
