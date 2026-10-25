package com.ebookreader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterWeightsTest {

    private val eps = 1e-4f

    @Test
    fun `front matter without text does not count as progress`() {
        // Cover, title page, dedication: no text. Then the book.
        val w = ChapterWeights(listOf(0, 0, 0, 1000, 1000))
        assertEquals(0f, w.progressAt(3, 0f), eps)
        assertEquals(0.25f, w.progressAt(3, 0.5f), eps)
        assertEquals(0.5f, w.progressAt(4, 0f), eps)
    }

    @Test
    fun `a long chapter moves progress further than a short one`() {
        val w = ChapterWeights(listOf(100, 900))
        assertEquals(0.1f, w.progressAt(1, 0f), eps)
        assertEquals(0.55f, w.progressAt(1, 0.5f), eps)
    }

    @Test
    fun `the end of the last chapter is the end of the book`() {
        val w = ChapterWeights(listOf(3, 7, 11))
        assertEquals(1f, w.progressAt(2, 1f), eps)
    }

    @Test
    fun `progress never goes backwards through the book`() {
        val weights = listOf(5, 0, 50, 1, 400, 0, 30)
        val w = ChapterWeights(weights)
        var last = -1f
        for (chapter in weights.indices) {
            for (step in 0..10) {
                val p = w.progressAt(chapter, step / 10f)
                assertTrue("dropped at $chapter/$step: $p < $last", p >= last - eps)
                last = p
            }
        }
    }

    @Test
    fun `a book with no measurable text counts chapters instead`() {
        val w = ChapterWeights(listOf(0, 0, 0, 0))
        assertEquals(0.25f, w.progressAt(1, 0f), eps)
        assertEquals(0.625f, w.progressAt(2, 0.5f), eps)
    }

    @Test
    fun `out of range input is clamped, not thrown`() {
        val w = ChapterWeights(listOf(1, 1))
        assertEquals(1f, w.progressAt(9, 1f), eps)
        assertEquals(0f, w.progressAt(-3, -1f), eps)
        assertEquals(0f, ChapterWeights(emptyList()).progressAt(0, 0.5f), eps)
    }

    @Test
    fun `each chapter starts where the text before it ends`() {
        val w = ChapterWeights(listOf(10, 20, 70))
        assertEquals(0.1f, w.progressAt(1, 0f), eps)
        assertEquals(0.3f, w.progressAt(2, 0f), eps)
        assertEquals(w.progressAt(0, 1f), w.progressAt(1, 0f), eps)
    }
}
