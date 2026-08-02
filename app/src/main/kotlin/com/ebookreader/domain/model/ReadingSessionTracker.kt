package com.ebookreader.domain.model

import android.os.SystemClock

/**
 * Counts time actually spent looking at a book.
 *
 * Two things this deliberately gets right:
 *  - It only runs between [resume] and [pause], so time with the app in the background
 *    (or the screen off) is not counted as reading.
 *  - [takeUnsavedSeconds] hands out each second exactly once, so a screen that flushes
 *    on dispose *and* a ViewModel that flushes in onCleared cannot double-count a session.
 *
 * Uses [SystemClock.elapsedRealtime] rather than wall clock so a timezone change or an
 * NTP correction mid-session cannot produce negative or absurd durations.
 */
class ReadingSessionTracker {

    private var runningSinceMs: Long? = null
    private var accumulatedMs: Long = 0L
    private var handedOutSeconds: Long = 0L

    val isRunning: Boolean get() = runningSinceMs != null

    fun resume() {
        if (runningSinceMs == null) runningSinceMs = SystemClock.elapsedRealtime()
    }

    fun pause() {
        runningSinceMs?.let { accumulatedMs += SystemClock.elapsedRealtime() - it }
        runningSinceMs = null
    }

    /** Total seconds of this session so far, for display. */
    fun elapsedSeconds(): Long {
        val live = runningSinceMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        return (accumulatedMs + live) / 1000L
    }

    /**
     * Seconds read since the last call, marking them as handed out.
     * Returns 0 when there is nothing new to persist — calling it twice is harmless.
     */
    fun takeUnsavedSeconds(): Long {
        val total = elapsedSeconds()
        val delta = total - handedOutSeconds
        if (delta <= 0L) return 0L
        handedOutSeconds = total
        return delta
    }
}
