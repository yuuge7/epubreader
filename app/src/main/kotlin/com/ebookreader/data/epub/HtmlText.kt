package com.ebookreader.data.epub

/*
 * The text a reader actually sees in a chapter, without a DOM: one pass over the markup
 * that drops tags, the head, scripts, styles and comments and turns character references
 * into the characters they stand for. Used to weigh chapters, to fill the search index,
 * to search inside a book and to read it aloud, so it has to be cheap and not allocate
 * more than the text it returns.
 */

/** Elements whose content is never shown as text. */
private val HiddenElements = listOf("head", "script", "style")

/** Elements that end a run of text, so words either side do not run together. */
private val BreakingElements = setOf(
    "p", "div", "br", "li", "ul", "ol", "dd", "dt", "dl", "tr", "td", "th", "table",
    "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article", "aside",
    "header", "footer", "figure", "figcaption", "hr", "pre", "body",
)

/** The named references that turn up in books; any other counts as one unknown character. */
private val NamedEntities = mapOf(
    "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'', "nbsp" to ' ',
    "mdash" to '—', "ndash" to '–', "hellip" to '…', "lsquo" to '‘', "rsquo" to '’',
    "ldquo" to '“', "rdquo" to '”', "laquo" to '«', "raquo" to '»', "shy" to '\u00AD',
    "copy" to '©', "reg" to '®', "deg" to '°', "middot" to '·', "bull" to '•',
    "eacute" to 'é', "egrave" to 'è', "agrave" to 'à', "aacute" to 'á', "ccedil" to 'ç',
    "ouml" to 'ö', "uuml" to 'ü', "auml" to 'ä', "szlig" to 'ß', "aelig" to 'æ',
)

/** Takes characters one at a time without boxing them, which a (Char) -> Unit would. */
internal fun interface CharSink {
    fun accept(c: Char)
}

/**
 * Feeds the visible characters of [html] to [sink], in order. Block-level tags become a
 * single '\n' so paragraphs stay apart; a line break in the markup becomes a space and other
 * whitespace is passed through as it is.
 */
internal fun scanVisibleText(html: String, sink: CharSink) {
    var i = 0
    val end = html.length
    while (i < end) {
        val c = html[i]
        when {
            html.startsWith("<!--", i) -> i = html.indexOf("-->", i + 4).let { if (it < 0) end else it + 3 }
            c == '<' -> {
                val hidden = HiddenElements.firstOrNull { html.opensElement(i, it) }
                val close = html.indexOf('>', i + 1)
                if (close < 0) {
                    i = end
                } else {
                    if (html.isBreakingTag(i)) sink.accept('\n')
                    i = close + 1
                }
                if (hidden != null && i < end) {
                    val closing = html.indexOf("</$hidden", i, ignoreCase = true)
                    i = if (closing < 0) end else html.indexOf('>', closing).let { if (it < 0) end else it + 1 }
                }
            }
            c == '&' -> {
                val after = html.entityEnd(i)
                if (after == null) {
                    sink.accept('&'); i++
                } else {
                    sink.accept(html.decodeEntity(i + 1, after - 1)); i = after
                }
            }
            // A line break in the markup is only a space: books wrap their source at 80
            // columns, mid-paragraph. Only block tags make the '\n' that ends a paragraph.
            c == '\n' || c == '\r' -> { sink.accept(' '); i++ }
            else -> { sink.accept(c); i++ }
        }
    }
}

/**
 * Roughly how many characters a reader sees in [html]; whitespace does not count. Only
 * used to weigh chapters against each other, so rough is enough.
 */
internal fun visibleTextLength(html: String): Int {
    var count = 0
    scanVisibleText(html) { c -> if (!c.isWhitespace()) count++ }
    return count
}

/**
 * The visible text of [html] with runs of whitespace collapsed: spaces within a
 * paragraph, a single '\n' between paragraphs.
 */
internal fun visibleText(html: String): String {
    val out = StringBuilder(html.length / 2)
    var pendingSpace = false
    var pendingBreak = false
    scanVisibleText(html) { c ->
        when {
            c == '\n' -> pendingBreak = true
            c.isWhitespace() -> pendingSpace = true
            c == '\u00AD' -> Unit
            else -> {
                if (out.isNotEmpty()) {
                    if (pendingBreak) out.append('\n') else if (pendingSpace) out.append(' ')
                }
                pendingSpace = false
                pendingBreak = false
                out.append(c)
            }
        }
    }
    return out.toString()
}

/** Whether a start tag for [name] (and not, say, <header> for "head") begins at [at]. */
internal fun String.opensElement(at: Int, name: String): Boolean {
    val after = at + 1 + name.length
    return regionMatches(at + 1, name, 0, name.length, ignoreCase = true) &&
        (after >= length || !this[after].isLetterOrDigit())
}

/**
 * Whether the tag starting at [at] (start or end tag) is one of [BreakingElements],
 * checked in place: this runs for every tag of every chapter when a book opens.
 */
private fun String.isBreakingTag(at: Int): Boolean {
    var k = at + 1
    if (k < length && this[k] == '/') k++
    val start = k
    while (k < length && this[k].isLetterOrDigit()) k++
    val nameLength = k - start
    return nameLength > 0 && BreakingElements.any {
        it.length == nameLength && regionMatches(start, it, 0, nameLength, ignoreCase = true)
    }
}

/** Just past the ";" of a character reference starting at [at], or null if there is none. */
internal fun String.entityEnd(at: Int): Int? {
    val semi = indexOf(';', at + 1)
    if (semi < at + 2 || semi > at + 12) return null
    for (k in at + 1 until semi) if (!this[k].isLetterOrDigit() && this[k] != '#') return null
    return semi + 1
}

/** The character for the reference body between [from] and [to] (exclusive), "#8212" or "mdash". */
internal fun String.decodeEntity(from: Int, to: Int): Char {
    if (this[from] == '#') {
        val hex = from + 1 < to && (this[from + 1] == 'x' || this[from + 1] == 'X')
        val digits = substring(if (hex) from + 2 else from + 1, to)
        val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return '?'
        // A character outside the BMP stands in as one placeholder; only counts and
        // searches see this text, and neither cares which emoji it was.
        return if (code in 0..0xFFFF) code.toChar() else '?'
    }
    return NamedEntities[substring(from, to)] ?: '?'
}
