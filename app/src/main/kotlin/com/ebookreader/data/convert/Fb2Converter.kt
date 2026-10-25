package com.ebookreader.data.convert

import android.util.Base64
import com.ebookreader.data.convert.EpubWriter.xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * FictionBook 2, the XML format much of the Russian-speaking web shares books in. The
 * main body's top-level sections become chapters; a body named "notes" becomes a notes
 * chapter at the end; embedded base64 images become files. The title, author, language
 * and series come from the title-info.
 */
object Fb2Converter {

    private const val XLINK = "http://www.w3.org/1999/xlink"

    /** Base64 decoding, swappable so the converter runs in JVM tests. */
    internal var decodeBase64: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) }

    /** Where an image goes, by its binary's id, until the binaries have been read. */
    private val ImagePlaceholder = Regex("""\u0002([^\u0003]*)\u0003""")
    private val Whitespace = Regex("""\s+""")

    fun convertZip(bytes: ByteArray, fallbackTitle: String): GeneratedBook {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    return convert(zip.readBytes(), fallbackTitle)
                }
            }
        }
        throw IllegalArgumentException("There is no .fb2 book in this archive")
    }

    fun convert(bytes: ByteArray, fallbackTitle: String): GeneratedBook {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // FB2 declares its encoding (often windows-1251) in the XML declaration; the
        // parser reads it from there when given bytes rather than a decoded string.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(ByteArrayInputStream(bytes), null)

        var title: String? = null
        val authors = mutableListOf<String>()
        var language: String? = null
        var series: String? = null
        var seriesIndex: Float? = null
        var coverId: String? = null
        val chapters = mutableListOf<GeneratedChapter>()
        val resources = mutableListOf<Resource>()
        val images = mutableMapOf<String, String>() // binary id -> resource path

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "title-info" -> {
                    // Read the whole title-info block here.
                    val depth = parser.depth
                    while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        when (parser.name) {
                            "book-title" -> title = parser.nextText().trim()
                            "lang" -> language = parser.nextText().trim().ifBlank { null }
                            "author" -> authors += readAuthor(parser)
                            "sequence" -> if (series == null) {
                                series = parser.getAttributeValue(null, "name")?.trim()?.ifBlank { null }
                                seriesIndex = parser.getAttributeValue(null, "number")?.toFloatOrNull()
                            }
                            "image" -> coverId = href(parser)?.removePrefix("#")
                        }
                    }
                }
                "body" -> {
                    val notes = parser.getAttributeValue(null, "name") == "notes"
                    chapters += readBody(parser, notes, images)
                }
                "binary" -> {
                    val id = parser.getAttributeValue(null, "id")
                    val type = parser.getAttributeValue(null, "content-type") ?: "image/jpeg"
                    val data = runCatching { decodeBase64(parser.nextText()) }.getOrNull()
                    if (id != null && data != null) {
                        val (ext, mime) = imageType(data) ?: ("jpg" to type)
                        val path = "images/${resources.size + 1}.$ext"
                        resources += Resource(path, mime, data)
                        images[id] = path
                    }
                }
            }
            event = parser.next()
        }

        // Images are referred to before the binaries that hold them, so paths are filled
        // in once everything is read.
        val resolved = chapters.map { c ->
            c.copy(body = ImagePlaceholder.replace(c.body) { m ->
                images[m.groupValues[1]]?.let { "<img src=\"../$it\" alt=\"\"/>" } ?: ""
            })
        }
        return GeneratedBook(
            title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = authors.joinToString(", ").ifBlank { "Unknown Author" },
            language = language,
            chapters = resolved.filter { it.body.isNotBlank() }.ifEmpty { listOf(GeneratedChapter(title ?: fallbackTitle, "")) },
            resources = resources,
            coverPath = coverId?.let { images[it] },
            series = series,
            seriesIndex = seriesIndex,
        )
    }

    private fun readAuthor(parser: XmlPullParser): String {
        val parts = mutableMapOf<String, String>()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) parts[parser.name] = parser.nextText().trim()
        }
        return listOfNotNull(parts["first-name"], parts["middle-name"], parts["last-name"])
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { parts["nickname"].orEmpty() }
    }

    private fun href(parser: XmlPullParser): String? =
        parser.getAttributeValue(XLINK, "href")
            ?: (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it).endsWith("href") }
                ?.let { parser.getAttributeValue(it) }

    /** The body's sections, each a chapter. Text before the first section is its own. */
    private fun readBody(parser: XmlPullParser, notes: Boolean, images: Map<String, String>): List<GeneratedChapter> {
        val chapters = mutableListOf<GeneratedChapter>()
        val loose = StringBuilder()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "section") {
                val out = StringBuilder()
                val sectionTitle = readSection(parser, out, 2)
                chapters += GeneratedChapter(
                    sectionTitle ?: if (notes) "Notes" else "Chapter ${chapters.size + 1}",
                    out.toString()
                )
            } else {
                readElement(parser, loose, 1)
            }
        }
        if (notes && chapters.isNotEmpty()) {
            return listOf(GeneratedChapter("Notes", chapters.joinToString("\n") { it.body }))
        }
        if (loose.isNotBlank()) chapters.add(0, GeneratedChapter(if (notes) "Notes" else "Opening", loose.toString()))
        return chapters
    }

    /** Writes a section into [out]; returns its title as plain text. */
    private fun readSection(parser: XmlPullParser, out: StringBuilder, level: Int): String? {
        var title: String? = null
        val id = parser.getAttributeValue(null, "id")
        if (id != null) out.append("<a id=\"${xml(id)}\"></a>")
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> {
                    val heading = StringBuilder()
                    val plain = StringBuilder()
                    readInline(parser, heading, plain, paragraphs = true)
                    if (title == null) title = plain.toString().replace(Whitespace, " ").trim()
                    val h = level.coerceAtMost(6)
                    out.append("<h$h>").append(heading.toString().trim()).append("</h$h>\n")
                }
                "section" -> readSection(parser, out, level + 1)
                else -> readElement(parser, out, level)
            }
        }
        return title?.ifBlank { null }
    }

    private fun readElement(parser: XmlPullParser, out: StringBuilder, level: Int) {
        when (parser.name) {
            "p" -> { out.append("<p>"); readInline(parser, out); out.append("</p>\n") }
            "subtitle" -> { out.append("<h${(level + 1).coerceAtMost(6)}>"); readInline(parser, out); out.append("</h${(level + 1).coerceAtMost(6)}>\n") }
            "empty-line" -> { out.append("<br/>\n"); skip(parser) }
            "text-author" -> { out.append("<p class=\"first\"><em>"); readInline(parser, out); out.append("</em></p>\n") }
            "image" -> { out.append("\u0002").append(href(parser)?.removePrefix("#").orEmpty()).append("\u0003"); skip(parser) }
            "epigraph", "cite", "annotation" -> {
                out.append("<blockquote>\n")
                children(parser) { readElement(parser, out, level) }
                out.append("</blockquote>\n")
            }
            "poem" -> {
                out.append("<div class=\"verse\">\n")
                children(parser) {
                    when (parser.name) {
                        "stanza" -> {
                            children(parser) {
                                if (parser.name == "v") { out.append("<p>"); readInline(parser, out); out.append("</p>\n") }
                                else readElement(parser, out, level)
                            }
                            out.append("<br/>\n")
                        }
                        "title" -> { out.append("<p class=\"first\"><strong>"); readInline(parser, out); out.append("</strong></p>\n") }
                        else -> readElement(parser, out, level)
                    }
                }
                out.append("</div>\n")
            }
            "table" -> {
                out.append("<table>\n")
                children(parser) {
                    if (parser.name == "tr") {
                        out.append("<tr>")
                        children(parser) {
                            val cell = if (parser.name == "th") "th" else "td"
                            out.append("<$cell>"); readInline(parser, out); out.append("</$cell>")
                        }
                        out.append("</tr>\n")
                    } else skip(parser)
                }
                out.append("</table>\n")
            }
            else -> skip(parser)
        }
    }

    /**
     * Inline content of the element the parser is on, as XHTML into [out] (and as plain
     * text into [plain]). A title's <p> children become line breaks when [paragraphs].
     */
    private fun readInline(
        parser: XmlPullParser,
        out: StringBuilder,
        plain: StringBuilder = StringBuilder(),
        paragraphs: Boolean = false,
    ) {
        val depth = parser.depth
        var first = true
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> { out.append(xml(parser.text)); plain.append(parser.text) }
                XmlPullParser.START_TAG -> when (parser.name) {
                    "emphasis" -> { out.append("<em>"); readInline(parser, out, plain); out.append("</em>") }
                    "strong" -> { out.append("<strong>"); readInline(parser, out, plain); out.append("</strong>") }
                    "strikethrough" -> { out.append("<s>"); readInline(parser, out, plain); out.append("</s>") }
                    "sub", "sup", "code" -> { val t = parser.name; out.append("<$t>"); readInline(parser, out, plain); out.append("</$t>") }
                    "a" -> {
                        val target = href(parser)
                        // Note links point inside the book; anything else is plain text.
                        if (target != null && target.startsWith("#")) {
                            out.append("<sup>"); readInline(parser, out, plain); out.append("</sup>")
                        } else readInline(parser, out, plain)
                    }
                    "image" -> { out.append("\u0002").append(href(parser)?.removePrefix("#").orEmpty()).append("\u0003"); skip(parser) }
                    "p" -> {
                        if (!first && paragraphs) { out.append("<br/>"); plain.append(' ') }
                        readInline(parser, out, plain)
                        first = false
                    }
                    else -> readInline(parser, out, plain)
                }
                XmlPullParser.END_TAG -> if (parser.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private inline fun children(parser: XmlPullParser, each: () -> Unit) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType == XmlPullParser.START_TAG) each()
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
        }
    }
}
