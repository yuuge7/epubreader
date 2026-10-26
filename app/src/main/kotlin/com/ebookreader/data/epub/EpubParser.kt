package com.ebookreader.data.epub

import com.ebookreader.domain.model.EpubBook
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.TocEntry
import com.ebookreader.domain.model.flatten
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Reads an EPUB: its package (metadata, reading order, table of contents) straight from
 * the zip, and its chapters from an unpacked copy in [cacheDir], which is also what the
 * reader's WebView serves the book's images and stylesheets from.
 *
 * Chapters are the files of the reading order (the spine). The table of contents is a
 * separate, nested list pointing into them, read from the EPUB 3 navigation document or
 * else the NCX. Chapter files are read when they are asked for, not all at once.
 *
 * Styling is not done here. The chapters come back as the book ships them, made safe for
 * an HTML parser (see [expandSelfClosingTags]); the reader dresses them in its own
 * typography and theme.
 */
class EpubParser(private val cacheDir: File) {

    /**
     * An opened book. Chapter markup is read from the unpacked files on demand and a few
     * recent chapters are kept, so the whole book is never held in memory at once.
     */
    class EpubParseResult internal constructor(
        val book: EpubBook,
        val extractedDir: File,
        /** Each chapter's file as a path inside the book, '/'-separated; null if it is missing. */
        val chapterPaths: List<String?>,
        /** Unpacks the book again: the cache it lives in can be cleared while it is open. */
        private val reextract: () -> Unit = {},
    ) {
        private val recent = object : LinkedHashMap<Int, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > RECENT_CHAPTERS
        }

        /** The chapter's markup, ready for an HTML parser; null if its file is missing. */
        fun chapterHtml(index: Int): String? {
            synchronized(recent) { recent[index] }?.let { return it }
            val html = readChapter(index) ?: return null
            synchronized(recent) { recent[index] = html }
            return html
        }

        private fun readChapter(index: Int): String? {
            val path = chapterPaths.getOrNull(index) ?: return null
            return readFile(path)?.let(::expandSelfClosingTags)
        }

        private fun readFile(path: String): String? {
            val file = File(extractedDir, path)
            if (!file.isFile) {
                // The cache was cleared under an open book: unpack it again, once.
                if (extractedDir.listFiles().isNullOrEmpty()) runCatching { reextract() }
                if (!file.isFile) return null
            }
            return file.readTextWithoutBom()
        }

        /**
         * How much text each chapter holds, in order. Reads every chapter once, without
         * keeping them; a file the spine lists twice has its text split between the two.
         */
        fun measureChapters(): List<Int> = readChapters { _, _ -> }

        /**
         * Reads each chapter file once, handing [each] its first chapter's index and its
         * markup, and returns the chapters' weights as [measureChapters] does.
         */
        fun readChapters(each: (index: Int, html: String) -> Unit): List<Int> {
            val lengthByPath = HashMap<String, Int>()
            chapterPaths.forEachIndexed { index, path ->
                if (path != null && path !in lengthByPath) {
                    val html = readFile(path)
                    lengthByPath[path] = html?.let(::visibleTextLength) ?: 0
                    if (html != null) each(index, expandSelfClosingTags(html))
                }
            }
            val perFile = chapterPaths.filterNotNull().groupingBy { it }.eachCount()
            return chapterPaths.map { path ->
                if (path == null) 0 else (lengthByPath[path] ?: 0) / perFile.getValue(path)
            }
        }

