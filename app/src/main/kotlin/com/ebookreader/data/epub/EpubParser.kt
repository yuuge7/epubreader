package com.ebookreader.data.epub

import com.ebookreader.domain.model.EpubBook
import com.ebookreader.domain.model.EpubChapter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Unpacks an EPUB into [cacheDir] and reads its package: metadata, reading order, table
 * of contents and the chapters themselves.
 *
 * Styling is not done here. The chapters come back as the book ships them, made safe for
 * an HTML parser (see [expandSelfClosingTags]); the reader dresses them in its own
 * typography and theme.
 */
class EpubParser(private val cacheDir: File) {

    data class EpubParseResult(
        val book: EpubBook,
        val extractedDir: File,
        /** chapterId -> the chapter file's markup. Entries sharing a file share the string. */
        val chapterContents: Map<String, String>,
        /** chapterId -> the chapter file, relative to [extractedDir], '/'-separated. */
        val chapterPaths: Map<String, String>,
        /**
         * How much text each chapter holds, in the order of [EpubBook.chapters]. Chapters
         * that share one file split its text between them.
         */
        val chapterWeights: List<Int>,
    )

    /**
     * @param measureText weigh each chapter by its text, for the reader's progress. Off
     *   for the import path, which only wants the metadata and would throw it away.
     */
    fun parseAndExtract(filePath: String, measureText: Boolean = true): EpubParseResult {
        val epubFile = File(filePath)
        val extractDir = extractDirFor(epubFile)
        val root = extractDir.canonicalFile

        if (!extractDir.exists() || extractDir.listFiles()?.isEmpty() == true) {
            extractDir.mkdirs()
            ZipFile(epubFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val out = File(extractDir, entry.name)
                    // Zip Slip guard: an entry named "../../databases/x" must not be able
                    // to write outside the extraction directory.
                    if (!out.isInside(root)) return@forEach
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
        }

        val containerFile = File(extractDir, "META-INF/container.xml")
        val opfPath = parseContainerXml(containerFile.readTextWithoutBom())
        val opfFile = File(extractDir, opfPath)
        val opfDir = opfFile.parentFile ?: extractDir
        val opfContent = opfFile.readTextWithoutBom()

        val opf = parseOpf(opfContent)
        val ncxHref = findNcxHref(opfContent)
        val chapters = if (ncxHref != null) {
            val ncxFile = File(opfDir, decodeHref(ncxHref))
            if (ncxFile.exists()) parseNcx(ncxFile.readTextWithoutBom(), opf.spineHrefs)
            else buildChaptersFromSpine(opf.spineHrefs)
        } else buildChaptersFromSpine(opf.spineHrefs)

        val chapterContents = mutableMapOf<String, String>()
        val chapterPaths = mutableMapOf<String, String>()
        // By file: a file several contents entries point into is read, and kept, once.
        val htmlByPath = mutableMapOf<String, String>()
        val textLengthByPath = mutableMapOf<String, Int>()

        chapters.forEach { chapter ->
            val chapterFile = File(opfDir, decodeHref(chapter.href.substringBefore("#")))
                .canonicalFile
                // A manifest or NCX entry pointing at "../../shared_prefs/x.xml" must not
                // get a file from outside the book onto the page.
                .takeIf { it.isFile && it.isInside(root) } ?: return@forEach
            val relativePath = chapterFile.relativeTo(root).invariantSeparatorsPath
            val html = htmlByPath.getOrPut(relativePath) {
                expandSelfClosingTags(chapterFile.readTextWithoutBom())
            }
            chapterContents[chapter.id] = html
            chapterPaths[chapter.id] = relativePath
            if (measureText) textLengthByPath.getOrPut(relativePath) { visibleTextLength(html) }
        }

        // A file listed by several contents entries is shown whole for each of them, so
        // its text is split between them rather than counted once per entry.
        val entriesPerFile = chapters.mapNotNull { chapterPaths[it.id] }.groupingBy { it }.eachCount()
        val chapterWeights = chapters.map { chapter ->
            val path = chapterPaths[chapter.id] ?: return@map 0
            (textLengthByPath[path] ?: 0) / entriesPerFile.getValue(path)
        }

        val book = EpubBook(
            title = opf.title, author = opf.author, chapters = chapters,
            coverImagePath = opf.coverImageHref?.let { File(opfDir, decodeHref(it)).absolutePath },
            language = opf.language,
        )
        return EpubParseResult(book, extractDir, chapterContents, chapterPaths, chapterWeights)
    }

    private fun parseContainerXml(content: String): String {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(content))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile")
                return parser.getAttributeValue(null, "full-path") ?: "OEBPS/content.opf"
            event = parser.next()
        }
        return "OEBPS/content.opf"
    }

    data class OpfData(
        val title: String, val author: String,
        val spineHrefs: List<Pair<String, String>>, val coverImageHref: String?,
        /** BCP 47 tag from dc:language; drives hyphenation in the reader. */
        val language: String?,
    )

    private fun parseOpf(content: String): OpfData {
        var title = "Unknown Title"; var author = "Unknown Author"; var language: String? = null
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var coverMetaId: String? = null; var coverImageHref: String? = null
        var inMeta = false; var inManifest = false; var inSpine = false
        try {
            val factory = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true }
            val parser = factory.newPullParser(); parser.setInput(StringReader(content))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                        "metadata" -> inMeta = true
                        "manifest" -> inManifest = true
                        "spine" -> inSpine = true
                        // Namespace-aware parsing reports the *local* name, so this sees
                        // "title"/"creator". The prefixed forms are kept for the rare
                        // OPF that declares no namespace.
                        "title", "dc:title" ->
                            if (inMeta) parser.nextText().trim().takeIf { it.isNotBlank() }?.let { title = it }
                        "creator", "dc:creator" ->
                            if (inMeta) parser.nextText().trim().takeIf { it.isNotBlank() }?.let { author = it }
                        "language", "dc:language" ->
                            if (inMeta && language == null)
                                language = parser.nextText().trim().takeIf { it.isNotBlank() }
                        "meta" -> if (inMeta && parser.getAttributeValue(null, "name") == "cover")
                            coverMetaId = parser.getAttributeValue(null, "content")
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            val props = parser.getAttributeValue(null, "properties") ?: ""
                            manifest[id] = href
                            if (props.contains("cover-image") ||
                                (mediaType.startsWith("image/") && id.lowercase().contains("cover")))
                                coverImageHref = href
                        }
                        "itemref" -> if (inSpine)
                            parser.getAttributeValue(null, "idref")?.takeIf { it.isNotBlank() }?.let { spine.add(it) }
                    }
                    XmlPullParser.END_TAG -> when (parser.name?.lowercase()) {
                        "metadata" -> inMeta = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        if (coverImageHref == null && coverMetaId != null) coverImageHref = manifest[coverMetaId]
        val spineHrefs = spine.mapNotNull { id -> manifest[id]?.let { id to it } }
        return OpfData(title, author, spineHrefs, coverImageHref, language)
    }

    /**
     * The NCX, which is what the contents list is built from. An EPUB 3 book without one
     * falls back to its reading order.
     *
     * TODO(R17): parse the EPUB 3 navigation document too. That changes the chapter list,
     *  and with it the chapter index every saved position and bookmark is stored against,
     *  so it needs a way to carry existing positions across first.
     */
    private fun findNcxHref(opfContent: String): String? {
        val ncx = Regex("""media-type="application/x-dtbncx\+xml"[^>]*href="([^"]+)"""")
        return ncx.find(opfContent)?.groupValues?.get(1)
    }

    private fun parseNcx(ncxContent: String, spineHrefs: List<Pair<String, String>>): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        try {
            val factory = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true }
            val parser = factory.newPullParser(); parser.setInput(StringReader(ncxContent))
            var index = 0; var inNavPoint = false
            var currentTitle = ""; var currentHref = ""; var currentId = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                        "navpoint" -> {
                            inNavPoint = true
                            currentId = parser.getAttributeValue(null, "id") ?: "chapter_$index"
                            currentTitle = ""; currentHref = ""
                        }
                        "text" -> if (inNavPoint && currentTitle.isEmpty()) currentTitle = parser.nextText().trim()
                        "content" -> if (inNavPoint)
                            currentHref = parser.getAttributeValue(null, "src")?.substringBefore("#") ?: ""
                    }
                    XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "navpoint" && inNavPoint) {
                        if (currentHref.isNotBlank())
                            chapters.add(EpubChapter(
                                id = currentId,
                                title = currentTitle.ifBlank { "Chapter ${index + 1}" },
                                href = currentHref, index = index++
                            ))
                        inNavPoint = false
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return chapters.ifEmpty { buildChaptersFromSpine(spineHrefs) }
    }

    private fun buildChaptersFromSpine(spineHrefs: List<Pair<String, String>>) =
        spineHrefs.mapIndexed { i, (id, href) ->
            EpubChapter(id = id, title = "Chapter ${i + 1}", href = href.substringBefore("#"), index = i)
        }

    /**
     * XmlPullParser chokes on a byte-order mark ("Unexpected token @1:2") and the whole
     * book then fails to open. Plenty of EPUBs in the wild ship a BOM in container.xml,
     * the OPF or the NCX, so strip it before parsing.
     */
    private fun File.readTextWithoutBom(): String = readText().removePrefix("\uFEFF")

    /**
     * Cache directory for an EPUB. Keyed by the full path, not just the file name —
     * two different books both called "book.epub" used to share one extraction and
     * show each other's contents.
     */
    private fun extractDirFor(epubFile: File): File {
        val key = epubFile.absolutePath.hashCode().toUInt().toString(16)
        val safeName = epubFile.nameWithoutExtension.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(cacheDir, "epub_${safeName}_$key")
    }

    /**
     * Title, author and cover, for import. Still unpacks and reads the whole book (A4 in
     * ideas.md); it only skips weighing the chapters.
     */
    fun readMetadata(filePath: String): EpubMetadata? = try {
        val result = parseAndExtract(filePath, measureText = false)
        EpubMetadata(
            title = result.book.title,
            author = result.book.author,
            coverImagePath = result.book.coverImagePath?.takeIf { File(it).exists() }
        )
    } catch (_: Exception) {
        null
    }

    data class EpubMetadata(
        val title: String,
        val author: String,
        val coverImagePath: String?
    )

    fun clearCache(filePath: String) {
        extractDirFor(File(filePath)).deleteRecursively()
    }
}

