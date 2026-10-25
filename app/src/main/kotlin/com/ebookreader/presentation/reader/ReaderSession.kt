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
