package com.ebookreader.domain.model

/**
 * Turns a position in a reflowable book into the share of the book read.
 *
 * Counting chapters made "41% read" wrong by ten points or more, since chapters differ
 * in length by an order of magnitude, and a book opening on a title page, a copyright
 * page and a dedication showed ~15% before a word had been read. Each chapter here
 * counts for the amount of text it holds instead.
 *
 * @param weights one entry per chapter, in reading order: how much text it holds, in any
 *   unit. A book with no measurable text at all (every chapter an image, say) falls back
 *   to counting chapters, which is the best that can be done for it.
 */
class ChapterWeights(weights: List<Int>) {

    private val shares: FloatArray
    /** Share of the book that lies before each chapter, plus a final 1.0. */
    private val starts: FloatArray

    init {
        val clean = weights.map { it.coerceAtLeast(0).toDouble() }
        val total = clean.sum()
        shares = if (total > 0.0) {
            FloatArray(clean.size) { (clean[it] / total).toFloat() }
        } else {
            FloatArray(clean.size) { 1f / clean.size }
        }
        starts = FloatArray(clean.size + 1)
        for (i in shares.indices) starts[i + 1] = starts[i] + shares[i]
    }

    /** Share of the book read at [fraction] (0 top, 1 bottom) of chapter [chapter]. */
    fun progressAt(chapter: Int, fraction: Float): Float {
        if (shares.isEmpty()) return 0f
        val i = chapter.coerceIn(0, shares.size - 1)
        val f = fraction.coerceIn(0f, 1f)
        return (starts[i] + shares[i] * f).coerceIn(0f, 1f)
    }
}
