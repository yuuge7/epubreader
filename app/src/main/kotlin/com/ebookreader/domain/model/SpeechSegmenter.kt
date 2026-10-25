package com.ebookreader.domain.model

/** A stretch of a chapter's text to speak in one go, by its place in that text. */
data class SpokenSentence(val start: Int, val end: Int, val text: String)

/**
 * Cuts a chapter's visible text into what read-aloud speaks one at a time: sentences,
 * never across a paragraph break, and long ones cut at a space so the highlight moves
 * often enough to follow. "Mr." and initials like "H. G." do not end a sentence.
 */
object SpeechSegmenter {

    private const val MAX_CHARS = 280
    private val Closers = setOf('"', '\'', ')', ']', '”', '’', '»')
    private val Abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "mt", "prof", "rev", "gen", "capt", "col", "lt", "messrs",
    )

    fun split(text: String): List<SpokenSentence> {
        val out = mutableListOf<SpokenSentence>()
        fun push(from: Int, to: Int) {
            var s = from
            var e = to
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s && (s until e).any { text[it].isLetterOrDigit() }) out += SpokenSentence(s, e, text.substring(s, e))
        }
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                push(start, i)
                start = i + 1
                i++
                continue
            }
            val terminal = c == '.' || c == '!' || c == '?' || c == '…'
            if (terminal && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in Closers)) {
                var end = i + 1
                while (end < text.length && text[end] in Closers) end++
                // "Who is there?" she said: a quoted question runs on into its sentence.
                var next = end
                while (next < text.length && text[next] == ' ') next++
                val runsOn = next < text.length && text[next].isLowerCase()
                if (!runsOn && !isAbbreviation(text, i)) {
                    push(start, end)
                    start = end
                }
                i = end
                continue
            }
            if (i - start > MAX_CHARS && c.isWhitespace()) {
                push(start, i)
                start = i
            }
            i++
        }
        push(start, text.length)
        return out
    }

    private fun isAbbreviation(text: String, dot: Int): Boolean {
        if (text[dot] != '.') return false
        var s = dot
        while (s > 0 && text[s - 1].isLetter()) s--
        val word = text.substring(s, dot)
        // An initial: one capital letter, as in "H. G. Wells".
        if (word.length == 1 && word[0].isUpperCase()) return true
        return word.lowercase() in Abbreviations
    }

    /**
     * Collapses whitespace and drops soft hyphens, as the spoken text has them, for
     * matching text from the page against it.
     */
    fun normalize(text: String): String = text.replace("\u00AD", "").replace(Whitespace, " ").trim()

    private val Whitespace = Regex("""\s+""")
}
