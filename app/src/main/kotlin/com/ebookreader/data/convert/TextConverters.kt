package com.ebookreader.data.convert

import com.ebookreader.data.convert.EpubWriter.xml
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a text file whose encoding nobody wrote down: a byte-order mark if there is
 * one, else UTF-8 if the bytes are valid UTF-8, else Windows-1252, which takes any byte.
 */
internal fun decodeText(bytes: ByteArray): String {
    fun starts(vararg b: Int) = b.indices.all { i -> bytes.size > i && (bytes[i].toInt() and 0xFF) == b[i] }
    return when {
        starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }.replace("\r\n", "\n").replace('\r', '\n')
}

/** Chapters past this many characters of text get split at a paragraph. */
private const val MAX_CHAPTER_CHARS = 40_000

/**
 * A plain-text book. Chapters are found from their headings ("Chapter IV", "BOOK TWO",
 * a lone roman numeral); a file with none is cut into parts of a readable length.
 * Project Gutenberg's "Title:" and "Author:" lines supply the metadata when present.
 */
object TxtConverter {

    private val Heading = Regex(
        """^(chapter|book|part|letter|volume|section|canto|act)\b[\s.:\-]*([0-9ivxlcdm]+|[a-z]+)?\b.{0,60}$|^[ivxlcdm]+\.?$|^\d{1,3}\.?$""",
        RegexOption.IGNORE_CASE
    )

    /** "Title: …", "Author: …" and "Language: …", as Gutenberg heads its files. */
    private val HeaderField = Regex("""^(Title|Author|Language):\s*(.+)$""")
    private val ParagraphBreak = Regex("""\n[ \t]*\n+""")

    fun convert(bytes: ByteArray, fallbackTitle: String): GeneratedBook {
        val text = decodeText(bytes)
        val header = text.lineSequence().take(120)
            .mapNotNull { HeaderField.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        val title = header["Title"] ?: fallbackTitle
        val author = header["Author"] ?: "Unknown Author"
        val language = header["Language"]?.let(::languageCode)

        val paragraphs = paragraphsOf(text)
        val chapters = mutableListOf<Pair<String, MutableList<String>>>()
        var current = mutableListOf<String>()
        var currentTitle = "Opening"
        for (p in paragraphs) {
            val heading = headingOf(p)
            if (heading != null) {
                if (current.isNotEmpty()) chapters += currentTitle to current
                currentTitle = heading
                current = mutableListOf()
            } else current += p
        }
        if (current.isNotEmpty()) chapters += currentTitle to current
        // Too few headings to trust: they were probably numbers in the text, not chapters.
        val usable = if (chapters.size >= 3) chapters else listOf("Text" to paragraphs.toMutableList())

        val out = mutableListOf<GeneratedChapter>()
        usable.forEach { (heading, paras) ->
            splitBySize(paras).forEachIndexed { part, chunk ->
                val name = if (part == 0) heading else "$heading (${part + 1})"
                val body = buildString {
                    if (usable.size > 1 || part == 0) append("<h2>${xml(name)}</h2>\n")
                    chunk.forEach { append("<p>${xml(it).replace("\n", "<br/>")}</p>\n") }
                }
                out += GeneratedChapter(name, body)
            }
        }
        return GeneratedBook(title.trim(), author.trim(), language, out.ifEmpty { listOf(GeneratedChapter(title, "")) })
    }

    /**
     * A heading paragraph: "CHAPTER IV" or "XII." alone, or with its name on the next
     * line, as Gutenberg sets them ("I." then "Introduction" becomes "I. Introduction").
     */
    private fun headingOf(paragraph: String): String? {
        val lines = paragraph.trim().lines().map { it.trim() }
        if (lines.size > 2 || paragraph.length > 100 || !Heading.matches(lines[0])) return null
        val name = lines.getOrNull(1) ?: return lines[0]
        if (name.length > 60) return null
        val joiner = if (lines[0].last() in ".:") " " else ": "
        return lines[0] + joiner + name
    }

    /**
     * Paragraphs are separated by blank lines. A file that has almost none uses a line
     * per paragraph instead; one with hard-wrapped lines has them joined back up.
     */
    private fun paragraphsOf(text: String): List<String> {
        val blocks = text.split(ParagraphBreak).map { it.trim('\n') }.filter { it.isNotBlank() }
        val lines = text.count { it == '\n' } + 1
        return if (blocks.size * 20 < lines && blocks.size < 50) {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            blocks.map { block ->
                val parts = block.lines().map { it.trim() }
                // Verse and lists keep their lines; prose wrapped at ~70 columns is rejoined.
                if (parts.size > 1 && parts.dropLast(1).all { it.length in 40..90 }) parts.joinToString(" ")
                else parts.joinToString("\n")
            }
        }
    }
}

/**
 * A Markdown book: headings, paragraphs, emphasis, lists, quotes, code and rules, which is
 * what books written in Markdown use. A new chapter starts at every top-level heading
 * (or second-level, when there is only one top-level title).
 */
object MarkdownConverter {

    private val FrontMatter = Regex("""^---\n(.*?)\n---\n""", RegexOption.DOT_MATCHES_ALL)
    private val Heading = Regex("""^#{1,6} """)
    private val Rule = Regex("""^(\*\s*\*\s*\*|-\s*-\s*-|_\s*_\s*_)[\s*_-]*$""")
    private val ListMarker = Regex("""^([-*+]|\d+[.)])\s+""")
    private val CodeSpan = Regex("""`([^`]+)`""")
    private val Image = Regex("""!\[([^\]]*)]\(([^)\s]+)[^)]*\)""")
    private val Link = Regex("""\[([^\]]+)]\(([^)\s]+)[^)]*\)""")
    private val Strong = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val Emphasis = Regex("""(?<![*\w])([*_])(?=\S)(.+?)(?<=\S)\1(?![*\w])""")
    private val CodePlaceholder = Regex("""\uE000(\d+)\uE000""")

