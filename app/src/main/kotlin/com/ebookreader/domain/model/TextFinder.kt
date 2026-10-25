package com.ebookreader.domain.model

import java.text.Normalizer

/**
 * Finds words in a chapter's text the way a reader expects: case and accents do not
 * matter, so "eleve" finds "Élève". Matches are reported as ranges of the original text,
 * which the folded copy is mapped back to.
 */
object TextFinder {

    private val CombiningMarks = Regex("""\p{Mn}+""")

    /** [text] lower-cased and stripped of accents, and for each of its characters, where it came from. */
    fun fold(text: String): Pair<String, IntArray> {
        val out = StringBuilder(text.length)
        val map = IntArray(text.length * 2)
        var size = 0
        for (i in text.indices) {
            val c = text[i]
            // Plain ASCII, nearly all of most books, skips the normaliser.
            val folded = if (c.code < 128) c.lowercaseChar().toString()
            else CombiningMarks.replace(Normalizer.normalize(c.toString(), Normalizer.Form.NFD), "").lowercase()
            for (f in folded) {
                if (size == map.size) return out.toString() to map.copyOf(size)
                out.append(f)
                map[size++] = i
            }
        }
        return out.toString() to map.copyOf(size)
    }

    /** Every place [query] occurs in [text], in order, at most [limit] of them. */
    fun findAll(text: String, query: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        val q = fold(query.trim().replace(Regex("""\s+"""), " ")).first
        if (q.isEmpty()) return emptyList()
        val (folded, map) = fold(text)
        val hits = ArrayList<IntRange>()
        var from = 0
        while (hits.size < limit) {
            val at = folded.indexOf(q, from)
            if (at < 0) break
            hits += map[at]..map[at + q.length - 1]
            from = at + q.length
        }
        return hits
    }

    /** The words around [range], trimmed to whole words, for a result list. */
    fun snippet(text: String, range: IntRange, context: Int = 48): Snippet {
        var start = (range.first - context).coerceAtLeast(0)
        var end = (range.last + 1 + context).coerceAtMost(text.length)
        if (start > 0) text.indexOf(' ', start).takeIf { it in start until range.first }?.let { start = it + 1 }
        if (end < text.length) text.lastIndexOf(' ', end).takeIf { it > range.last }?.let { end = it }
        return Snippet(
            before = (if (start > 0) "…" else "") + text.substring(start, range.first).replace('\n', ' '),
            match = text.substring(range.first, range.last + 1),
            after = text.substring(range.last + 1, end).replace('\n', ' ') + if (end < text.length) "…" else "",
        )
    }

    data class Snippet(val before: String, val match: String, val after: String)
}
