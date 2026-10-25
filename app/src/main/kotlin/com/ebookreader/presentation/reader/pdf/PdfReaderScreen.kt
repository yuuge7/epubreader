package com.ebookreader.presentation.reader.pdf

import com.ebookreader.presentation.reader.rememberSittingSeconds
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.*
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.pageProgress
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.ReaderBar
import com.ebookreader.presentation.common.ReaderProgressFooter
import com.ebookreader.presentation.common.SessionClock
import com.ebookreader.presentation.common.BinderySheet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.rajat.pdfviewer.PdfRendererView
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: Long,
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ReaderTheme has already put the reading palette in scope.
    val pageColors = Bindery.colors

    var currentPage by remember { mutableIntStateOf(0) }
    var totalPages  by remember { mutableIntStateOf(0) }
    val pdfViewRef  = remember { mutableStateOf<PdfRendererView?>(null) }

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

    // Re-assert the restored page while the document renders. Page rows start out as
    // short placeholders, so the layout manager scrolls back to fill the screen and the
    // book opens a few pages early; repeating the jump as real heights arrive makes it
    // stick. Stops the moment the reader touches anything.
    LaunchedEffect(uiState.book?.id) {
        if (uiState.book == null) return@LaunchedEffect
        val target = viewModel.startPage()
        if (target <= 0) return@LaunchedEffect
        repeat(12) {
            delay(400L)
            if (!viewModel.shouldPinRestoredPage()) return@LaunchedEffect
            pdfViewRef.value?.jumpToPage(target)
        }
    }

    val sessionSeconds = rememberSittingSeconds(
        onResume = viewModel::onScreenResumed,
        onPause = viewModel::onScreenPaused,
        elapsed = viewModel::currentSessionSeconds,
    )

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
                    background = pageColors.paper,
                    pdfViewRef = pdfViewRef,
                    onTap = viewModel::toggleControls,
                    onPageChanged = { page, total ->
                        currentPage = page
                        totalPages  = total
                        viewModel.onPageChanged(page, total)
                    },
                    onUserTouch = viewModel::onUserInteraction,
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
                    ReaderBar(BarEdge.Top) {
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
                                        SessionClock(sessionSeconds)
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
                    ReaderBar(BarEdge.Bottom) {
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
                            ReaderProgressFooter(
                                progress = pageProgress(currentPage, totalPages),
                                totalReadingSeconds = book.totalReadingSeconds,
                            )
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
        BinderySheet(onDismissRequest = viewModel::hideBookmarksSheet) {
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
    background: Color,
    pdfViewRef: MutableState<PdfRendererView?>,
    onTap: () -> Unit,
    onPageChanged: (page: Int, total: Int) -> Unit,
    onUserTouch: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnUserTouch by rememberUpdatedState(onUserTouch)
    val latestOnError by rememberUpdatedState(onError)

    AndroidView(
        factory = { ctx ->
            PdfRendererView(ctx).also { view ->
                pdfViewRef.value = view

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
                    // until the renderer is ready; the ViewModel decides when the jump
                    // has landed by watching the reported pages.
                    if (startPage > 0) {
                        view.jumpToPage(startPage)
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
                                if (e.action == MotionEvent.ACTION_DOWN) latestOnUserTouch()
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
        // The gutter around and between pages takes the reading surface colour. Set in
        // update, not factory, so it follows a theme that changes while the book is open
        // (Auto switching with the system, for one).
        update = { view -> view.setBackgroundColor(background.toArgb()) },
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
                    BinderyCard(
                        onClick = { onJumpToPage(bm.page) },
                        selected = bm.page == currentPage
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