    fun convert(bytes: ByteArray, fallbackTitle: String): GeneratedBook {
        var text = decodeText(bytes)
        var title: String? = null
        var author: String? = null
        // YAML front matter, as Pandoc and static-site books carry it.
        FrontMatter.find(text)?.let { m ->
            m.groupValues[1].lines().forEach { line ->
                val (k, v) = line.split(':', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                when (k.trim().lowercase()) {
                    "title" -> title = v.trim().trim('"', '\'')
                    "author" -> author = v.trim().trim('"', '\'')
                }
            }
            text = text.substring(m.range.last + 1)
        }
        val lines = text.lines()
        val h1 = lines.count { it.startsWith("# ") }
        val chapterLevel = if (h1 <= 1) 2 else 1
        if (title == null) title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()

        val chapters = mutableListOf<GeneratedChapter>()
        var chunk = mutableListOf<String>()
        var chunkTitle = title ?: fallbackTitle
        fun flush() {
            if (chunk.any { it.isNotBlank() }) chapters += GeneratedChapter(chunkTitle, render(chunk))
            chunk = mutableListOf()
        }
        var inFence = false
        for (line in lines) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            val level = if (inFence) 0 else line.takeWhile { it == '#' }.length
            if (level == chapterLevel && line.getOrNull(level) == ' ') {
                flush()
                chunkTitle = line.drop(level).trim()
            }
            chunk += line
        }
        flush()
        return GeneratedBook(
            title = title ?: fallbackTitle,
            author = author ?: "Unknown Author",
            language = null,
            chapters = chapters.ifEmpty { listOf(GeneratedChapter(fallbackTitle, "")) },
        )
    }

    /** Block-level Markdown to XHTML. */
    internal fun render(lines: List<String>): String {
        val out = StringBuilder()
        var i = 0
        val para = mutableListOf<String>()
        fun endPara() {
            if (para.isNotEmpty()) out.append("<p>").append(inline(para.joinToString(" "))).append("</p>\n")
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    endPara()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out.append("<pre><code>").append(xml(code.joinToString("\n"))).append("</code></pre>\n")
                }
                trimmed.isEmpty() -> endPara()
                Heading.containsMatchIn(trimmed) -> {
                    endPara()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out.append("<h$level>").append(inline(trimmed.drop(level).trim().trimEnd('#').trim())).append("</h$level>\n")
                }
                Rule.matches(trimmed) -> { endPara(); out.append("<hr/>\n") }
                trimmed.startsWith(">") -> {
                    endPara()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").removePrefix(" ")
                    out.append("<blockquote>\n").append(render(quote)).append("</blockquote>\n")
                    continue
                }
                ListMarker.containsMatchIn(trimmed) -> {
                    endPara()
                    val ordered = trimmed.first().isDigit()
                    out.append(if (ordered) "<ol>\n" else "<ul>\n")
                    while (i < lines.size && ListMarker.containsMatchIn(lines[i].trimStart())) {
                        out.append("<li>").append(inline(lines[i].trim().replaceFirst(ListMarker, ""))).append("</li>\n")
                        i++
                    }
                    out.append(if (ordered) "</ol>\n" else "</ul>\n")
                    continue
                }
                line.startsWith("    ") || line.startsWith("\t") -> {
                    endPara()
                    val code = mutableListOf<String>()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t"))) {
                        code += lines[i++].removePrefix("    ").removePrefix("\t")
                    }
                    out.append("<pre><code>").append(xml(code.joinToString("\n"))).append("</code></pre>\n")
                    continue
                }
                else -> para += trimmed
            }
            i++
        }
        endPara()
        return out.toString()
    }

    /** Inline Markdown: code first (nothing inside it is markup), then links, emphasis. */
    internal fun inline(text: String): String {
        val codes = mutableListOf<String>()
        // Placeholders from the private use area: xml() drops control characters.
        var s = CodeSpan.replace(text) { codes += it.groupValues[1]; "\uE000${codes.size - 1}\uE000" }
        s = xml(s)
        s = Image.replace(s) { it.groupValues[1] }
        s = Link.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = Strong.replace(s) { "<strong>${it.groupValues[2]}</strong>" }
        s = Emphasis.replace(s) { "<em>${it.groupValues[2]}</em>" }
        s = CodePlaceholder.replace(s) { "<code>${xml(codes[it.groupValues[1].toInt()])}</code>" }
        return s
    }
}

private fun splitBySize(paragraphs: List<String>): List<List<String>> {
    val out = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    var size = 0
    for (p in paragraphs) {
        if (size > MAX_CHAPTER_CHARS && current.isNotEmpty()) {
            out += current
            current = mutableListOf()
            size = 0
        }
        current += p
        size += p.length
    }
    if (current.isNotEmpty()) out += current
    return out
}

/** "English" to "en", for the handful of languages Gutenberg names in full. */
internal fun languageCode(name: String): String? {
    val n = name.trim().lowercase()
    if (n.length in 2..3 && n.all { it.isLetter() }) return n
    return mapOf(
        "english" to "en", "french" to "fr", "german" to "de", "spanish" to "es", "italian" to "it",
        "portuguese" to "pt", "dutch" to "nl", "finnish" to "fi", "swedish" to "sv", "latin" to "la",
        "russian" to "ru", "polish" to "pl", "greek" to "el", "danish" to "da", "norwegian" to "no",
        "hungarian" to "hu", "czech" to "cs", "romanian" to "ro", "chinese" to "zh", "japanese" to "ja",
    )[n.substringBefore(',').substringBefore(' ')]
}
