package com.ebookreader.presentation.reader.epub

import android.widget.Toast
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.AutoScroll
import com.ebookreader.domain.model.BookReadingSettings
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.ReaderLight
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.domain.model.ScrollDirection
import com.ebookreader.domain.model.TocEntry
import com.ebookreader.domain.model.flatten
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.BinderySheet
import com.ebookreader.presentation.common.ReaderBar
import com.ebookreader.presentation.common.ReaderProgressFooter
import com.ebookreader.presentation.common.ReaderTheme
import com.ebookreader.presentation.common.SessionClock
import com.ebookreader.presentation.common.ShareCards
import com.ebookreader.presentation.reader.ReaderWindow
import com.ebookreader.presentation.reader.rememberReducedMotion
import com.ebookreader.presentation.reader.rememberSittingSeconds
import com.ebookreader.presentation.reader.rememberTouchExploration
import java.io.File
import kotlin.math.roundToInt

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
    // A book can keep its own page colour; everything else follows the app.
    ReaderTheme(settings.theme, uiState.bookSettings?.readingTheme ?: settings.readingTheme) {
        EpubReaderContent(uiState, bookId, startChapter, searchQuery, settings, onEditSettings, onNavigateBack, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EpubReaderContent(
    uiState: EpubReaderUiState,
    bookId: Long,
    startChapter: Int,
    searchQuery: String?,
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: EpubReaderViewModel,
) {
    LaunchedEffect(bookId) { viewModel.loadBook(bookId, startChapter, searchQuery) }

    val sessionSeconds = rememberSittingSeconds(
        onResume = viewModel::onScreenResumed,
        onPause = viewModel::onScreenPaused,
        elapsed = viewModel::currentSessionSeconds,
    )
    val screenReader = rememberTouchExploration()
    LaunchedEffect(screenReader) { viewModel.setScreenReader(screenReader) }
    ReaderWindow(keepScreenOn = settings.keepScreenOn, showSystemBars = uiState.showControls)

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
    val bookSettings = uiState.bookSettings
    val typography = bookSettings?.typography ?: typographyDraft ?: settings.typography
    // The loaded pages, by chapter, for what needs the page's own answer.
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
        viewModel.stopAutoScroll()
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

    // Auto-scroll runs while nothing else is in front of the page.
    val anySheet = uiState.showTocSheet || uiState.showBookmarksSheet || uiState.showReadingSheet ||
        uiState.showSearch || uiState.openHighlight != null || uiState.noteFor != null || uiState.bookmarkDraft != null
    val autoScrollRunning = uiState.autoScrolling && !uiState.showControls && !anySheet && !speaking
    val autoScrollSpeed = if (autoScrollRunning) settings.autoScrollSpeed * AutoScroll.PIXELS_PER_SECOND_PER_STEP else 0f

    val addOrRemoveBookmark: () -> Unit = {
        val here = uiState.bookmarkHere
        if (here != null) viewModel.deleteBookmark(here)
        else {
            val handle = pageHandles[uiState.currentChapterIndex]
            if (handle == null) viewModel.startBookmark(null) else handle.place(viewModel::startBookmark)
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
                    val turn: (Int) -> Unit = { dir -> pageHandles[uiState.currentChapterIndex]?.turn(dir) }
                    ReaderPages(
                        state = uiState,
                        viewModel = viewModel,
                        bookDir = bookDir,
                        typography = typography,
                        config = PageConfig(
                            swipe = settings.readerScrollDirection == ScrollDirection.HORIZONTAL,
                            smooth = !rememberReducedMotion(),
                            language = uiState.language,
                            a11y = screenReader,
                            focus = settings.readingFocus,
                        ),
                        autoScroll = autoScrollSpeed,
                        pages = pageHandles,
                        // The page keeps one size whether or not the system bars are showing,
                        // so they come and go without the text reflowing, and never over it.
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                            .semantics {
                                // With a screen reader the page's tap zones stand aside;
                                // the same moves are offered as actions on the page.
                                customActions = listOf(
                                    CustomAccessibilityAction(if (uiState.showControls) "Hide controls" else "Show controls") {
                                        viewModel.setControlsVisible(!uiState.showControls); true
                                    },
                                    CustomAccessibilityAction("Next page") { turn(1); true },
                                    CustomAccessibilityAction("Previous page") { turn(-1); true },
                                    CustomAccessibilityAction("Next chapter") { viewModel.nextChapter(); true },
                                    CustomAccessibilityAction("Previous chapter") { viewModel.previousChapter(); true },
                                )
                            },
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
                        onBookmark = addOrRemoveBookmark,
                        viewModel = viewModel,
                    )
                }

                // ── Bottom bar, with read-aloud or auto-scroll above it ──
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
                        visible = uiState.autoScrolling && !speaking,
                        enter = fadeIn(tween(ControlsFadeMs)),
                        exit = fadeOut(tween(ControlsFadeMs)),
                    ) {
                        AutoScrollBar(
                            speed = settings.autoScrollSpeed,
                            running = autoScrollRunning,
                            insetBottom = !uiState.showControls,
                            onSpeed = { speed -> onEditSettings { it.copy(autoScrollSpeed = speed) } },
                            onPlay = { viewModel.setControlsVisible(false) },
                            onPause = { viewModel.setControlsVisible(true) },
                            onStop = viewModel::stopAutoScroll,
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.showControls,
                        enter = fadeIn(tween(ControlsFadeMs)),
                        exit = fadeOut(tween(ControlsFadeMs)),
                    ) {
                        ReaderBottomBar(state = uiState, paged = typography.paged, viewModel = viewModel)
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
            readingTheme = bookSettings?.readingTheme ?: settings.readingTheme,
            appTheme = settings.theme,
            focus = settings.readingFocus,
            bookOnly = bookSettings != null,
            onTypographyChange = { t ->
                if (bookSettings != null) viewModel.setBookSettings(bookSettings.copy(typography = t))
                else {
                    typographyDraft = t
                    onEditSettings { it.copy(typography = t) }
                }
            },
            onLightChange = { lightDraft = it },
            // Saves the draft rather than a value passed in: a slider's last change and
            // its finish can arrive with no recomposition in between.
            onLightCommit = {
                lightDraft?.let { l -> onEditSettings { it.copy(readerLight = l) } }
            },
            onReadingThemeChange = { theme ->
                if (bookSettings != null) viewModel.setBookSettings(bookSettings.copy(readingTheme = theme))
                else onEditSettings { it.copy(readingTheme = theme) }
            },
            onFocusChange = { focus -> onEditSettings { it.copy(readingFocus = focus) } },
            onBookOnlyChange = { only ->
                // Starts from what the book looks like now, so nothing jumps.
                viewModel.setBookSettings(
                    if (only) BookReadingSettings(typography, settings.readingTheme) else null
                )
            },
            onDismiss = viewModel::hideReadingSheet,
        )
    }

    uiState.bookmarkDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBookmarkDialog,
            title = { Text("Add bookmark") },
            text = {
                Column {
                    Text(
                        uiState.chapters.getOrNull(draft.chapterIndex)?.title.orEmpty().uppercase(),
                        style = Bindery.type.eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    draft.place?.text?.takeIf { it.isNotBlank() }?.let { words ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "“$words…”",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft.note,
                        onValueChange = viewModel::setBookmarkNote,
                        label = { Text("Note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveBookmark) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissBookmarkDialog) { Text("Cancel") } }
        )
    }

    if (uiState.showTocSheet) {
        BinderySheet(
            onDismissRequest = viewModel::hideTocSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TableOfContentsPanel(
                toc = uiState.toc,
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                onEntry = viewModel::navigateToEntry,
                onChapter = viewModel::navigateToChapter,
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
                currentBookmark = uiState.bookmarkHere,
                onOpenBookmark = viewModel::goToBookmark,
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
    config: PageConfig,
    autoScroll: Float,
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
    val events = remember(viewModel) {
        object : PageEvents {
            override fun onTap(zone: Int) = viewModel.onPageTap(zone)
            override fun onEdge(direction: Int) = viewModel.onChapterEdge(direction)
            override fun onSwipe(direction: Int) = viewModel.onSwipe(direction)
            override fun onScroll(fraction: Float, span: Float) = viewModel.updateScrollProgress(fraction, span)
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
                        autoScroll = autoScroll,
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
    onBookmark: () -> Unit,
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
            val marked = state.bookmarkHere != null
            IconButton(
                onClick = onBookmark,
                modifier = Modifier.semantics { stateDescription = if (marked) "Bookmarked" else "Not bookmarked" },
            ) {
                Icon(
                    if (marked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (marked) "Remove bookmark" else "Add bookmark",
                    tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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
                text = { Text(if (state.autoScrolling) "Stop auto-scroll" else "Auto-scroll") },
                leadingIcon = { Icon(if (state.autoScrolling) Icons.Default.Stop else Icons.Default.KeyboardDoubleArrowDown, null) },
                onClick = {
                    open = false
                    if (state.autoScrolling) viewModel.stopAutoScroll() else viewModel.startAutoScroll()
                },
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
private fun ReaderBottomBar(state: EpubReaderUiState, paged: Boolean, viewModel: EpubReaderViewModel) {
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
                        ((scrubTarget?.roundToInt() ?: state.currentChapterIndex) + 1).toString(),
                        style = Bindery.type.numeralSmall,
                        modifier = Modifier.widthIn(min = 24.dp)
                    )
                    Slider(
                        value = scrubTarget ?: state.currentChapterIndex.toFloat(),
                        onValueChange = { v -> scrubTarget = v },
                        onValueChangeFinished = {
                            scrubTarget?.let { viewModel.navigateToChapter(it.roundToInt()) }
                            scrubTarget = null
                        },
                        valueRange = 0f..(state.totalChapters - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .semantics { contentDescription = "Chapter" }
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
            if (paged && state.screenSpan < 1f) {
                // Pages of this chapter, as the layout counts them on this screen.
                val pages = (1f / state.screenSpan).roundToInt().coerceAtLeast(1)
                val page = (state.chapterFraction * (pages - 1)).roundToInt() + 1
                Text(
                    "Page $page of $pages in this chapter",
                    style = Bindery.type.numeralCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            ReaderProgressFooter(
                progress = state.overallReadingProgress,
                totalReadingSeconds = state.totalReadingSeconds,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Auto-scroll's controls, at the foot of the page while it runs: slower, the speed,
 * faster, pause or go on, and stop. The page holds still while the reader's controls are
 * up, so pausing is showing them.
 */
@Composable
private fun AutoScrollBar(
    speed: Int,
    running: Boolean,
    insetBottom: Boolean,
    onSpeed: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    ReaderBar(BarEdge.Bottom) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (insetBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardDoubleArrowDown, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text("Auto-scroll", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onSpeed((speed - 1).coerceAtLeast(AutoScroll.MIN_SPEED)) }, enabled = speed > AutoScroll.MIN_SPEED) {
                Icon(Icons.Default.Remove, "Slower")
            }
            Text(
                speed.toString(),
                style = Bindery.type.numeral.copy(fontSize = 16.sp),
                modifier = Modifier
                    .widthIn(min = 28.dp)
                    .semantics { contentDescription = "Speed $speed of ${AutoScroll.MAX_SPEED}" },
            )
            IconButton(onClick = { onSpeed((speed + 1).coerceAtMost(AutoScroll.MAX_SPEED)) }, enabled = speed < AutoScroll.MAX_SPEED) {
                Icon(Icons.Default.Add, "Faster")
            }
            IconButton(onClick = if (running) onPause else onPlay) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, if (running) "Pause" else "Go on")
            }
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop auto-scroll") }
        }
    }
}

// ── TOC ───────────────────────────────────────────────────────────────────────

/**
 * The book's contents as it nests them: parts, chapters and sections, indented by depth.
 * The entry being read is marked and scrolled to. A book without contents gets its
 * chapters instead.
 */
@Composable
private fun TableOfContentsPanel(
    toc: List<TocEntry>,
    chapters: List<EpubChapter>,
    currentChapterIndex: Int,
    onEntry: (TocEntry) -> Unit,
    onChapter: (Int) -> Unit,
) {
    val rows = remember(toc, chapters) {
        if (toc.isEmpty()) chapters.map { TocEntry(it.title, it.index) to 0 } else toc.flatten()
    }
    // The entry being read: the last one that starts at or before the open chapter.
    val current = remember(rows, currentChapterIndex) {
        rows.indexOfLast { it.first.chapterIndex <= currentChapterIndex }.takeIf { it >= 0 }
            ?.let { last ->
                // Of several entries in the same chapter, the first one is where it starts.
                val chapter = rows[last].first.chapterIndex
                rows.indexOfFirst { it.first.chapterIndex == chapter }
            }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ((current ?: 0) - 3).coerceAtLeast(0))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Contents",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                .semantics { heading() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 560.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(rows) { index, (entry, depth) ->
                val isCurrent = index == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { if (toc.isEmpty()) onChapter(entry.chapterIndex) else onEntry(entry) }
                        .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(start = 20.dp + 18.dp * depth.coerceAtMost(4), end = 20.dp, top = 10.dp, bottom = 10.dp)
                        .semantics { if (isCurrent) stateDescription = "Reading now" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.title,
                        style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.primary
                            depth == 0 -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size(width = 4.dp, height = 18.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}

// ── Bookmarks ─────────────────────────────────────────────────────────────────

/** Bookmarks in reading order, each with its chapter, its first words and its note. */
@Composable
internal fun EpubBookmarksPanel(
    bookmarks: List<Bookmark>,
    chapters: List<EpubChapter>,
    currentBookmark: Bookmark?,
    onOpen: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
) {
    val ordered = remember(bookmarks) {
        bookmarks.sortedWith(compareBy<Bookmark> { it.page }.thenBy { if (it.textOffset >= 0) it.textOffset.toFloat() else it.scrollFraction * 1e9f })
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (ordered.isEmpty()) {
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
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.heightIn(max = 520.dp)
            ) {
                items(ordered, key = { it.id }) { bookmark ->
                    val here = bookmark.id == currentBookmark?.id
                    BinderyCard(onClick = { onOpen(bookmark) }, selected = here) {
                        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null,
                                tint = if (here) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (chapters.getOrNull(bookmark.page)?.title ?: bookmark.chapterTitle ?: "Chapter ${bookmark.page + 1}").uppercase(),
                                    style = Bindery.type.eyebrow,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (bookmark.excerpt.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "“${bookmark.excerpt}…”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (bookmark.note.isNotBlank())
                                    Text(bookmark.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDeleteBookmark(bookmark) }) {
                                Icon(Icons.Default.Delete, "Delete bookmark",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
