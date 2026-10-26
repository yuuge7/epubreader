package com.ebookreader.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * A reader screen's sitting: [onResume] and [onPause] as the screen comes into and goes
 * out of view, so time counts only while the book is in front of the reader and is saved
 * on every pause (a swipe-away kill included), and [onPause] once more when the screen
 * closes. Returns the sitting so far in seconds, from [elapsed], updated every second for
 * the clock in the reader's bar.
 */
@Composable
fun rememberSittingSeconds(onResume: () -> Unit, onPause: () -> Unit, elapsed: () -> Long): Long {
    val latestResume by rememberUpdatedState(onResume)
    val latestPause by rememberUpdatedState(onPause)
    val latestElapsed by rememberUpdatedState(elapsed)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> latestResume()
                Lifecycle.Event.ON_PAUSE -> latestPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestPause()
        }
    }

    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            seconds = latestElapsed()
        }
    }
    return seconds
}

/**
 * The window while a book is open. The status and navigation bars show only with the
 * reader's own controls, so the page has the whole screen; a swipe from the edge still
 * brings them back for a moment. The screen stays awake if the reader asked for that,
 * and only here: the library and settings let it sleep as usual.
 */
@Composable
fun ReaderWindow(keepScreenOn: Boolean, showSystemBars: Boolean) {
    val window = androidx.activity.compose.LocalActivity.current?.window ?: return
    DisposableEffect(window, keepScreenOn) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepScreenOn) window.addFlags(flag)
        onDispose { window.clearFlags(flag) }
    }
    val controller = remember(window) {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    }
    val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
    LaunchedEffect(controller, showSystemBars) {
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showSystemBars) controller.show(bars) else controller.hide(bars)
    }
    DisposableEffect(controller) {
        onDispose { controller.show(bars) }
    }
}

/**
 * Whether a screen reader is exploring by touch. The page's tap zones then stand aside:
 * a double tap to activate lands in the middle of whatever was focused and would turn
 * the page or hide the controls instead.
 */
@Composable
fun rememberTouchExploration(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) {
        context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    }
    var exploring by remember { androidx.compose.runtime.mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}

/** The system's "remove animations": animator duration scale set to zero. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { reducedMotion(context) }
}

fun reducedMotion(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
