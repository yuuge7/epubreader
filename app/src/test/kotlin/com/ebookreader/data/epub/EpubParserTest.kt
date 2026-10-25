package com.ebookreader.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs the parser over real EPUB files built on the fly, on the same kXML pull parser
 * Android ships, so what is covered here is what the app does with a book.
 */
class EpubParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cache by lazy { temp.newFolder("cache") }
    private val parser by lazy { EpubParser(cache) }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>""".trimIndent()

    private fun opf(manifest: String, spine: String, metadata: String = defaultMetadata) = """<?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
          <manifest>$manifest</manifest>
          <spine toc="ncx">$spine</spine>
        </package>""".trimIndent()

    private val defaultMetadata =
        "<dc:title>The Test Book</dc:title><dc:creator>A. Writer</dc:creator><dc:language>en-GB</dc:language>"

    private fun ncx(vararg points: Pair<String, String>) = """<?xml version="1.0" encoding="UTF-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><docTitle><text>Ignored</text></docTitle><navMap>
        ${points.mapIndexed { i, (title, src) ->
            """<navPoint id="np$i" playOrder="${i + 1}"><navLabel><text>$title</text></navLabel><content src="$src"/></navPoint>"""
        }.joinToString("\n")}
        </navMap></ncx>""".trimIndent()

    private fun chapter(body: String) =
        """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""

    private fun epub(name: String, files: Map<String, String>): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun standardBook(name: String = "book.epub", bom: String = "") = epub(
        name,
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to bom + container,
            "OEBPS/content.opf" to bom + opf(
                manifest = """
                    <item id="ncx" media-type="application/x-dtbncx+xml" href="toc.ncx"/>
                    <item id="c1" href="Text/one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/two.xhtml" media-type="application/xhtml+xml"/>
                """,
                spine = """<itemref idref="c1"/><itemref idref="c2"/>""",
            ),
            "OEBPS/toc.ncx" to bom + ncx("Opening" to "Text/one.xhtml", "Second Part" to "Text/two.xhtml#start"),
            "OEBPS/Text/one.xhtml" to chapter("""<a id="top"/><p>""" + "a".repeat(300) + "</p>"),
            "OEBPS/Text/two.xhtml" to chapter("<p>" + "b".repeat(100) + "</p>"),
        )
    )

    @Test
    fun `reads metadata, contents and chapter files`() {
        val result = parser.parseAndExtract(standardBook().path)

        assertEquals("The Test Book", result.book.title)
        assertEquals("A. Writer", result.book.author)
        assertEquals("en-GB", result.book.language)
        assertEquals(listOf("Opening", "Second Part"), result.book.chapters.map { it.title })
        assertEquals(listOf(0, 1), result.book.chapters.map { it.index })
        // Fragments are dropped from chapter hrefs: a chapter is a whole file.
        assertEquals("Text/two.xhtml", result.book.chapters[1].href)

        val first = result.book.chapters[0]
        assertEquals("OEBPS/Text/one.xhtml", result.chapterPaths[first.id])
        assertTrue(result.chapterContents.getValue(first.id).contains("a".repeat(300)))
        // Ready for an HTML parser: the XHTML anchor is closed explicitly.
        assertTrue(result.chapterContents.getValue(first.id).contains("""<a id="top"></a>"""))
    }

    @Test
    fun `chapters are weighted by the text they hold`() {
        val result = parser.parseAndExtract(standardBook().path)
        // Only visible text counts, not markup or the head.
        assertEquals(listOf(300, 100), result.chapterWeights)
    }

    @Test
    fun `a byte order mark in container, package or ncx does not stop the book opening`() {
        val result = parser.parseAndExtract(standardBook(bom = "\uFEFF").path)
        assertEquals("The Test Book", result.book.title)
        assertEquals(2, result.book.chapters.size)
        assertEquals("Opening", result.book.chapters[0].title)
    }

    @Test
    fun `without an ncx the reading order is the contents`() {
        val book = epub(
            "spine.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                        <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="b"/><itemref idref="a"/>""",
                ),
                "OEBPS/a.xhtml" to chapter("<p>A</p>"),
                "OEBPS/b.xhtml" to chapter("<p>B</p>"),
            )
        )
        val chapters = parser.parseAndExtract(book.path).book.chapters
        assertEquals(listOf("b", "a"), chapters.map { it.id })
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
    }

    @Test
    fun `a broken package document falls back rather than throwing`() {
        val book = epub(
            "broken.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to "<package><metadata><dc:title>Never closed",
            )
        )
        val result = parser.parseAndExtract(book.path)
        assertEquals("Unknown Title", result.book.title)
        assertEquals("Unknown Author", result.book.author)
        assertTrue(result.book.chapters.isEmpty())
    }

    @Test
    fun `percent-encoded hrefs find files with spaces in their names`() {
        val book = epub(
            "spaces.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """<item id="c" href="Text/chapter%201.xhtml" media-type="application/xhtml+xml"/>""",
                    spine = """<itemref idref="c"/>""",
                ),
                "OEBPS/Text/chapter 1.xhtml" to chapter("<p>Spaced</p>"),
            )
        )
        val result = parser.parseAndExtract(book.path)
        val id = result.book.chapters.single().id
        assertEquals("OEBPS/Text/chapter 1.xhtml", result.chapterPaths[id])
        assertTrue(result.chapterContents.getValue(id).contains("Spaced"))
    }

    @Test
    fun `a zip entry climbing out of the book is not extracted`() {
        val book = epub(
            "slip.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(manifest = "", spine = ""),
                "../../escaped.txt" to "gotcha",
            )
        )
        parser.parseAndExtract(book.path)
        val escaped = temp.root.walkTopDown().filter { it.name == "escaped.txt" }.toList()
        assertTrue("Zip Slip entry was written: $escaped", escaped.isEmpty())
    }

    @Test
    fun `a chapter href pointing outside the book is never read`() {
        val outside = File(temp.root, "secret.xhtml").apply { writeText(chapter("<p>private</p>")) }
        val book = epub(
            "escape.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """<item id="x" href="../../../${outside.name}" media-type="application/xhtml+xml"/>""",
                    spine = """<itemref idref="x"/>""",
                ),
            )
        )
        val result = parser.parseAndExtract(book.path)
        val id = result.book.chapters.single().id
        assertNull(result.chapterContents[id])
        assertNull(result.chapterPaths[id])
        assertEquals(listOf(0), result.chapterWeights)
    }

    @Test
    fun `contents entries sharing one file split its text between them`() {
        val book = epub(
            "shared.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="ncx" media-type="application/x-dtbncx+xml" href="toc.ncx"/>
                        <item id="all" href="all.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="all"/>""",
                ),
                "OEBPS/toc.ncx" to ncx("One" to "all.xhtml#one", "Two" to "all.xhtml#two"),
                "OEBPS/all.xhtml" to chapter("<p>" + "x".repeat(200) + "</p>"),
            )
        )
        assertEquals(listOf(100, 100), parser.parseAndExtract(book.path).chapterWeights)
    }

    @Test
    fun `same-named books in different folders get separate extractions`() {
        val a = standardBook("same.epub")
        val bDir = temp.newFolder("elsewhere")
        val b = a.copyTo(File(bDir, "same.epub"))
        parser.parseAndExtract(a.path)
        parser.parseAndExtract(b.path)
        assertEquals(2, cache.listFiles()!!.count { it.name.startsWith("epub_same_") })
    }

    @Test
    fun `clearing the cache removes the extraction`() {
        val book = standardBook()
        parser.parseAndExtract(book.path)
        assertFalse(cache.listFiles()!!.isEmpty())
        parser.clearCache(book.path)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