        /** The chapter whose file is [path] (inside the book), or -1. */
        fun chapterOfPath(path: String): Int {
            val clean = normalizePath("", path) ?: return -1
            chapterPaths.indexOfFirst { it.equals(clean, ignoreCase = false) }.takeIf { it >= 0 }?.let { return it }
            // Books whose links and package disagree about letter case or folders.
            chapterPaths.indexOfFirst { it.equals(clean, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
            val name = clean.substringAfterLast('/')
            return chapterPaths.indexOfFirst { it?.substringAfterLast('/').equals(name, ignoreCase = true) }
        }

        private companion object {
            const val RECENT_CHAPTERS = 6
        }
    }

    /** Unpacks the book if it is not already, and reads its package. */
    fun parseAndExtract(filePath: String): EpubParseResult {
        val epubFile = File(filePath)
        val extractDir = extractDirFor(epubFile)
        extract(epubFile, extractDir)
        val files = DirFiles(extractDir.canonicalFile)
        val pkg = readPackage(files)
        val structure = structureOf(pkg, files)
        return EpubParseResult(
            book = structure.book,
            extractedDir = extractDir,
            chapterPaths = structure.chapterPaths,
            reextract = { extract(epubFile, extractDir) },
        )
    }

    private fun extract(epubFile: File, extractDir: File) {
        if (extractDir.exists() && extractDir.listFiles()?.isNotEmpty() == true) return
        extractDir.mkdirs()
        val root = extractDir.canonicalFile
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

    // ── Package ──────────────────────────────────────────────────────────────

    /** A manifest entry; [path] is resolved inside the book, null if it points outside. */
    internal data class ManifestItem(val id: String, val href: String, val path: String?, val mediaType: String, val properties: String)

    internal data class OpfData(
        val title: String,
        val author: String,
        val language: String?,
        val series: String?,
        val seriesIndex: Float?,
        val description: String?,
        val manifest: Map<String, ManifestItem>,
        /** idrefs of the spine, in reading order. */
        val spine: List<String>,
        /** The spine's toc attribute: the NCX's manifest id in EPUB 2. */
        val spineToc: String?,
        val coverPath: String?,
    )

    internal data class PackageDoc(val opfPath: String, val opfDir: String, val rawOpf: String, val opf: OpfData)

    private class Structure(val book: EpubBook, val chapterPaths: List<String?>)

    private fun readPackage(files: BookFiles): PackageDoc {
        val opfPath = files.text("META-INF/container.xml")?.let(::parseContainerXml)
            ?.let { normalizePath("", it) } ?: "OEBPS/content.opf"
        val opfDir = opfPath.substringBeforeLast('/', "")
        val raw = files.text(opfPath).orEmpty()
        return PackageDoc(opfPath, opfDir, raw, parseOpf(raw, opfDir))
    }

    private fun structureOf(pkg: PackageDoc, files: BookFiles): Structure {
        val opf = pkg.opf
        val spineItems = opf.spine.mapNotNull { opf.manifest[it] }
        val chapterPaths = spineItems.map { item -> item.path?.takeIf { files.exists(it) } }
        val toc = readToc(pkg, files, chapterPaths)
        val titles = chapterTitles(chapterPaths.size, toc, opf.title)
        val chapters = spineItems.mapIndexed { i, item ->
            EpubChapter(id = item.id, title = titles[i], href = chapterPaths[i] ?: item.href, index = i)
        }
        val book = EpubBook(
            title = opf.title,
            author = opf.author,
            chapters = chapters,
            toc = toc,
            coverImagePath = opf.coverPath?.let { files.absolutePath(it) },
            description = opf.description,
            language = opf.language,
            series = opf.series,
            seriesIndex = opf.seriesIndex,
        )
        return Structure(book, chapterPaths)
    }

    private fun parseContainerXml(content: String): String? = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(content))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile")
                return parser.getAttributeValue(null, "full-path")
            event = parser.next()
        }
        null
    }.getOrNull()

    private fun parseOpf(content: String, opfDir: String): OpfData {
        var title = "Unknown Title"; var author = "Unknown Author"; var language: String? = null
        var description: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var spineToc: String? = null
        var coverMetaId: String? = null; var coverHref: String? = null
        // Calibre's <meta name="calibre:series">, or EPUB 3's belongs-to-collection
        // refined by a group-position.
        var series: String? = null; var seriesIndex: Float? = null
        var collectionId: String? = null
        val positions = mutableMapOf<String, Float>()
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
                        "spine" -> {
                            inSpine = true
                            spineToc = parser.getAttributeValue(null, "toc")
                        }
                        // Namespace-aware parsing reports the *local* name, so this sees
                        // "title"/"creator". The prefixed forms are kept for the rare
                        // OPF that declares no namespace.
                        "title", "dc:title" ->
                            if (inMeta) parser.elementText().trim().takeIf { it.isNotBlank() }?.let { title = it }
                        "creator", "dc:creator" ->
                            if (inMeta) parser.elementText().trim().takeIf { it.isNotBlank() }?.let { author = it }
                        "language", "dc:language" ->
                            if (inMeta && language == null)
                                language = parser.elementText().trim().takeIf { it.isNotBlank() }
                        "description", "dc:description" ->
                            if (inMeta && description == null)
                                description = plainDescription(parser.elementText())
                        "meta" -> if (inMeta) {
                            val name = parser.getAttributeValue(null, "name")
                            val property = parser.getAttributeValue(null, "property")
                            when {
                                name == "cover" -> coverMetaId = parser.getAttributeValue(null, "content")
                                name == "calibre:series" ->
                                    series = parser.getAttributeValue(null, "content")?.trim()?.ifBlank { null }
                                name == "calibre:series_index" ->
                                    seriesIndex = parser.getAttributeValue(null, "content")?.toFloatOrNull()
                                property == "belongs-to-collection" -> {
                                    val id = parser.getAttributeValue(null, "id")
                                    val text = parser.elementText().trim()
                                    if (series == null && text.isNotEmpty()) { series = text; collectionId = id }
                                }
                                property == "group-position" -> {
                                    val refines = parser.getAttributeValue(null, "refines")?.removePrefix("#")
                                    val value = parser.elementText().trim().toFloatOrNull()
                                    if (refines != null && value != null) positions[refines] = value
                                }
                            }
                        }
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            val props = parser.getAttributeValue(null, "properties") ?: ""
                            manifest[id] = ManifestItem(id, href, normalizePath(opfDir, href), mediaType, props)
                            if (props.split(' ').contains("cover-image") ||
                                (coverHref == null && mediaType.startsWith("image/") && id.lowercase().contains("cover")))
                                coverHref = href
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
        if (coverHref == null && coverMetaId != null) coverHref = manifest[coverMetaId]?.href
        if (seriesIndex == null) seriesIndex = collectionId?.let { positions[it] }
        return OpfData(
            title = title, author = author, language = language, series = series,
            seriesIndex = seriesIndex, description = description, manifest = manifest,
            spine = spine, spineToc = spineToc,
            coverPath = coverHref?.let { normalizePath(opfDir, it) },
        )
    }

    // ── Table of contents ────────────────────────────────────────────────────

    /** An entry as the book writes it: a path inside the book and a fragment. */
    private class RawEntry(val title: String, val path: String?, val fragment: String?, val children: List<RawEntry>)

    /**
     * The EPUB 3 navigation document when there is one with entries, else the NCX. Each
     * entry is tied to the chapter whose file it points into; an entry pointing nowhere
     * is dropped, its own entries taking its place.
     */
    private fun readToc(pkg: PackageDoc, files: BookFiles, chapterPaths: List<String?>): List<TocEntry> {
        val opf = pkg.opf
        val nav = opf.manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        val fromNav = nav?.path?.let { path ->
            files.text(path)?.let { parseNav(it, path.substringBeforeLast('/', "")) }
        }.orEmpty()
        val raw = fromNav.ifEmpty {
            val ncx = opf.manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
                ?: opf.spineToc?.let { opf.manifest[it] }
            ncx?.path?.let { path ->
                files.text(path)?.let { parseNcx(it, path.substringBeforeLast('/', "")) }
            }.orEmpty()
        }
        val indexByPath = HashMap<String, Int>()
        chapterPaths.forEachIndexed { i, p -> if (p != null) indexByPath.putIfAbsent(p, i) }

        fun resolve(entries: List<RawEntry>): List<TocEntry> = entries.flatMap { e ->
            val children = resolve(e.children)
            val index = e.path?.let { indexByPath[it] }
            when {
                index != null -> listOf(
                    TocEntry(e.title.ifBlank { "Section" }, index, e.fragment?.ifBlank { null }, children)
                )
                // A heading with no link of its own opens where its first entry does.
                e.path == null && children.isNotEmpty() ->
                    listOf(TocEntry(e.title.ifBlank { children.first().title }, children.first().chapterIndex, children.first().fragment, children))
                else -> children
            }
        }
        return resolve(raw)
    }

    /** The `nav` of type toc (or the first nav) of an EPUB 3 navigation document. */
    private fun parseNav(content: String, baseDir: String): List<RawEntry> {
        class Node {
            val title = StringBuilder()
            var href: String? = null
            var labelled = false
            val children = mutableListOf<Node>()
        }
        fun parse(requireToc: Boolean): List<Node> {
            val root = Node()
            runCatching {
                val parser = relaxedParser(content)
                val items = ArrayDeque<Node>()
                var inToc = false
                var done = false
                var depth = 0
                var captureDepth = -1
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT && !done) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            val name = parser.name.substringAfter(':').lowercase()
                            if (name == "nav" && !inToc) {
                                val type = parser.attribute("epub:type") ?: parser.attribute("type")
                                val role = parser.attribute("role")
                                inToc = !requireToc || type?.split(' ')?.contains("toc") == true || role == "doc-toc"
                            } else if (inToc) when (name) {
                                "li" -> {
                                    val node = Node()
                                    (items.lastOrNull() ?: root).children += node
                                    items.addLast(node)
                                }
                                "a", "span" -> items.lastOrNull()?.let { node ->
                                    if (!node.labelled && captureDepth < 0) {
                                        if (name == "a") node.href = parser.attribute("href")
                                        captureDepth = depth
                                    }
                                }
                            }
                        }
                        XmlPullParser.TEXT -> if (captureDepth >= 0) items.lastOrNull()?.title?.append(parser.text)
                        XmlPullParser.END_TAG -> {
                            val name = parser.name.substringAfter(':').lowercase()
                            if (depth == captureDepth) {
                                captureDepth = -1
                                items.lastOrNull()?.labelled = true
                            }
                            if (inToc && name == "li") items.removeLastOrNull()
                            if (inToc && name == "nav") { inToc = false; done = true }
                            depth--
                        }
                    }
                    ev = parser.next()
                }
            }
            return root.children
        }
        fun convert(nodes: List<Node>): List<RawEntry> = nodes.map { n ->
            val href = n.href?.trim()
            RawEntry(
                title = n.title.toString().replace(Whitespace, " ").trim(),
                path = href?.substringBefore('#')?.let { if (it.isEmpty()) null else normalizePath(baseDir, it) },
                fragment = href?.substringAfter('#', "")?.let(::decodeHref),
                children = convert(n.children),
            )
        }
        return convert(parse(requireToc = true).ifEmpty { parse(requireToc = false) })
    }

    private fun parseNcx(content: String, baseDir: String): List<RawEntry> {
        class Point(val title: StringBuilder = StringBuilder(), var src: String? = null, var hasLabel: Boolean = false) {
            val children = mutableListOf<Point>()
        }
        val root = Point()
        runCatching {
            val parser = relaxedParser(content)
            val points = ArrayDeque<Point>()
            var inLabel = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                        "navpoint" -> {
                            val point = Point()
                            (points.lastOrNull() ?: root).children += point
                            points.addLast(point)
                        }
                        "navlabel" -> inLabel = points.lastOrNull()?.hasLabel == false
                        "content" -> points.lastOrNull()?.let { if (it.src == null) it.src = parser.attribute("src") }
                    }
                    XmlPullParser.TEXT -> if (inLabel) points.lastOrNull()?.title?.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                        "navlabel" -> {
                            if (inLabel) points.lastOrNull()?.hasLabel = true
                            inLabel = false
                        }
                        "navpoint" -> points.removeLastOrNull()
                    }
                }
                ev = parser.next()
            }
        }
        fun convert(list: List<Point>): List<RawEntry> = list.map { p ->
            val src = p.src?.trim()
            RawEntry(
                title = p.title.toString().replace(Whitespace, " ").trim(),
                path = src?.substringBefore('#')?.let { if (it.isEmpty()) null else normalizePath(baseDir, it) },
                fragment = src?.substringAfter('#', "")?.let(::decodeHref),
                children = convert(p.children),
            )
        }
        return convert(root.children)
    }

    /**
     * Each chapter is named by the contents entry that opens it at its top, the most
     * specific one when entries nest (a chapter over the part it opens), else the first
     * that points into it at all. A file the contents leave out (a title page, an
     * epigraph) carries on the name of the one before it; before any, the book's title.
     * A book with no contents at all has its chapters numbered.
     */
    private fun chapterTitles(count: Int, toc: List<TocEntry>, bookTitle: String): List<String> {
        val flat = toc.flatten()
        if (flat.isEmpty()) return List(count) { "Chapter ${it + 1}" }
        val whole = HashMap<Int, Pair<String, Int>>()
        val any = HashMap<Int, String>()
        flat.forEach { (e, depth) ->
            if (e.fragment == null) {
                val known = whole[e.chapterIndex]
                if (known == null || depth > known.second) whole[e.chapterIndex] = e.title to depth
            }
            any.putIfAbsent(e.chapterIndex, e.title)
        }
        var previous: String? = null
        return List(count) { i ->
            val title = whole[i]?.first ?: any[i] ?: previous ?: bookTitle
            previous = title
            title
        }
    }

    // ── Metadata for import ──────────────────────────────────────────────────

    data class EpubMetadata(
        val title: String,
        val author: String,
        /** The cover image's bytes, as the book ships it. */
        val coverImage: ByteArray?,
        val series: String? = null,
        val seriesIndex: Float? = null,
        val description: String? = null,
    )

    /**
     * Title, author, cover, series and blurb, read from the zip without unpacking the
     * book or reading its chapters: a few hundred milliseconds where a full parse of a
     * large book took seconds.
     */
    fun readMetadata(filePath: String, withCover: Boolean = true): EpubMetadata? = try {
        ZipFile(File(filePath)).use { zip ->
            val files = ZipFiles(zip)
            val pkg = readPackage(files)
            val opf = pkg.opf
            EpubMetadata(
                title = opf.title,
                author = opf.author,
                coverImage = if (withCover) opf.coverPath?.let(files::bytes) else null,
                series = opf.series,
                seriesIndex = opf.seriesIndex,
                description = opf.description,
            )
        }
    } catch (_: Exception) {
        null
    }

    // ── Chapter numbering, old and new ───────────────────────────────────────

    /**
     * For each chapter as the app numbered them before chapters followed the spine (see
     * [com.ebookreader.domain.model.ChapterLayout.LEGACY]), the chapter it is now. Null
     * when the book cannot be read.
     */
    fun legacyChapterMap(filePath: String): IntArray? = try {
        ZipFile(File(filePath)).use { zip ->
            val files = ZipFiles(zip)
            val pkg = readPackage(files)
            val current = structureOf(pkg, files).chapterPaths
            val legacy = legacyChapterPaths(pkg, files)
            IntArray(legacy.size) { i ->
                val path = legacy[i]
                val found = if (path == null) -1 else current.indexOf(path)
                when {
                    found >= 0 -> found
                    // A file that is not in the reading order: the closest chapter by number.
                    else -> i.coerceAtMost((current.size - 1).coerceAtLeast(0))
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The chapter files in the legacy numbering, reproduced as the old parser built it:
     * one per NCX navPoint (found only when the manifest item put media-type before href,
     * and losing a parent navPoint to its children), else one per spine item.
     */
    private fun legacyChapterPaths(pkg: PackageDoc, files: BookFiles): List<String?> {
        val spineHrefs = pkg.opf.spine.mapNotNull { id -> pkg.opf.manifest[id]?.href }
        val ncxHref = LegacyNcxPattern.find(pkg.rawOpf)?.groupValues?.get(1)
        val hrefs = ncxHref
            ?.let { normalizePath(pkg.opfDir, it) }
            ?.let(files::text)
            ?.let(::legacyNcxHrefs)
            ?.takeIf { it.isNotEmpty() }
            ?: spineHrefs
        return hrefs.map { href -> normalizePath(pkg.opfDir, href.substringBefore('#')) }
    }

    private fun legacyNcxHrefs(ncxContent: String): List<String> {
        val hrefs = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true }
            val parser = factory.newPullParser(); parser.setInput(StringReader(ncxContent))
            var inNavPoint = false
            var currentHref = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                        "navpoint" -> { inNavPoint = true; currentHref = "" }
                        "content" -> if (inNavPoint)
                            currentHref = parser.getAttributeValue(null, "src")?.substringBefore("#") ?: ""
                    }
                    XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "navpoint" && inNavPoint) {
                        if (currentHref.isNotBlank()) hrefs.add(currentHref)
                        inNavPoint = false
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return hrefs
    }

    // ── Files ────────────────────────────────────────────────────────────────

    /** The book's files, by path inside the book: in the zip, or unpacked. */
    private interface BookFiles {
        fun bytes(path: String): ByteArray?
        fun exists(path: String): Boolean
        fun text(path: String): String? = bytes(path)?.toString(Charsets.UTF_8)?.removePrefix(BOM)
        fun absolutePath(path: String): String?
    }

    private class ZipFiles(private val zip: ZipFile) : BookFiles {
        private val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { entry -> normalizePath("", entry.name)?.let { it to entry } }
            .toMap()

        override fun bytes(path: String) = entries[path]?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        override fun exists(path: String) = path in entries
        override fun absolutePath(path: String): String? = null
    }

    private class DirFiles(private val root: File) : BookFiles {
        private fun file(path: String): File? = File(root, path).takeIf { it.isFile && it.isInside(root) }
        override fun bytes(path: String) = file(path)?.readBytes()
        override fun exists(path: String) = file(path) != null
        override fun absolutePath(path: String) = file(path)?.absolutePath
    }

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

    fun clearCache(filePath: String) {
        extractDirFor(File(filePath)).deleteRecursively()
    }

    private companion object {
        const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
        val LegacyNcxPattern = Regex("""media-type="application/x-dtbncx\+xml"[^>]*href="([^"]+)"""")
        val Whitespace = Regex("""\s+""")
    }
}

/**
 * XmlPullParser chokes on a byte-order mark ("Unexpected token @1:2") and the whole
 * book then fails to open. Plenty of EPUBs in the wild ship a BOM in container.xml,
 * the OPF or the NCX, so strip it before parsing.
 */
private val BOM = Char(0xFEFF).toString()

private fun File.readTextWithoutBom(): String = readText().removePrefix(BOM)

/** Whether this path, once ".." and links are resolved, lies inside [root] (canonical). */
private fun File.isInside(root: File): Boolean = canonicalFile.toPath().startsWith(root.toPath())

/** A pull parser that lets HTML entities such as &nbsp; through instead of failing. */
private fun relaxedParser(content: String): XmlPullParser {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    parser.setInput(StringReader(content))
    return parser
}

private fun XmlPullParser.attribute(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

/**
 * All the text inside the element the parser is on, child elements included, leaving the
 * parser on its end tag. nextText() throws on a child element, and a <dc:description>
 * holding XHTML would then abort the whole package.
 */
private fun XmlPullParser.elementText(): String {
    val out = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT -> out.append(text)
            XmlPullParser.END_DOCUMENT -> return out.toString()
        }
    }
    return out.toString()
}

/** A blurb as plain text: publishers often put escaped HTML in it. */
internal fun plainDescription(raw: String): String? {
    val text = visibleText(raw).lines().joinToString("\n") { it.trim() }.trim()
    return text.takeIf { it.isNotEmpty() }
}

/**
 * [href] (relative to [baseDir], both inside the book) as a clean path: percent-escapes
 * decoded, "." and ".." resolved. Null when it climbs out of the book.
 */
internal fun normalizePath(baseDir: String, href: String): String? {
    val decoded = decodeHref(href.substringBefore('#').substringBefore('?')).replace('\\', '/')
    val start = if (decoded.startsWith("/")) emptyList() else baseDir.split('/').filter { it.isNotEmpty() }
    val parts = ArrayList(start)
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/").takeIf { it.isNotEmpty() }
}

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
