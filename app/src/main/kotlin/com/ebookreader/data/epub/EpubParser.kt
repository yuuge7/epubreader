package com.ebookreader.data.epub

import android.content.Context
import com.ebookreader.domain.model.EpubBook
import com.ebookreader.domain.model.EpubChapter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubParser @Inject constructor(private val context: Context) {

    data class EpubParseResult(
        val book: EpubBook,
        val extractedDir: File,
        val chapterContents: Map<String, String>,  // chapterId -> styled html
        val chapterBaseUrls: Map<String, String>,  // chapterId -> base url for WebView
        val opfDirPath: String
    )

    fun parseAndExtract(filePath: String): EpubParseResult {
        val epubFile = File(filePath)
        val extractDir = File(context.cacheDir, "epub_${epubFile.nameWithoutExtension}")

        if (!extractDir.exists() || extractDir.listFiles()?.isEmpty() == true) {
            extractDir.mkdirs()
            ZipFile(epubFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val out = File(extractDir, entry.name)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
        }

        val containerFile = File(extractDir, "META-INF/container.xml")
        val opfPath = parseContainerXml(containerFile.readText())
        val opfFile = File(extractDir, opfPath)
        val opfDir = opfFile.parentFile ?: extractDir
        val opfContent = opfFile.readText()

        val (title, author, spineHrefs, coverHref) = parseOpf(opfContent)
        val ncxHref = findNcxHref(opfContent)
        val chapters = if (ncxHref != null) {
            val ncxFile = File(opfDir, ncxHref)
            if (ncxFile.exists()) parseNcx(ncxFile.readText(), spineHrefs)
            else buildChaptersFromSpine(spineHrefs)
        } else buildChaptersFromSpine(spineHrefs)

        val chapterContents = mutableMapOf<String, String>()
        val chapterBaseUrls = mutableMapOf<String, String>()

        chapters.forEach { chapter ->
            val chapterFile = File(opfDir, chapter.href.substringBefore("#"))
            if (chapterFile.exists()) {
                val chapterDir = chapterFile.parentFile ?: opfDir
                val rawHtml = chapterFile.readText()
                chapterContents[chapter.id] = injectReaderStyles(rawHtml, chapterDir.absolutePath)
                chapterBaseUrls[chapter.id] = "file://${chapterDir.absolutePath}/"
            }
        }

        val book = EpubBook(
            title = title, author = author, chapters = chapters,
            coverImagePath = coverHref?.let { File(opfDir, it).absolutePath }
        )
        return EpubParseResult(book, extractDir, chapterContents, chapterBaseUrls, opfDir.absolutePath)
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
        val spineHrefs: List<Pair<String, String>>, val coverImageHref: String?
    )

    private fun parseOpf(content: String): OpfData {
        var title = "Unknown Title"; var author = "Unknown Author"
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
                        "dc:title" -> if (inMeta) parser.nextText().trim().takeIf { it.isNotBlank() }?.let { title = it }
                        "dc:creator" -> if (inMeta) parser.nextText().trim().takeIf { it.isNotBlank() }?.let { author = it }
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
        return OpfData(title, author, spineHrefs, coverImageHref)
    }

    private fun findNcxHref(opfContent: String): String? {
        val ncx = Regex("""media-type="application/x-dtbncx\+xml"[^>]*href="([^"]+)"""")
        val nav = Regex("""properties="nav"[^>]*href="([^"]+)"""")
        return ncx.find(opfContent)?.groupValues?.get(1)
            ?: nav.find(opfContent)?.groupValues?.get(1)
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

    private fun injectReaderStyles(html: String, chapterDirPath: String): String {
        val style = """
            <style id="epub-reader-base">
                body { font-family: Georgia, serif; line-height: 1.7; padding: 16px; margin: 0; word-wrap: break-word; }
                img { max-width: 100%; height: auto; }
                p { margin: 0 0 1em 0; }
                h1,h2,h3,h4,h5,h6 { line-height: 1.3; }
                a { color: #6650A4; text-decoration: underline; }
            </style>
        """.trimIndent()
        return if (html.contains("<head>", ignoreCase = true))
            html.replace(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$style")
        else "<html><head>$style</head><body>$html</body></html>"
    }

    fun clearCache(filePath: String) {
        File(context.cacheDir, "epub_${File(filePath).nameWithoutExtension}").deleteRecursively()
    }
}
