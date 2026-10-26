package com.ebookreader.presentation.reader.pdf

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.doOnLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.pageProgress
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.BinderySheet
import com.ebookreader.presentation.common.ReaderBar
import com.ebookreader.presentation.common.ReaderProgressFooter
import com.ebookreader.presentation.common.SessionClock
import com.ebookreader.presentation.reader.ReaderWindow
import com.ebookreader.presentation.reader.rememberSittingSeconds
import com.rajat.pdfviewer.PdfRendererView
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: Long,
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ReaderTheme has already put the reading palette in scope.
    val pageColors = Bindery.colors

    var currentPage by remember { mutableIntStateOf(0) }
    var totalPages  by remember { mutableIntStateOf(0) }
    val pdfViewRef  = remember { mutableStateOf<PdfRendererView?>(null) }

    ReaderWindow(keepScreenOn = settings.keepScreenOn, showSystemBars = uiState.showControls)

    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    LaunchedEffect(uiState.book) {
        uiState.book?.let { book ->
            if (currentPage == 0) currentPage = book.currentPage
            if (totalPages  == 0) totalPages  = book.totalPages.coerceAtLeast(1)
        }
    }

    // External jump requests (markAsFinished, bookmark tap, thumbnails)
    LaunchedEffect(uiState.jumpToPage) {
        uiState.jumpToPage?.let { page ->
            pdfViewRef.value?.jumpToPage(page)
            viewModel.clearJumpRequest()
        }
    }

    // Re-assert the restored place while the document renders. Page rows start out as
    // short placeholders, so the layout manager scrolls back to fill the screen and the
    // book opens a few pages early; repeating the jump as real heights arrive makes it
    // stick. Stops the moment the reader touches anything.
    LaunchedEffect(uiState.book?.id) {
        if (uiState.book == null) return@LaunchedEffect
        val target = viewModel.startPage()
        val fraction = viewModel.startFraction()
        if (target <= 0 && fraction <= 0f) return@LaunchedEffect
        repeat(12) {
            delay(400L)
            if (!viewModel.shouldPinRestoredPage(fraction)) return@LaunchedEffect
            pdfViewRef.value?.let { view -> restorePlace(view, target, fraction) }
        }
    }

    // Cropping needs to know where the text is; measured once, when first wanted.
    LaunchedEffect(settings.pdfCropMargins, uiState.book?.id) {
        if (settings.pdfCropMargins && uiState.book != null) viewModel.measureMargins()
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
                    background = if (settings.pdfNight) NightPaper else pageColors.paper,
                    night = settings.pdfNight,
                    crop = uiState.contentBounds.takeIf { settings.pdfCropMargins },
                    pdfViewRef = pdfViewRef,
                    onTap = viewModel::toggleControls,
                    onPageChanged = { page, total ->
                        currentPage = page
                        totalPages  = total
                        viewModel.onPageChanged(page, total)
                    },
                    onScrollPosition = viewModel::onScrollPosition,
                    onUserTouch = viewModel::onUserInteraction,
                    onError = viewModel::onRenderError,
                    modifier = Modifier.fillMaxSize()
                )

                // ── Top bar ───────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn(tween(ControlsFadeMs)),
                    exit  = fadeOut(tween(ControlsFadeMs)),
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
                            val marked = uiState.isCurrentPageBookmarked
                            IconButton(
                                onClick = {
                                    if (marked) viewModel.removeBookmarkFromPage(currentPage)
                                    else viewModel.showBookmarkDialog()
                                },
                                modifier = Modifier.semantics {
                                    stateDescription = if (marked) "Bookmarked" else "Not bookmarked"
                                },
                            ) {
                                Icon(
                                    if (marked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    if (marked) "Remove bookmark" else "Add bookmark",
                                    tint = if (marked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = viewModel::showThumbnails) {
                                Icon(Icons.Default.GridView, "All pages")
                            }
                            PdfOverflowMenu(
                                night = settings.pdfNight,
                                crop = settings.pdfCropMargins,
                                finished = uiState.isFinished,
                                onBookmarks = viewModel::showBookmarksSheet,
                                onNight = { onEditSettings { it.copy(pdfNight = !it.pdfNight) } },
                                onCrop = { onEditSettings { it.copy(pdfCropMargins = !it.pdfCropMargins) } },
                                onFinished = { viewModel.markAsFinished() },
                            )
                        }
                    }
                }

                // ── Bottom bar ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn(tween(ControlsFadeMs)),
                    exit  = fadeOut(tween(ControlsFadeMs)),
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
                                        style = Bindery.type.numeralSmall,
                                        modifier = Modifier.widthIn(min = 28.dp)
                                    )
                                    Slider(
                                        value = scrubTarget ?: currentPage.toFloat(),
                                        onValueChange = { v -> scrubTarget = v },
                                        onValueChangeFinished = {
                                            scrubTarget?.let { v ->
                                                viewModel.onUserInteraction()
                                                pdfViewRef.value?.jumpToPage(
                                                    v.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                                )
                                            }
                                            scrubTarget = null
                                        },
                                        valueRange = 0f..(totalPages - 1).toFloat().coerceAtLeast(0f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = "Page" }
                                    )
                                    Text(
                                        "$totalPages",
                                        style = Bindery.type.numeralSmall,
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
                    viewModel.onUserInteraction()
                    pdfViewRef.value?.jumpToPage(page)
                    viewModel.hideBookmarksSheet()
                },
                onDeleteBookmark = viewModel::deleteBookmark
            )
        }
    }

    if (uiState.showThumbnails) {
        viewModel.thumbnails?.let { thumbs ->
            BinderySheet(
                onDismissRequest = viewModel::hideThumbnails,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                ThumbnailGrid(
                    thumbnails = thumbs,
                    currentPage = currentPage,
                    bookmarked = remember(uiState.bookmarks) { uiState.bookmarks.mapTo(HashSet()) { it.page } },
                    night = settings.pdfNight,
                    onPick = viewModel::jumpTo,
                )
            }
        }
    }
}

