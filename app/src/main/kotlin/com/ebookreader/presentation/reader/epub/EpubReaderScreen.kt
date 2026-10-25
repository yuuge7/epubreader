package com.ebookreader.presentation.reader.epub

import com.ebookreader.presentation.reader.rememberSittingSeconds
import android.provider.Settings
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.ReaderLight
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.domain.model.ScrollDirection
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.ReaderBar
import com.ebookreader.presentation.common.ReaderProgressFooter
import com.ebookreader.presentation.common.SessionClock
import com.ebookreader.presentation.common.ShareCards
import com.ebookreader.presentation.common.BinderySheet
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    bookId: Long,
    startChapter: Int = -1,
    searchQuery: String? = null,
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) { viewModel.loadBook(bookId, startChapter, searchQuery) }

    val sessionSeconds = rememberSittingSeconds(
        onResume = viewModel::onScreenResumed,
        onPause = viewModel::onScreenPaused,
        elapsed = viewModel::currentSessionSeconds,
    )

    // Changes show from here at once and are saved behind them: a slider is saved when
    // it is let go, a tap straight away. Each draft is dropped once the saved settings
    // have caught up with it, and until then the next change builds on the draft, so
    // quick taps on A+ are not each worked out from the same stale size.
    var typographyDraft by remember { mutableStateOf<ReaderTypography?>(null) }
    var lightDraft by remember { mutableStateOf<ReaderLight?>(null) }
    LaunchedEffect(settings.typography) {
        if (typographyDraft == settings.typography) typographyDraft = null
    }
    LaunchedEffect(settings.readerLight) {
        if (lightDraft == settings.readerLight) lightDraft = null
    }
    val typography = typographyDraft ?: settings.typography
    // The loaded pages, by chapter, for what needs the page's own answer (read-aloud).
    val pageHandles = remember { mutableStateMapOf<Int, PageHandle>() }
    val context = LocalContext.current
    val pageColors = Bindery.colors
    LaunchedEffect(uiState.quote) {
        uiState.quote?.let { quote ->
            viewModel.consumeQuote()
            ShareCards.shareQuote(context, quote.text, quote.title, quote.author, pageColors)
        }
    }
    val light = lightDraft ?: settings.readerLight
    ReaderBrightness(light)

    // ── Read aloud ───────────────────────────────────────────────────────────
    val readAloud by viewModel.readAloud.collectAsStateWithLifecycle()
    val speaking = readAloud.isActive && readAloud.bookId == bookId
    val startReadAloud: () -> Unit = {
        val handle = pageHandles[uiState.currentChapterIndex]
        if (handle == null) viewModel.startReadAloud(null, settings.readAloudRate)
        else handle.firstVisibleText { viewModel.startReadAloud(it.ifBlank { null }, settings.readAloudRate) }
    }
    // The sentence being read is marked on its page, and the page keeps it in view.
    val speakingPage = if (speaking) pageHandles[readAloud.chapterIndex] else null
    LaunchedEffect(speakingPage, readAloud.sentence) {
        readAloud.sentence?.let { speakingPage?.speakMark(it.text) }
    }
    LaunchedEffect(speaking) {
        if (!speaking) pageHandles.values.forEach { it.speakMark(null) }
    }
    LaunchedEffect(settings.readAloudRate) { viewModel.setReadAloudRate(settings.readAloudRate) }
    LaunchedEffect(readAloud.error) {
        readAloud.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearReadAloudError()
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
                val bookDir = uiState.bookDir
                if (bookDir != null) {
                    ReaderPages(
                        state = uiState,
                        viewModel = viewModel,
                        bookDir = bookDir,
                        typography = typography,
                        swipe = settings.readerScrollDirection == ScrollDirection.HORIZONTAL,
                        pages = pageHandles,
                        // The pages stop at the system bars, which show the page colour
                        // behind them, so text never runs under the clock or the handle.
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    )
                }

                // ── Top bar ──────────────────────────────────────────────
                AnimatedVisibility(
                    visible = uiState.showControls,
                    enter = fadeIn(tween(ControlsFadeMs)),
                    exit = fadeOut(tween(ControlsFadeMs)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    ReaderTopBar(
                        state = uiState,
                        sessionSeconds = sessionSeconds,
                        onNavigateBack = onNavigateBack,
                        onReadAloud = startReadAloud,
                        viewModel = viewModel,
                    )
                }

                // ── Bottom bar, with the read-aloud controls above it ────
                Column(Modifier.align(Alignment.BottomCenter)) {
                    AnimatedVisibility(
                        visible = speaking,
                        enter = fadeIn(tween(ControlsFadeMs)),
                        exit = fadeOut(tween(ControlsFadeMs)),
                    ) {
                        ReadAloudBar(
                            state = readAloud,
                            // Alone at the foot of the screen, it keeps clear of the gesture bar.
                            insetBottom = !uiState.showControls,
                            onToggle = viewModel::toggleReadAloud,
                            onSkip = viewModel::skipSentence,
                            onRate = { rate -> onEditSettings { it.copy(readAloudRate = rate) } },
                            onStop = viewModel::stopReadAloud,
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.showControls,
                        enter = fadeIn(tween(ControlsFadeMs)),
                        exit = fadeOut(tween(ControlsFadeMs)),
                    ) {
                        ReaderBottomBar(state = uiState, viewModel = viewModel)
                    }
                }
            }
        }

        // Over everything the reader draws, bars included, like a filter on the screen.
        if (light.warmth > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(WarmthTint.copy(alpha = light.warmth * MaxWarmthAlpha))
            )
        }
    }

    // ── Dialogs / Sheets ─────────────────────────────────────────────────────

    if (uiState.showReadingSheet) {
        ReadingSheet(
            typography = typography,
            light = light,
            readingTheme = settings.readingTheme,
            appTheme = settings.theme,
            onTypographyChange = { t ->
                typographyDraft = t
                onEditSettings { it.copy(typography = t) }
            },
            onLightChange = { lightDraft = it },
            // Saves the draft rather than a value passed in: a slider's last change and
            // its finish can arrive with no recomposition in between.
            onLightCommit = {
                lightDraft?.let { l -> onEditSettings { it.copy(readerLight = l) } }
            },
            onReadingThemeChange = { theme -> onEditSettings { it.copy(readingTheme = theme) } },
            onDismiss = viewModel::hideReadingSheet,
        )
    }

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
        BinderySheet(
            onDismissRequest = viewModel::hideTocSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TableOfContentsPanel(
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                onChapterSelected = viewModel::navigateToChapter
            )
        }
    }

    if (uiState.showBookmarksSheet) {
        BinderySheet(
            onDismissRequest = viewModel::hideBookmarksSheet,
            sheetState = rememberModalBottomSheetState(),
        ) {
            MarksPanel(
                bookmarks = uiState.bookmarks,
                highlights = uiState.highlights,
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                onJumpToChapter = { i ->
                    viewModel.navigateToChapter(i)
                    viewModel.hideBookmarksSheet()
                },
                onDeleteBookmark = viewModel::deleteBookmark,
                onOpenHighlight = viewModel::goToHighlight,
            )
        }
    }

    if (uiState.showSearch) {
        BookSearchSheet(
            search = uiState.search,
            onQueryChange = viewModel::searchBook,
            onPick = viewModel::goToHit,
            onDismiss = viewModel::closeSearch,
        )
    }

    uiState.openHighlight?.let { highlight ->
        HighlightSheet(
            highlight = highlight,
            chapterTitle = uiState.chapters.getOrNull(highlight.chapterIndex)?.title,
            onColor = { viewModel.setHighlightColor(highlight, it) },
            onNote = { viewModel.editNote(highlight) },
            onQuote = { viewModel.shareQuote(highlight.text) },
            onDelete = { viewModel.deleteHighlight(highlight) },
            onDismiss = viewModel::closeHighlight,
        )
    }

    uiState.noteFor?.let { highlight ->
        NoteDialog(
            highlight = highlight,
            onSave = { viewModel.saveNote(highlight, it) },
            onDismiss = viewModel::dismissNote,
        )
    }
}

