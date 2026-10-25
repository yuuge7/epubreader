package com.ebookreader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers the rule the v4 -> v5 migration applies to history that already exists.
 *
 * The migration rewrites rows the user cannot get back, so the property that matters most
 * is that no reading time is invented or lost, whatever the shape of the input.
 */
class ReadingSessionMergeTest {

    private val minute = 60_000L

    /** 10:00 today, so every case sits well inside one local day. */
    private fun baseTime(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun span(id: Long, bookId: Long, startedAt: Long, seconds: Long) =
        SittingSpan(
            id = id,
            bookId = bookId,
            startedAt = startedAt,
            endedAt = startedAt + seconds * 1000L,
            durationSeconds = seconds
        )

    private fun totalAfter(rows: List<SittingSpan>, plan: SittingMergePlan): Long {
        val extendedById = plan.extended.associateBy { it.id }
        return rows
            .filterNot { it.id in plan.absorbedIds }
            .sumOf { extendedById[it.id]?.durationSeconds ?: it.durationSeconds }
    }

    @Test
    fun `fragments of one sitting collapse into a single row`() {
        val t0 = baseTime()
        // The reported symptom: a ten-second fragment, then a long one starting the
        // instant it ended.
        val rows = listOf(
            span(id = 1, bookId = 7, startedAt = t0, seconds = 10),
            span(id = 2, bookId = 7, startedAt = t0 + 10_000L, seconds = 1800)
        )

        val plan = planSittingMerge(rows)

        assertEquals(listOf(2L), plan.absorbedIds)
        assertEquals(1, plan.extended.size)
        val merged = plan.extended.single()
        assertEquals(1L, merged.id)
        assertEquals(1810L, merged.durationSeconds)
        assertEquals(t0 + 10_000L + 1_800_000L, merged.endedAt)
        assertEquals(1810L, totalAfter(rows, plan))
    }

    @Test
    fun `a gap longer than the grace window starts a new sitting`() {
        val t0 = baseTime()
        val rows = listOf(
            span(id = 1, bookId = 7, startedAt = t0, seconds = 600),
            span(id = 2, bookId = 7, startedAt = t0 + 600_000L + 6 * minute, seconds = 600)
        )

        val plan = planSittingMerge(rows)

        assertTrue(plan.absorbedIds.isEmpty())
        assertTrue(plan.extended.isEmpty())
        assertEquals(1200L, totalAfter(rows, plan))
    }

    @Test
    fun `a gap inside the grace window keeps one sitting`() {
        val t0 = baseTime()
        val rows = listOf(
            span(id = 1, bookId = 7, startedAt = t0, seconds = 600),
            span(id = 2, bookId = 7, startedAt = t0 + 600_000L + 4 * minute, seconds = 600)
        )

        val plan = planSittingMerge(rows)

        assertEquals(listOf(2L), plan.absorbedIds)
        // Counted time excludes the four-minute interruption, but the span covers it.
        assertEquals(1200L, plan.extended.single().durationSeconds)
        assertEquals(1200L, totalAfter(rows, plan))
    }

    @Test
    fun `different books are never merged together`() {
        val t0 = baseTime()
        val rows = listOf(
            span(id = 1, bookId = 7, startedAt = t0, seconds = 300),
            span(id = 2, bookId = 8, startedAt = t0 + 300_000L, seconds = 300)
        )

        val plan = planSittingMerge(rows)

        assertTrue(plan.absorbedIds.isEmpty())
        assertEquals(600L, totalAfter(rows, plan))
    }

    @Test
    fun `a long run of fragments becomes one sitting and conserves every second`() {
        val t0 = baseTime()
        // Twenty fragments a minute apart — an evening of reading with the screen
        // timing out repeatedly.
        val rows = (0 until 20).map { i ->
            span(id = i + 1L, bookId = 7, startedAt = t0 + i * minute, seconds = 55)
        }

        val plan = planSittingMerge(rows)

        assertEquals(19, plan.absorbedIds.size)
        assertEquals(20 * 55L, plan.extended.single().durationSeconds)
        assertEquals(20 * 55L, totalAfter(rows, plan))
    }

    @Test
    fun `input order does not change the outcome`() {
        val t0 = baseTime()
        val rows = listOf(
            span(id = 3, bookId = 7, startedAt = t0 + 2 * minute, seconds = 60),
            span(id = 1, bookId = 7, startedAt = t0, seconds = 60),
            span(id = 2, bookId = 7, startedAt = t0 + minute, seconds = 60)
        )

        val plan = planSittingMerge(rows)

        // Oldest row survives and swallows the later two, regardless of the order given.
        assertEquals(1L, plan.extended.single().id)
        assertEquals(setOf(2L, 3L), plan.absorbedIds.toSet())
        assertEquals(180L, totalAfter(rows, plan))
    }

    @Test
    fun `history that is already whole is left completely alone`() {
        val t0 = baseTime()
        val rows = listOf(
            span(id = 1, bookId = 7, startedAt = t0, seconds = 1800),
            span(id = 2, bookId = 7, startedAt = t0 + 1_800_000L + 30 * minute, seconds = 1800)
        )

        val plan = planSittingMerge(rows)

        assertTrue(plan.extended.isEmpty())
        assertTrue(plan.absorbedIds.isEmpty())
        assertEquals(3600L, totalAfter(rows, plan))
    }

    @Test
    fun `an empty history produces an empty plan`() {
        val plan = planSittingMerge(emptyList())
        assertTrue(plan.extended.isEmpty())
        assertTrue(plan.absorbedIds.isEmpty())
    }
}