/** Controls appear and go in a short fade. */
private const val ControlsFadeMs = 120

/** The page colour of a PDF in night mode: the Night reading theme's paper. */
private val NightPaper = Color(0xFF0F1013)

/**
 * Night pages: white paper becomes [NightPaper] and black ink a soft light grey, the way
 * the Night reading theme sets text. Pictures turn to negatives; that is the trade.
 */
private val NightFilter: ColorMatrixColorFilter by lazy {
    val paper = NightPaper.toArgb()
    val ink = android.graphics.Color.rgb(0xDC, 0xD8, 0xD0)
    fun channel(shift: Int, of: Int) = (of shr shift) and 0xFF
    // out = ink + (paper - ink) * in / 255, per channel.
    fun row(shift: Int): FloatArray {
        val i = channel(shift, ink).toFloat()
        val p = channel(shift, paper).toFloat()
        return floatArrayOf((p - i) / 255f, i)
    }
    val r = row(16); val g = row(8); val b = row(0)
    ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                r[0], 0f, 0f, 0f, r[1],
                0f, g[0], 0f, 0f, g[1],
                0f, 0f, b[0], 0f, b[1],
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

@Composable
private fun PdfOverflowMenu(
    night: Boolean,
    crop: Boolean,
    finished: Boolean,
    onBookmarks: () -> Unit,
    onNight: () -> Unit,
    onCrop: () -> Unit,
    onFinished: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Bookmarks") },
                leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                onClick = { open = false; onBookmarks() },
            )
            DropdownMenuItem(
                text = { Text("Night pages") },
                leadingIcon = { Icon(Icons.Default.InvertColors, null) },
                trailingIcon = { Switch(checked = night, onCheckedChange = null) },
                onClick = { open = false; onNight() },
                modifier = Modifier.semantics { stateDescription = if (night) "On" else "Off" },
            )
            DropdownMenuItem(
                text = { Text("Crop margins") },
                leadingIcon = { Icon(Icons.Default.Crop, null) },
                trailingIcon = { Switch(checked = crop, onCheckedChange = null) },
                onClick = { open = false; onCrop() },
                modifier = Modifier.semantics { stateDescription = if (crop) "On" else "Off" },
            )
            DropdownMenuItem(
                text = { Text(if (finished) "Finished" else "Mark as finished") },
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        tint = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                enabled = !finished,
                onClick = { open = false; onFinished() },
            )
        }
    }
}

// ── Thumbnails ────────────────────────────────────────────────────────────────