/** Controls appear and go in a short fade; sliding them would only move the text's frame. */
private const val ControlsFadeMs = 120

/** Candlelight, roughly 1900 K. */
private val WarmthTint = Color(0xFFFF9329)
/** Strong enough to take the blue out of a white page, not so strong text loses contrast. */
private const val MaxWarmthAlpha = 0.3f

// ── Pages ─────────────────────────────────────────────────────────────────────

/**
 * The open chapter and, once it has loaded, its neighbours either side, each in its own
 * WebView. Turning into a neighbour shows a page that is already laid out instead of
 * reloading the WebView, which is what made every chapter change flash.
 */
@Composable
private fun ReaderPages(
    state: EpubReaderUiState,
    viewModel: EpubReaderViewModel,
    bookDir: String,
    typography: ReaderTypography,
    swipe: Boolean,
    /** The loaded pages by chapter, kept here for whoever needs a page's own answer. */
    pages: MutableMap<Int, PageHandle>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = Bindery.colors
    val assets: WebViewAssetLoader = remember(bookDir) {
        ReaderOrigin.assetLoader(context.applicationContext, File(bookDir))
    }
    val style = PageStyle(typography, PageColors.of(colors), LocalDensity.current.fontScale)
    val css = remember(style) { readerCss(style) }
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val config = PageConfig(swipe = swipe, smooth = !reducedMotion, language = state.language)
    val events = remember(viewModel) {
        object : PageEvents {
            override fun onTap(zone: Int) = viewModel.onPageTap(zone)
            override fun onEdge(direction: Int) = viewModel.onChapterEdge(direction)
            override fun onSwipe(direction: Int) = viewModel.onSwipe(direction)
            override fun onScroll(fraction: Float) = viewModel.updateScrollProgress(fraction)
            override fun onInternalLink(path: String, fragment: String?) =
                viewModel.navigateToInternalLink(path, fragment)
            override fun onHighlightTapped(id: Long) = viewModel.onHighlightTapped(id)
            override fun onSelection(action: SelectionAction, selection: TextSelection) =
                viewModel.onSelection(action, selection)
            override fun onPositionApplied(requestId: Long) {
                viewModel.onPositionApplied(requestId)
            }
        }
    }

    val current = state.currentChapterIndex
    // The neighbours wait for the open chapter, so they never compete with it for the
    // renderer while it is the one being looked at.
    val window = if (current in pages) {
        (current - 1..current + 1).filter { it in 0 until state.totalChapters }
    } else listOf(current)

    Box(modifier.fillMaxSize()) {
        window.forEach { index ->
            key(index) {
                val document = remember { viewModel.chapterDocument(index) }
                if (document != null) {
                    DisposableEffect(Unit) {
                        onDispose {
                            pages.remove(index)
                        }
                    }
                    val active = index == current
                    val chapterHighlights = remember(state.highlights, index) {
                        state.highlights.filter { it.chapterIndex == index }
                    }
                    ChapterPage(
                        document = document,
                        active = active,
                        css = css,
                        config = config,
                        position = state.positionRequest.takeIf { active },
                        highlights = chapterHighlights,
                        assets = assets,
                        paper = colors.paper,
                        events = events,
                        onReady = { handle ->
                            pages[index] = handle
                            viewModel.onPageReady(index)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (active) 1f else 0f),
                    )
                }
            }
        }
    }
}

