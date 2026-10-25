package com.ebookreader.data.convert

import com.github.junrar.Archive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What ComicInfo.xml, the de facto comic metadata, says. */
data class ComicMetadata(
    val title: String? = null,
    val writer: String? = null,
    val series: String? = null,
    val number: Float? = null,
)

/**
 * Comic book archives: a zip (CBZ) or RAR (CBR) of page images. Pages are read in
 * natural order, so "page 10" comes after "page 9" and not after "page 1".
 */
object ComicArchives {

    private val ImageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun isPage(name: String): Boolean {
        val lower = name.lowercase()
        // macOS archives carry "__MACOSX/._page.jpg" ghosts; they are not pages.
        if (lower.startsWith("__macosx/") || lower.substringAfterLast('/').startsWith(".")) return false
        return lower.substringAfterLast('.', "") in ImageExtensions
    }

    /** The page entries of a CBZ, in reading order. */
    fun pages(zip: ZipFile): List<ZipEntry> =
        zip.entries().asSequence().filter { !it.isDirectory && isPage(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .toList()

    fun metadata(zip: ZipFile): ComicMetadata {
        val entry = zip.entries().asSequence().firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?: return ComicMetadata()
        return runCatching { parseComicInfo(zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)) }
            .getOrDefault(ComicMetadata())
    }

    internal fun parseComicInfo(xml: String): ComicMetadata {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml.removePrefix("\uFEFF")))
        val values = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == 2) {
                val name = parser.name
                runCatching { parser.nextText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { values[name] = it }
            }
            event = parser.next()
        }
        return ComicMetadata(
            title = values["Title"],
            writer = values["Writer"],
            series = values["Series"],
            number = values["Number"]?.toFloatOrNull(),
        )
    }

    /** Rewrites a RAR comic as a zip, pages stored as they are: they are compressed already. */
    fun cbrToCbz(source: File, output: File) {
        Archive(source).use { archive ->
            if (archive.isEncrypted) throw IllegalArgumentException("This comic is password-protected")
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                var pages = 0
                for (header in archive.fileHeaders) {
                    if (header.isDirectory) continue
                    val name = header.fileName.replace('\\', '/')
                    if (!isPage(name) && !name.endsWith("ComicInfo.xml", true)) continue
                    val bytes = java.io.ByteArrayOutputStream().also { archive.extractFile(header, it) }.toByteArray()
                    zip.putStored(name, bytes)
                    if (isPage(name)) pages++
                }
                if (pages == 0) throw IllegalArgumentException("There are no pages in this comic")
            }
        }
    }

    /** "2" before "10"; otherwise case-insensitive. */
    val NaturalOrder: Comparator<String> = Comparator { a, b ->
        val chunksA = Chunk.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = Chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val x = chunksA[i]
            val y = chunksB[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (cmp != 0) return@Comparator cmp
        }
        chunksA.size - chunksB.size
    }

    private val Chunk = Regex("""\d+|\D+""")
}