/** Whether this path, once ".." and links are resolved, lies inside [root] (canonical). */
private fun File.isInside(root: File): Boolean = canonicalFile.toPath().startsWith(root.toPath())

/**
 * Hrefs in the OPF and NCX are URLs, so a file called "chapter 1.xhtml" is listed as
 * "chapter%201.xhtml". Decoded by hand: URLDecoder would also turn '+' into a space.
 */
internal fun decodeHref(href: String): String {
    if ('%' !in href) return href
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < href.length) {
        if (href[i] == '%' && i + 2 < href.length && href[i + 1].isHexDigit() && href[i + 2].isHexDigit()) {
            out.write(href.substring(i + 1, i + 3).toInt(16))
            i += 3
        } else {
            // The plain run up to the next '%' goes over whole, so a character outside
            // the BMP keeps both halves of its surrogate pair.
            val next = href.indexOf('%', i + 1).let { if (it < 0) href.length else it }
            out.write(href.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Elements whose content is never shown as text. */
private val HiddenElements = listOf("head", "script", "style")

/**
 * Roughly how many characters a reader sees in [html]: markup, the head, scripts,
 * styles, comments and whitespace do not count, and an entity counts as the one
 * character it stands for. Only used to weigh chapters against each other, so rough is
 * enough. One pass and no copies, since it runs over every chapter each time a book opens.
 */
internal fun visibleTextLength(html: String): Int {
    var count = 0
    var i = 0
    val end = html.length
    while (i < end) {
        val c = html[i]
        when {
            html.startsWith("<!--", i) -> i = html.indexOf("-->", i + 4).let { if (it < 0) end else it + 3 }
            c == '<' -> {
                val hidden = HiddenElements.firstOrNull { html.opensElement(i, it) }
                i = html.indexOf('>', i + 1).let { if (it < 0) end else it + 1 }
                if (hidden != null) {
                    val close = html.indexOf("</$hidden", i, ignoreCase = true)
                    i = if (close < 0) end else html.indexOf('>', close).let { if (it < 0) end else it + 1 }
                }
            }
            c == '&' -> {
                count++
                i = html.entityEnd(i) ?: (i + 1)
            }
            else -> {
                if (!c.isWhitespace()) count++
                i++
            }
        }
    }
    return count
}

/** Whether a start tag for [name] (and not, say, <header> for "head") begins at [at]. */
private fun String.opensElement(at: Int, name: String): Boolean {
    val after = at + 1 + name.length
    return regionMatches(at + 1, name, 0, name.length, ignoreCase = true) &&
        (after >= length || !this[after].isLetterOrDigit())
}

/** Just past the ";" of a character reference starting at [at], or null if there is none. */
private fun String.entityEnd(at: Int): Int? {
    val semi = indexOf(';', at + 1)
    if (semi < at + 2 || semi > at + 12) return null
    for (k in at + 1 until semi) if (!this[k].isLetterOrDigit() && this[k] != '#') return null
    return semi + 1
}

/** Elements HTML defines as having no content; only these may legitimately end in "/>". */
private val VoidElements = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
    "param", "source", "track", "wbr",
)
private val SelfClosingTag = Regex("""<([A-Za-z][\w:.-]*)(\s[^<>]*?)?\s*/>""")

/**
 * Chapters are XHTML but are shown as HTML, and the HTML parser ignores the "/" in
 * `<a id="ch2"/>`: the anchor stays open and swallows the rest of the chapter, which then
 * renders as one long link. Project Gutenberg books open every chapter like this. Such
 * tags are written out as an explicit open and close; void elements are left alone.
 */
internal fun expandSelfClosingTags(html: String): String =
    SelfClosingTag.replace(html) { m ->
        val name = m.groupValues[1]
        if (name.lowercase() in VoidElements) m.value
        else "<$name${m.groupValues[2]}></$name>"
    }

/** An opening head tag, with or without attributes; never <header>. */
private val HeadTag = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
private val HeadEndTag = Regex("""</head\s*>""", RegexOption.IGNORE_CASE)

/**
 * Puts [markup] at the top of the document's head. Matches `<head profile="…">` and the
 * like too: a literal "<head>" test missed those, and the chapter was then wrapped in a
 * second html document, losing the injected viewport and styles.
 */
internal fun insertIntoHead(html: String, markup: String): String {
    val head = HeadTag.find(html) ?: return "<html><head>$markup</head><body>$html</body></html>"
    val afterTag = head.range.last + 1
    return html.substring(0, afterTag) + "\n" + markup + html.substring(afterTag)
}

/**
 * Puts [markup] at the end of the document's head, after the book's own stylesheets, so
 * that rules of equal weight in it win. Falls back to [insertIntoHead] for a head that
 * is never closed.
 */
internal fun insertAtEndOfHead(html: String, markup: String): String {
    val head = HeadTag.find(html) ?: return insertIntoHead(html, markup)
    val end = HeadEndTag.find(html, head.range.last + 1) ?: return insertIntoHead(html, markup)
    return html.substring(0, end.range.first) + markup + "\n" + html.substring(end.range.first)
}