/** Brightness while the reader is open; the system's own setting again once it closes. */
@Composable
private fun ReaderBrightness(light: ReaderLight) {
    val window = LocalActivity.current?.window ?: return
    // Applied on each change, but reset only when the reader closes: resetting between
    // two values of a slider drag would flash the screen to the system level.
    LaunchedEffect(window, light.brightness) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (light.followsSystem) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else light.brightness
        }
    }
    DisposableEffect(window) {
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

// ── Bars ──────────────────────────────────────────────────────────────────────

@Composable
private fun ReaderTopBar(
    state: EpubReaderUiState,
    sessionSeconds: Long,
    onNavigateBack: () -> Unit,
    onReadAloud: () -> Unit,
    viewModel: EpubReaderViewModel,
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
                    state.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                state.chapters.getOrNull(state.currentChapterIndex)?.let {
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
                            SessionClock(sessionSeconds)
                        }
                    }
                }
            }
            // Type and page settings, in the reader where their effect can be seen.
            IconButton(
                onClick = viewModel::showReadingSheet,
                modifier = Modifier.semantics { contentDescription = "Text and page" }
            ) {
                Text("Aa", style = Bindery.type.bookTitle.copy(fontSize = 18.sp))
            }
            IconButton(onClick = viewModel::showTocSheet) {
                Icon(Icons.Default.TableRows, "Contents")
            }
            IconButton(
                onClick = {
                    if (state.isCurrentChapterBookmarked)
                        viewModel.removeBookmarkFromChapter(state.currentChapterIndex)
                    else viewModel.showBookmarkDialog()
                }
            ) {
                Icon(
                    if (state.isCurrentChapterBookmarked) Icons.Default.Bookmark
                    else Icons.Default.BookmarkBorder,
                    if (state.isCurrentChapterBookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (state.isCurrentChapterBookmarked)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            ReaderOverflowMenu(state, onReadAloud, viewModel)
        }
    }
}