/** Every page as a small picture, to see the shape of a document and jump anywhere in it. */
@Composable
private fun ThumbnailGrid(
    thumbnails: PdfThumbnails,
    currentPage: Int,
    bookmarked: Set<Int>,
    night: Boolean,
    onPick: (Int) -> Unit,
) {
    val count = thumbnails.pageCount
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Pages",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.heightIn(max = 640.dp),
        ) {
            items((0 until count).toList(), key = { it }) { page ->
                ThumbnailCell(thumbnails, page, page == currentPage, page in bookmarked, night) { onPick(page) }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    thumbnails: PdfThumbnails,
    page: Int,
    current: Boolean,
    bookmarked: Boolean,
    night: Boolean,
    onClick: () -> Unit,
) {
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page) { bitmap = thumbnails.page(page) }
    val colors = Bindery.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Page ${page + 1}" + if (bookmarked) ", bookmarked" else ""
                selected = current
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)
                .background(if (night) NightPaper else Color.White)
                .border(if (current) 2.dp else 1.dp, if (current) colors.bookcloth else colors.rule),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (night) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                            -0.85f, 0f, 0f, 0f, 220f,
                            0f, -0.85f, 0f, 0f, 216f,
                            0f, 0f, -0.85f, 0f, 208f,
                            0f, 0f, 0f, 1f, 0f,
                        ))
                    ) else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (bookmarked) {
                Icon(
                    Icons.Default.Bookmark, null,
                    tint = colors.bookcloth,
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${page + 1}",
            style = Bindery.type.numeralCaption,
            color = if (current) colors.bookcloth else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── PdfRendererView widget ─────────────────────────────────────────────────────

/** Puts the top of the screen [fraction] of the way down page [page]. */
private fun restorePlace(view: PdfRendererView, page: Int, fraction: Float) {
    val list = view.recyclerView
    val manager = list.layoutManager as? LinearLayoutManager
    val height = manager?.findViewByPosition(page)?.height ?: 0
    if (manager != null && height > 0) manager.scrollToPositionWithOffset(page, -(height * fraction).toInt())
    else view.jumpToPage(page)
}

/**
 * The page list, with night pages and margin cropping laid over it as view effects: a
 * colour filter on its layer, and a zoom that puts the text's width across the screen.
 */
private fun applyLook(view: PdfRendererView, night: Boolean, crop: PdfContentBounds?) {
    val list = view.recyclerView
    // Once laid out, the zoom needs the list's size; and after that pass, not inside it,
    // or the change is made but not drawn until something else redraws the list.
    list.doOnLayout { list.post { lookNow(view, night, crop) } }
}

private fun lookNow(view: PdfRendererView, night: Boolean, crop: PdfContentBounds?) {
    val list = view.recyclerView
    if (night) {
        list.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = NightFilter })
    } else {
        list.setLayerType(View.LAYER_TYPE_NONE, null)
    }
    val w = list.width.toFloat()
    val h = list.height
    val scale = crop?.let { (1f / it.width).coerceIn(1f, MAX_CROP_ZOOM) } ?: 1f
    if (scale <= 1.01f || w <= 0f) {
        list.scaleX = 1f; list.scaleY = 1f; list.pivotX = w / 2; list.pivotY = 0f
        list.setPadding(0, 0, 0, 0)
        list.invalidate()
        return
    }
    // The text's left edge at the screen's left: x' = p + (x - p) * s with x' = 0 at
    // x = left * w gives p = left * w * s / (s - 1).
    val left = crop!!.left * w
    list.pivotX = (left * scale / (scale - 1f)).coerceIn(0f, w)
    list.pivotY = 0f
    list.scaleX = scale
    list.scaleY = scale
    // Zoomed from the top, the bottom of the list is off screen: room at the end lets
    // the last page scroll up into view.
    list.clipToPadding = false
    list.setPadding(0, 0, 0, (h * (1f - 1f / scale)).toInt())
    list.invalidate()
}

/** Past this the page is mostly enlargement, not cropping. */
private const val MAX_CROP_ZOOM = 1.8f

@Composable
private fun PdfViewerWidget(
    file: File,
    startPage: Int,
    background: Color,
    night: Boolean,
    crop: PdfContentBounds?,
    pdfViewRef: MutableState<PdfRendererView?>,
    onTap: () -> Unit,
    onPageChanged: (page: Int, total: Int) -> Unit,
    onScrollPosition: (page: Int, fraction: Float) -> Unit,
    onUserTouch: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnScroll by rememberUpdatedState(onScrollPosition)
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

                    // Resume where the reader left off. jumpToPage queues itself
                    // internally until the renderer is ready; the ViewModel decides when
                    // the jump has landed by watching the reported pages.
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
                    // Where in the page the screen starts, for reopening on the same line.
                    view.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                            val manager = rv.layoutManager as? LinearLayoutManager ?: return
                            val first = manager.findFirstVisibleItemPosition()
                            val child = manager.findViewByPosition(first) ?: return
                            if (child.height <= 0) return
                            latestOnScroll(first, (-child.top).toFloat() / child.height)
                        }
                    })
                }.onFailure {
                    latestOnError(it.message ?: "This PDF could not be opened")
                }
            }
        },
        // The gutter around and between pages takes the reading surface colour. Set in
        // update, not factory, so it follows a theme that changes while the book is open
        // (Auto switching with the system, for one).
        update = { view ->
            view.setBackgroundColor(background.toArgb())
            applyLook(view, night, crop)
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
                items(bookmarks.sortedBy { it.page }, key = { it.id }) { bm ->
                    BinderyCard(
                        onClick = { onJumpToPage(bm.page) },
                        selected = bm.page == currentPage
                    ) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                Icon(Icons.Default.Delete, "Delete bookmark",
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
