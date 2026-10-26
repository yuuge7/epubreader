package com.ebookreader.presentation.reader.comic

import com.ebookreader.presentation.reader.rememberSittingSeconds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.ebookreader.domain.model.pageProgress
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.ReaderBar
import com.ebookreader.presentation.common.ReaderProgressFooter
import com.ebookreader.presentation.common.SessionClock
import com.ebookreader.presentation.reader.pdf.pageLabel
import kotlinx.coroutines.launch
import java.io.File

/**
 * A comic, a page at a time. Swipe or tap the outer thirds to turn; tap the middle for
 * the bars; pinch or double-tap to zoom into a panel. Manga turns the other way, from
 * the reader's setting.
 */
@Composable
fun ComicReaderScreen(
    keepScreenOn: Boolean,
    rightToLeft: Boolean,
    onToggleDirection: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionSeconds = rememberSittingSeconds(
        onResume = viewModel::onScreenResumed,
        onPause = viewModel::onScreenPaused,
        elapsed = viewModel::currentSessionSeconds,
    )
    com.ebookreader.presentation.reader.ReaderWindow(keepScreenOn = keepScreenOn, showSystemBars = uiState.showControls)

    Box(Modifier.fillMaxSize().background(Bindery.colors.paper)) {
        val book = uiState.book
        when {
            uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            uiState.error != null || book == null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(uiState.error ?: "This comic could not be opened", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNavigateBack) { Text("Go back") }
            }
            else -> ComicPages(
                title = book.title,
                totalReadingSeconds = book.totalReadingSeconds,
                sessionSeconds = sessionSeconds,
                pageCount = uiState.pageCount,
                startPage = uiState.startPage,
                showControls = uiState.showControls,
                isFinished = uiState.isFinished,
                rightToLeft = rightToLeft,
                viewModel = viewModel,
                onToggleDirection = onToggleDirection,
                onNavigateBack = onNavigateBack,
            )
        }
    }
}

@Composable
private fun BoxScope.ComicPages(
    title: String,
    totalReadingSeconds: Long,
    sessionSeconds: Long,
    pageCount: Int,
    startPage: Int,
    showControls: Boolean,
    isFinished: Boolean,
    rightToLeft: Boolean,
    viewModel: ComicReaderViewModel,
    onToggleDirection: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = startPage) { pageCount }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { viewModel.onPageSettled(it) }
    }

    fun turn(by: Int) {
        val target = (pager.currentPage + by).coerceIn(0, pageCount - 1)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }

    HorizontalPager(
        state = pager,
        reverseLayout = rightToLeft,
        // A zoomed page pans instead of turning; double-tap or a tap zone turns it.
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        ComicPage(
            index = index,
            isCurrent = index == pager.currentPage,
            viewModel = viewModel,
            onZoomChanged = { if (index == pager.currentPage) zoomed = it },
            onTap = { fraction ->
                // The outer thirds turn towards the side tapped; in manga, the page to
                // the left is the next one.
                val forward = if (rightToLeft) -1 else 1
                when {
                    fraction < 0.3f -> turn(-forward)
                    fraction > 0.7f -> turn(forward)
                    else -> viewModel.toggleControls()
                }
            },
        )
    }

    AnimatedVisibility(
        visible = showControls,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        ReaderBar(BarEdge.Top) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pageLabel(pager.currentPage, pageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sessionSeconds > 0) SessionClock(sessionSeconds)
                    }
                }
                IconButton(
                    onClick = onToggleDirection,
                    modifier = Modifier.semantics { stateDescription = if (rightToLeft) "Right to left" else "Left to right" },
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        if (rightToLeft) "Pages turn right to left. Switch to left to right"
                        else "Pages turn left to right. Switch to right to left",
                        tint = if (rightToLeft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = viewModel::markAsFinished,
                    modifier = Modifier.semantics { stateDescription = if (isFinished) "Finished" else "Not finished" },
                ) {
                    Icon(
                        Icons.Default.CheckCircle, "Mark as finished",
                        tint = if (isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showControls,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        ReaderBar(BarEdge.Bottom) {
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (pageCount > 1) {
                    var scrubTarget by remember { mutableStateOf<Float?>(null) }
                    // The slider runs the way the pages do.
                    CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Slider(
                            value = scrubTarget ?: pager.currentPage.toFloat(),
                            onValueChange = { scrubTarget = it },
                            onValueChangeFinished = {
                                scrubTarget?.let { v -> scope.launch { pager.scrollToPage(v.toInt().coerceIn(0, pageCount - 1)) } }
                                scrubTarget = null
                            },
                            valueRange = 0f..(pageCount - 1).toFloat(),
                            modifier = Modifier.semantics { contentDescription = "Page" },
                        )
                    }
                }
                ReaderProgressFooter(
                    progress = pageProgress(pager.currentPage, pageCount),
                    totalReadingSeconds = totalReadingSeconds,
                )
            }
        }
    }
}

/** One page, fitted to the screen, with pinch and double-tap zoom. */
@Composable
private fun ComicPage(
    index: Int,
    isCurrent: Boolean,
    viewModel: ComicReaderViewModel,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (fraction: Float) -> Unit,
) {
    val file by produceState<File?>(null, index) { value = viewModel.pageFile(index) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val latestOnTap by rememberUpdatedState(onTap)

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun setZoom(s: Float, o: Offset) {
        scale = s
        offset = clamp(o, s)
        latestOnZoomChanged(s > 1f)
    }

    // Leaving a page puts it back to fit, so it is whole when the reader turns back.
    LaunchedEffect(isCurrent) { if (!isCurrent && scale != 1f) setZoom(1f, Offset.Zero) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { latestOnTap(it.x / this.size.width) },
                    onDoubleTap = { at ->
                        if (scale > 1f) setZoom(1f, Offset.Zero)
                        else {
                            val s = DOUBLE_TAP_SCALE
                            // Zoom towards the point tapped.
                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            setZoom(s, (center - at) * (s - 1f))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Two fingers zoom; once zoomed, one finger pans. A single finger on a
                // page at fit is left alone, so the pager turns it.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val s = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                            setZoom(s, offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f && scale != 1f) setZoom(1f, Offset.Zero)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val current = file
        if (current == null) {
            CircularProgressIndicator(Modifier.size(32.dp))
        } else {
            val density = LocalDensity.current
            // Decoded at twice the screen so a zoomed panel stays sharp.
            val widthPx = with(density) { maxWidth.roundToPx() } * 2
            val heightPx = with(density) { maxHeight.roundToPx() } * 2
            val context = LocalContext.current
            AsyncImage(
                model = remember(current, widthPx, heightPx) {
                    ImageRequest.Builder(context).data(current).size(widthPx, heightPx).precision(Precision.INEXACT).build()
                },
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 5f
