package com.ebookreader.presentation.reader

import com.ebookreader.domain.model.ReadingSessionTracker
import com.ebookreader.domain.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Times a sitting with a book while its reader is in front of the reader, and writes the
 * time out every minute as well as on every pause. Losing at most a minute to a crash or
 * a battery pull is the point; the sitting is extended rather than split, so flushing
 * this often costs nothing in the history.
 *
 * Writes go through [appScope] so the last one survives the screen closing.
 */
class ReadingTimeRecorder(
    private val bookRepository: BookRepository,
    private val appScope: CoroutineScope,
) {
    private val tracker = ReadingSessionTracker()
    private var flushJob: Job? = null

    /** The book being read; time is not recorded until it is set. */
    var bookId: Long = -1L

    /** Starts (or continues) the clock; [scope] runs the minute-by-minute flush. */
    fun resume(scope: CoroutineScope) {
        tracker.resume()
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** Stops the clock and writes out what was read. Calling it twice is harmless. */
    fun pause() {
        flushJob?.cancel()
        flushJob = null
        tracker.pause()
        flush()
    }

    /** This sitting so far, for the clock in the reader's bar. */
    fun elapsedSeconds(): Long = tracker.elapsedSeconds()

    private fun flush() {
        val seconds = tracker.takeUnsavedSeconds()
        if (seconds <= 0L || bookId <= 0L) return
        val id = bookId
        appScope.launch { bookRepository.addReadingSeconds(id, seconds) }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 60_000L
    }
}