@Composable
private fun ReaderOverflowMenu(state: EpubReaderUiState, onReadAloud: () -> Unit, viewModel: EpubReaderViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Read aloud") },
                leadingIcon = { Icon(Icons.Default.Headphones, null) },
                onClick = { open = false; onReadAloud() },
            )
            DropdownMenuItem(
                text = { Text("Find in book") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                onClick = { open = false; viewModel.openSearch() },
            )
            DropdownMenuItem(
                text = { Text("Bookmarks and highlights") },
                leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                onClick = { open = false; viewModel.showBookmarksSheet() },
            )
            DropdownMenuItem(
                text = { Text(if (state.isFinished) "Finished" else "Mark as finished") },
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        tint = if (state.isFinished) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                enabled = !state.isFinished,
                onClick = { open = false; viewModel.markAsFinished() },
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(state: EpubReaderUiState, viewModel: EpubReaderViewModel) {
    ReaderBar(BarEdge.Bottom) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Chapter scrubber slider
            if (state.totalChapters > 1) {
                // Track the drag locally: loading a chapter on every pixel of slider
                // movement would load a page per pixel.
                var scrubTarget by remember { mutableStateOf<Float?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = viewModel::previousChapter,
                        enabled = state.currentChapterIndex > 0,
                    ) {
                        Icon(Icons.Default.ChevronLeft, "Previous chapter")
                    }
                    Text(
                        ((scrubTarget?.toInt() ?: state.currentChapterIndex) + 1).toString(),
                        style = Bindery.type.numeralSmall,
                        modifier = Modifier.widthIn(min = 24.dp)
                    )
                    Slider(
                        value = scrubTarget ?: state.currentChapterIndex.toFloat(),
                        onValueChange = { v -> scrubTarget = v },
                        onValueChangeFinished = {
                            scrubTarget?.let { viewModel.navigateToChapter(it.toInt()) }
                            scrubTarget = null
                        },
                        valueRange = 0f..(state.totalChapters - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    Text(
                        state.totalChapters.toString(),
                        style = Bindery.type.numeralSmall,
                        modifier = Modifier.widthIn(min = 24.dp)
                    )
                    IconButton(
                        onClick = viewModel::nextChapter,
                        enabled = state.currentChapterIndex < state.totalChapters - 1,
                    ) {
                        Icon(Icons.Default.ChevronRight, "Next chapter")
                    }
                }
            }
            ReaderProgressFooter(
                progress = state.overallReadingProgress,
                totalReadingSeconds = state.totalReadingSeconds,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

// ── TOC ───────────────────────────────────────────────────────────────────────

@Composable
private fun TableOfContentsPanel(
    chapters: List<EpubChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Contents",
            style = MaterialTheme.typography.headlineSmall,
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
internal fun EpubBookmarksPanel(
    bookmarks: List<Bookmark>,
    currentChapterIndex: Int,
    onJumpToChapter: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Bookmarks",
            style = MaterialTheme.typography.headlineSmall,
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
                    BinderyCard(
                        onClick = { onJumpToChapter(bookmark.page) },
                        selected = bookmark.page == currentChapterIndex
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
