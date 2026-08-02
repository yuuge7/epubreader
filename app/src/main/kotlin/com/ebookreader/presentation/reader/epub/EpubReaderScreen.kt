package com.ebookreader.presentation.reader.epub

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.mutableLongStateOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.ScrollDirection
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    bookId: Long,
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // AppTheme.SYSTEM must follow the device, otherwise the app chrome goes dark
    // while the page stays blinding white.
    val isDark = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        else -> false
    }
    val isSepia = settings.theme == AppTheme.SEPIA
    val isHorizontal = settings.readerScrollDirection == ScrollDirection.HORIZONTAL

    LaunchedEffect(bookId) { viewModel.loadBook(bookId, settings.fontSize) }
    LaunchedEffect(settings.fontSize, uiState.isLoading) {
        if (!uiState.isLoading) viewModel.updateFontSize(settings.fontSize)
    }

    // Session reading timer — the ViewModel owns the clock so it stops while backgrounded.
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); sessionSeconds = viewModel.currentSessionSeconds() }
    }

    // Count reading time only while the screen is actually in front of the user, and
    // flush position + elapsed time on every pause (covers swipe-away kills too).
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
                        Text("Loading EPUB…")
                    }
                }
            }

            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(uiState.error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onNavigateBack) { Text("Go Back") }
                    }
                }
            }

            else -> {
                // WebView — takes full size
                EpubWebView(
                    html = uiState.currentChapterHtml,
                    baseUrl = uiState.currentChapterBaseUrl,
                    pendingAnchor = uiState.pendingScrollAnchor,
                    restoreFraction = uiState.pendingRestoreFraction,
                    isDarkMode = isDark,
                    isSepia = isSepia,
                    onTap = viewModel::toggleControls,
                    onScrollProgress = viewModel::updateScrollProgress,
                    onInternalLink = { path, fragment ->
                        viewModel.navigateToInternalLink(path, fragment)
                    },
                    onAnchorConsumed = viewModel::clearPendingAnchor,
                    onRestoreConsumed = viewModel::clearPendingRestore,
                    modifier = Modifier.fillMaxSize()
                )

                // ── Horizontal swipe edge zones ──────────────────────────
                // Only in horizontal mode. Left/right 18% of screen are swipe
                // zones; the middle 64% stays fully interactive for the WebView.
                if (isHorizontal) {
                    // Left zone → previous chapter
                    SwipeEdgeZone(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.18f)
                            .align(Alignment.CenterStart),
                        icon = Icons.Default.ChevronLeft,
                        iconAlignment = Alignment.CenterStart,
                        enabled = uiState.currentChapterIndex > 0,
                        onSwipeTriggered = viewModel::previousChapter
                    )
                    // Right zone → next chapter
                    SwipeEdgeZone(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.18f)
                            .align(Alignment.CenterEnd),
                        icon = Icons.Default.ChevronRight,
                        iconAlignment = Alignment.CenterEnd,
                        enabled = (uiState.currentChapterIndex < (uiState.totalChapters - 1)),
                        onSwipeTriggered = viewModel::nextChapter
                    )
                }

                // ── Top bar ──────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
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
                                    uiState.bookTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                uiState.chapters.getOrNull(uiState.currentChapterIndex)?.let {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            it.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
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
                            }
                            IconButton(
                                onClick = {
                                    if (uiState.isCurrentChapterBookmarked)
                                        viewModel.removeBookmarkFromChapter(uiState.currentChapterIndex)
                                    else viewModel.showBookmarkDialog()
                                }
                            ) {
                                Icon(
                                    if (uiState.isCurrentChapterBookmarked) Icons.Default.Bookmark
                                    else Icons.Default.BookmarkBorder,
                                    "Bookmark",
                                    tint = if (uiState.isCurrentChapterBookmarked)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = viewModel::showBookmarksSheet) {
                                Icon(Icons.Default.Bookmarks, "Bookmarks")
                            }
                            IconButton(onClick = viewModel::showTocSheet) {
                                Icon(Icons.Default.TableRows, "Contents")
                            }
                            IconButton(onClick = { viewModel.markAsFinished() }) {
                                val finishedTint =
                                    if (uiState.isFinished)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Mark as Finished",
                                    tint = finishedTint
                                )
                            }
                        }
                    }
                }

                // ── Bottom bar ───────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
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
                            // Chapter scrubber slider
                            if (uiState.totalChapters > 1) {
                                // Track the drag locally: loading a chapter on every pixel
                                // of slider movement re-renders the whole WebView.
                                var scrubTarget by remember { mutableStateOf<Float?>(null) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = viewModel::previousChapter,
                                        enabled = uiState.currentChapterIndex > 0,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ChevronLeft, "Previous",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        ((scrubTarget?.toInt() ?: uiState.currentChapterIndex) + 1)
                                            .toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 20.dp)
                                    )
                                    Slider(
                                        value = scrubTarget ?: uiState.currentChapterIndex.toFloat(),
                                        onValueChange = { v -> scrubTarget = v },
                                        onValueChangeFinished = {
                                            scrubTarget?.let { viewModel.navigateToChapter(it.toInt()) }
                                            scrubTarget = null
                                        },
                                        valueRange = 0f..(uiState.totalChapters - 1)
                                            .toFloat().coerceAtLeast(0f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        uiState.totalChapters.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 20.dp)
                                    )
                                    IconButton(
                                        onClick = viewModel::nextChapter,
                                        enabled = (uiState.currentChapterIndex < (uiState.totalChapters - 1)),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ChevronRight, "Next",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { uiState.overallReadingProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${(uiState.overallReadingProgress * 100).roundToInt()}% read",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val totalFormatted = formatTotalReadingTime(uiState.totalReadingSeconds)
                                    if (totalFormatted.isNotEmpty()) {
                                        Text(
                                            "Total: $totalFormatted",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                // Single chapter book — just show progress
                                LinearProgressIndicator(
                                    progress = { uiState.overallReadingProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs / Sheets ─────────────────────────────────────────────────────

    if (uiState.showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBookmarkDialog,
            title = { Text("Add Bookmark") },
            text = {
                Column {
                    Text("Chapter: ${uiState.chapters.getOrNull(uiState.currentChapterIndex)?.title ?: ""}")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.bookmarkNote,
                        onValueChange = viewModel::setBookmarkNote,
                        label = { Text("Note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::addBookmark) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissBookmarkDialog) { Text("Cancel") } }
        )
    }

    if (uiState.showTocSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::hideTocSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            TableOfContentsPanel(
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                onChapterSelected = viewModel::navigateToChapter
            )
        }
    }

    if (uiState.showBookmarksSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::hideBookmarksSheet,
            sheetState = rememberModalBottomSheetState()
        ) {
            EpubBookmarksPanel(
                bookmarks = uiState.bookmarks,
                currentChapterIndex = uiState.currentChapterIndex,
                onJumpToChapter = { i ->
                    viewModel.navigateToChapter(i)
                    viewModel.hideBookmarksSheet()
                },
                onDeleteBookmark = viewModel::deleteBookmark
            )
        }
    }
}

// ── Swipe edge zone ───────────────────────────────────────────────────────────

@Composable
private fun SwipeEdgeZone(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconAlignment: Alignment,
    enabled: Boolean,
    onSwipeTriggered: () -> Unit
) {
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(value = false) }

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulated = 0f; triggered = false },
                    onDragEnd = { dragAccumulated = 0f; triggered = false }
                ) { change, delta ->
                    change.consume()
                    dragAccumulated += delta
                    if (!triggered && kotlin.math.abs(dragAccumulated) > 80f) {
                        triggered = true
                        onSwipeTriggered()
                    }
                }
            },
        contentAlignment = iconAlignment
    ) {
        if (enabled) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(28.dp)
            )
        }
    }
}

