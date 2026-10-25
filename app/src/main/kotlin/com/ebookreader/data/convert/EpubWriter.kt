package com.ebookreader.data.convert

import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A book made from another format, ready to be written out as an EPUB. */
data class GeneratedBook(
    val title: String,
    val author: String,
    val language: String?,
    val chapters: List<GeneratedChapter>,
    val resources: List<Resource> = emptyList(),
    /** Path of the cover among [resources], if there is one. */
    val coverPath: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
)

/** One chapter: its title for the contents, and the markup that goes inside its body. */
data class GeneratedChapter(val title: String, val body: String)

/** A file inside the book: an image, at a path relative to the OPF ("images/1.jpg"). */
class Resource(val path: String, val mediaType: String, val bytes: ByteArray)

/**
 * Writes a [GeneratedBook] as a plain EPUB 2: what the reader opens best, and what every
 * other reader opens too, should the file ever leave the app. Chapters are written as
 * XHTML documents around the given body markup; the contents list comes from their
 * titles, so a converted book opens with a real table of contents.
 */
object EpubWriter {

    fun write(book: GeneratedBook, output: File) {
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            // The mimetype entry must come first and be stored, not deflated.
            zip.putStored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.putText("META-INF/container.xml", CONTAINER)
            zip.putText("OEBPS/content.opf", opf(book))
            zip.putText("OEBPS/toc.ncx", ncx(book))
            zip.putText("OEBPS/style.css", STYLE)
            book.chapters.forEachIndexed { i, chapter ->
                zip.putText("OEBPS/${chapterPath(i)}", chapterDocument(chapter, book.language))
            }
            book.resources.forEach { r ->
                zip.putNextEntry(ZipEntry("OEBPS/${r.path}"))
                zip.write(r.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun chapterPath(i: Int) = "text/chapter%03d.xhtml".format(i + 1)

    private fun opf(book: GeneratedBook): String = buildString {
        val id = "urn:uuid:" + UUID.nameUUIDFromBytes((book.title + "\u0000" + book.author).toByteArray())
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">""").append('\n')
        append("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">""").append('\n')
        append("<dc:title>${xml(book.title)}</dc:title>\n")
        append("<dc:creator opf:role=\"aut\">${xml(book.author)}</dc:creator>\n")
        append("<dc:language>${xml(book.language ?: "und")}</dc:language>\n")
        append("<dc:identifier id=\"bookid\">$id</dc:identifier>\n")
        book.coverPath?.let { append("<meta name=\"cover\" content=\"cover-image\"/>\n") }
        book.series?.let { append("<meta name=\"calibre:series\" content=\"${xml(it)}\"/>\n") }
        book.seriesIndex?.let { append("<meta name=\"calibre:series_index\" content=\"$it\"/>\n") }
        append("</metadata>\n<manifest>\n")
        // media-type ahead of href: the order the reader's contents lookup expects.
        append("""<item id="ncx" media-type="application/x-dtbncx+xml" href="toc.ncx"/>""").append('\n')
        append("""<item id="css" media-type="text/css" href="style.css"/>""").append('\n')
        book.chapters.indices.forEach { i ->
            append("""<item id="c${i + 1}" media-type="application/xhtml+xml" href="${chapterPath(i)}"/>""").append('\n')
        }
        book.resources.forEachIndexed { i, r ->
            val itemId = if (r.path == book.coverPath) "cover-image" else "r${i + 1}"
            append("""<item id="$itemId" media-type="${r.mediaType}" href="${xml(r.path)}"/>""").append('\n')
        }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        book.chapters.indices.forEach { i -> append("""<itemref idref="c${i + 1}"/>""").append('\n') }
        append("</spine>\n</package>\n")
    }

    private fun ncx(book: GeneratedBook): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""").append('\n')
        append("<head><meta name=\"dtb:depth\" content=\"1\"/></head>\n")
        append("<docTitle><text>${xml(book.title)}</text></docTitle>\n<navMap>\n")
        book.chapters.forEachIndexed { i, c ->
            append("<navPoint id=\"n${i + 1}\" playOrder=\"${i + 1}\">")
            append("<navLabel><text>${xml(c.title)}</text></navLabel>")
            append("<content src=\"${chapterPath(i)}\"/></navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    private fun chapterDocument(chapter: GeneratedChapter, language: String?): String {
        val lang = language?.let { " xml:lang=\"${xml(it)}\" lang=\"${xml(it)}\"" }.orEmpty()
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"$lang>
<head><title>${xml(chapter.title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body>
${chapter.body}
</body>
</html>
"""
    }

    /** Text escaped for XML content and attributes. */
    fun xml(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') Unit else append(c)
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

    private const val STYLE = """p { margin: 0; text-indent: 1.4em; }
p.first, h1 + p, h2 + p, h3 + p, hr + p, blockquote + p { text-indent: 0; }
h1, h2, h3 { text-align: center; margin: 1.5em 0 1em; }
blockquote { margin: 1em 1.5em; }
pre { white-space: pre-wrap; font-size: 0.9em; }
.verse { margin: 1em 2em; }
.verse p { text-indent: 0; }
img { max-width: 100%; }
"""
}

/** Guesses an image type from its first bytes; books do not always say. */
internal fun imageType(bytes: ByteArray): Pair<String, String>? {
    fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
    return when {
        at(0) == 0xFF && at(1) == 0xD8 -> "jpg" to "image/jpeg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png" to "image/png"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif" to "image/gif"
        at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 && at(9) == 0x45 -> "webp" to "image/webp"
        at(0) == 0x42 && at(1) == 0x4D -> "bmp" to "image/bmp"
        else -> null
    }
}

/** An entry written as it is, uncompressed: EPUB's mimetype, or images already compressed. */
internal fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}
