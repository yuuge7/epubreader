package com.ebookreader.domain.model

import java.util.Calendar

/**
 * The rules that turn raw foreground time into the sittings shown in the history.
 *
 * The reader flushes its clock on every `ON_PAUSE`, which Android fires for a screen
 * timeout, a pulled-down notification shade, a rotation or an app switch. Writing one row
 * per flush shredded a single evening's reading into a string of fragments, each stamped
 * with the moment it happened to be interrupted — so the history showed a ten-second
 * "session" followed by another that began the instant the first one ended.
 *
 * Flushing that often is worth keeping: it is what makes a swipe-away kill lose nothing.
 * So the fragments are stitched back together when they are written instead.
 */
object ReadingSessionPolicy {

    /**
     * How long a gap may be before the next stretch of reading counts as a new sitting.
     *
     * Covers a screen timeout, a phone call, or answering a message — the interruptions
     * that happen inside one sitting rather than ending it.
     */
    const val STITCH_GRACE_MS: Long = 5 * 60 * 1000L

    /**
     * Below this, an isolated stretch is not reading and is not recorded at all — not as
     * a row and not in the book's running total. Opening a book and immediately backing
     * out should leave no trace.
     *
     * This only applies to a stretch that cannot be stitched onto an existing sitting; a
     * short segment next to a longer one is always merged, never dropped.
     */
    const val MIN_SESSION_SECONDS: Long = 15L

    /**
     * Sittings never cross local midnight, so every session belongs to exactly one day
     * and the daily, monthly and yearly totals stay attributable.
     */
    fun isSameLocalDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Whether a stretch ending at [now] continues the sitting that last ended at
     * [lastEndedAt] and began at [lastStartedAt].
     */
    fun continuesSitting(lastStartedAt: Long, lastEndedAt: Long, now: Long): Boolean =
        now - lastEndedAt <= STITCH_GRACE_MS &&
            now >= lastEndedAt &&
            isSameLocalDay(lastStartedAt, now)
}