// ── WebView ───────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    html: String,
    baseUrl: String,
    pendingAnchor: String?,
    restoreFraction: Float?,
    isDarkMode: Boolean,
    isSepia: Boolean,
    onTap: () -> Unit,
    onScrollProgress: (Float) -> Unit,
    onInternalLink: (path: String, fragment: String?) -> Unit,
    onAnchorConsumed: () -> Unit,
    onRestoreConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when { isDarkMode -> "#1a1a1a"; isSepia -> "#f8f0e3"; else -> "#ffffff" }
    val fg = when { isDarkMode -> "#e0e0e0"; isSepia -> "#5b4636"; else -> "#000000" }

    val themedHtml = html.replace(
        Regex("<style id=\"epub-reader-base\">", RegexOption.IGNORE_CASE),
        """<style id="epub-reader-base">body{background-color:$bg!important;color:$fg!important;}"""
    )
    // Bottom padding so the last lines are never hidden by the floating bottom bar.
    val documentHtml = themedHtml + """
        <style>body { padding-bottom: 110px !important; }</style>
    """.trimIndent()
    val resolvedBaseUrl = baseUrl.ifBlank { "file:///android_asset/" }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // What is currently displayed. Reloading is destructive (it throws away the scroll
    // position), so it may only happen when the document genuinely changed — not on
    // every recomposition, which is what scroll callbacks cause.
    val loadedDocument = remember { mutableStateOf<Pair<String, String>?>(null) }

    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnScrollProgress by rememberUpdatedState(onScrollProgress)
    val latestOnInternalLink by rememberUpdatedState(onInternalLink)

    // Applied by the LaunchedEffect below, once the document has finished loading.
    val pendingAnchorState = rememberUpdatedState(pendingAnchor)
    val pendingRestoreState = rememberUpdatedState(restoreFraction)
    val latestOnAnchorConsumed by rememberUpdatedState(onAnchorConsumed)
    val latestOnRestoreConsumed by rememberUpdatedState(onRestoreConsumed)
    val documentReady = remember { mutableStateOf(false) }

    fun scrollToAnchorJs(anchor: String) = """
        (function(){
            var el = document.getElementById('$anchor')
                    || document.querySelector('[name="$anchor"]');
            if(el){ el.scrollIntoView(true); }
        })();
    """.trimIndent()

    fun scrollToFractionJs(fraction: Float) = """
        (function(){
            var max = Math.max(document.body.scrollHeight - window.innerHeight, 0);
            window.scrollTo(0, max * $fraction);
        })();
    """.trimIndent()

    // Restore/anchor jumps must wait for layout, otherwise they scroll a document that
    // has no height yet and silently do nothing.
    LaunchedEffect(documentReady.value, pendingAnchor, restoreFraction) {
        if (!documentReady.value) return@LaunchedEffect
        val wv = webViewRef.value ?: return@LaunchedEffect
        val anchor = pendingAnchorState.value
        val fraction = pendingRestoreState.value
        when {
            anchor != null -> {
                wv.evaluateJavascript(scrollToAnchorJs(anchor), null)
                latestOnAnchorConsumed()
            }
            fraction != null && fraction > 0f -> {
                wv.evaluateJavascript(scrollToFractionJs(fraction), null)
                latestOnRestoreConsumed()
            }
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { wv ->
                webViewRef.value = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean {
                        val url = request.url
                        return when (url.scheme) {
                            "file" -> {
                                latestOnInternalLink(url.path ?: return false, url.fragment)
                                true
                            }
                            "http", "https" -> true
                            else -> false
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // Inject tap + scroll listeners AFTER page is fully loaded.
                        // Named window properties so re-injection replaces old listeners
                        // instead of stacking a second one (which made every tap fire
                        // twice and cancel itself out).
                        view.evaluateJavascript("""
                            (function(){
                                if(window._tapHandler){
                                    document.removeEventListener('click', window._tapHandler);
                                }
                                window._tapHandler = function(e){ Android.tap(); };
                                document.addEventListener('click', window._tapHandler);

                                if(window._scrollHandler){
                                    window.removeEventListener('scroll', window._scrollHandler);
                                }
                                window._lastReported = -1;
                                window._scrollHandler = function(){
                                    var max = Math.max(
                                        document.body.scrollHeight - window.innerHeight, 1);
                                    var f = Math.min(window.scrollY / max, 1.0);
                                    // Throttle: a raw scroll stream re-renders the whole
                                    // screen dozens of times a second.
                                    if(Math.abs(f - window._lastReported) < 0.002) return;
                                    window._lastReported = f;
                                    Android.scrollProgress(f);
                                };
                                window.addEventListener('scroll', window._scrollHandler);
                            })();
                        """.trimIndent(), null)
                        documentReady.value = true
                    }
                }
                wv.addJavascriptInterface(object : Any() {
                    @android.webkit.JavascriptInterface
                    fun tap() = latestOnTap()
                    @android.webkit.JavascriptInterface
                    fun scrollProgress(fraction: Float) = latestOnScrollProgress(fraction)
                }, "Android")
            }
        },
        update = { wv ->
            webViewRef.value = wv
            val document = resolvedBaseUrl to documentHtml
            if (loadedDocument.value != document) {
                loadedDocument.value = document
                documentReady.value = false
                wv.loadDataWithBaseURL(
                    resolvedBaseUrl, documentHtml, "text/html", "UTF-8", null
                )
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
        },
        modifier = modifier
    )
}

// ── TOC ───────────────────────────────────────────────────────────────────────

@Composable
private fun TableOfContentsPanel(
    chapters: List<EpubChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Table of Contents",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = 500.dp)
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val isCurrent = index == currentChapterIndex
                Surface(
                    onClick = { onChapterSelected(index) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp))
                        Text(chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        if (isCurrent) Icon(Icons.Default.PlayArrow, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Bookmarks ─────────────────────────────────────────────────────────────────

@Composable
private fun EpubBookmarksPanel(
    bookmarks: List<Bookmark>,
    currentChapterIndex: Int,
    onJumpToChapter: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Bookmarks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp))
        if (bookmarks.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No bookmarks yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    Card(
                        onClick = { onJumpToChapter(bookmark.page) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (bookmark.page == currentChapterIndex)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = if (bookmark.page == currentChapterIndex)
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null,
                                tint = if (bookmark.page == currentChapterIndex)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.chapterTitle ?: "Chapter ${bookmark.page + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                if (bookmark.note.isNotBlank())
                                    Text(bookmark.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDeleteBookmark(bookmark) }) {
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
