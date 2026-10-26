package com.ebookreader.data.epub

import com.ebookreader.domain.model.TocEntry
import com.ebookreader.domain.model.flatten
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `reads metadata, chapters and contents`() {
        val result = parser.parseAndExtract(standardBook().path)

        assertEquals("The Test Book", result.book.title)
        assertEquals("A. Writer", result.book.author)
        assertEquals("en-GB", result.book.language)
        // Chapters are the files of the reading order, named by the contents.
        assertEquals(listOf("Opening", "Second Part"), result.book.chapters.map { it.title })
        assertEquals(listOf(0, 1), result.book.chapters.map { it.index })
        assertEquals("OEBPS/Text/two.xhtml", result.book.chapters[1].href)
        // The contents keep the place inside a file an entry points to.
        assertEquals(listOf(TocEntry("Opening", 0), TocEntry("Second Part", 1, "start")), result.book.toc)

        assertEquals("OEBPS/Text/one.xhtml", result.chapterPaths[0])
        val html = result.chapterHtml(0)!!
        assertTrue(html.contains("a".repeat(300)))
        // Ready for an HTML parser: the XHTML anchor is closed explicitly.
        assertTrue(html.contains("""<a id="top"></a>"""))
    }

    @Test
    fun `chapters are weighted by the text they hold`() {
        val result = parser.parseAndExtract(standardBook().path)
        // Only visible text counts, not markup or the head.
        assertEquals(listOf(300, 100), result.measureChapters())
    }

    @Test
    fun `a byte order mark in container, package or ncx does not stop the book opening`() {
        val result = parser.parseAndExtract(standardBook(bom = Char(0xFEFF).toString()).path)
        assertEquals("The Test Book", result.book.title)
        assertEquals(2, result.book.chapters.size)
        assertEquals("Opening", result.book.chapters[0].title)
    }

    @Test
    fun `without contents the reading order is numbered`() {
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
        val result = parser.parseAndExtract(book.path)
        assertEquals(listOf("b", "a"), result.book.chapters.map { it.id })
        assertEquals(listOf("Chapter 1", "Chapter 2"), result.book.chapters.map { it.title })
        assertTrue(result.book.toc.isEmpty())
    }

    @Test
    fun `an ncx listed with href before media-type is found`() {
        // How Project Gutenberg writes its manifest: the old pattern needed media-type first.
        val book = epub(
            "gutenberg.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item href="toc.ncx" id="ncx" media-type="application/x-dtbncx+xml"/>
                        <item href="one.xhtml" id="one" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="one"/>""",
                ),
                "OEBPS/toc.ncx" to ncx("Letter 1" to "one.xhtml"),
                "OEBPS/one.xhtml" to chapter("<p>Dear sister</p>"),
            )
        )
        assertEquals("Letter 1", parser.parseAndExtract(book.path).book.chapters.single().title)
    }

    @Test
    fun `nested ncx points keep their parents`() {
        val nested = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="p1"><navLabel><text>Part One</text></navLabel><content src="one.xhtml"/>
              <navPoint id="c1"><navLabel><text>Chapter 1</text></navLabel><content src="one.xhtml#c1"/></navPoint>
              <navPoint id="c2"><navLabel><text>Chapter 2</text></navLabel><content src="two.xhtml"/></navPoint>
            </navPoint>
            <navPoint id="p2"><navLabel><text>Part Two</text></navLabel><content src="three.xhtml"/></navPoint>
            </navMap></ncx>"""
        val book = epub(
            "nested.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                        <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                        <item id="three" href="three.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="one"/><itemref idref="two"/><itemref idref="three"/>""",
                ),
                "OEBPS/toc.ncx" to nested,
                "OEBPS/one.xhtml" to chapter("<p>1</p>"),
                "OEBPS/two.xhtml" to chapter("<p>2</p>"),
                "OEBPS/three.xhtml" to chapter("<p>3</p>"),
            )
        )
        val toc = parser.parseAndExtract(book.path).book.toc
        assertEquals(listOf("Part One", "Part Two"), toc.map { it.title })
        assertEquals(listOf("Chapter 1", "Chapter 2"), toc[0].children.map { it.title })
        assertEquals(TocEntry("Chapter 1", 0, "c1"), toc[0].children[0])
        assertEquals(listOf(0, 0, 1, 2), toc.flatten().map { it.first.chapterIndex })
        assertEquals(listOf(0, 1, 1, 0), toc.flatten().map { it.second })
    }

    @Test
    fun `an epub 3 navigation document is preferred, nested, with headings that have no link`() {
        val nav = """<?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
            <nav epub:type="landmarks"><ol><li><a href="text/one.xhtml">Start&nbsp;here</a></li></ol></nav>
            <nav epub:type="toc"><h1>Contents</h1><ol>
              <li><span>Part <em>One</em></span><ol>
                <li><a href="text/one.xhtml">The <b>First</b> Chapter</a></li>
                <li><a href="text/two.xhtml#mid">Halfway</a></li>
              </ol></li>
              <li><a href="text/three.xhtml">Afterword</a></li>
            </ol></nav></body></html>"""
        val book = epub(
            "nav.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/>
                        <item id="two" href="text/two.xhtml" media-type="application/xhtml+xml"/>
                        <item id="three" href="text/three.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="one"/><itemref idref="two"/><itemref idref="three"/>""",
                ),
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/toc.ncx" to ncx("From the NCX" to "text/one.xhtml"),
                "OEBPS/text/one.xhtml" to chapter("<p>1</p>"),
                "OEBPS/text/two.xhtml" to chapter("<p>2</p>"),
                "OEBPS/text/three.xhtml" to chapter("<p>3</p>"),
            )
        )
        val result = parser.parseAndExtract(book.path)
        val toc = result.book.toc
        assertEquals(listOf("Part One", "Afterword"), toc.map { it.title })
        // A heading without a link opens where its first entry does.
        assertEquals(0, toc[0].chapterIndex)
        assertEquals(listOf(TocEntry("The First Chapter", 0), TocEntry("Halfway", 1, "mid")), toc[0].children)
        // A chapter named only by an entry inside it takes that name.
        assertEquals(listOf("The First Chapter", "Halfway", "Afterword"), result.book.chapters.map { it.title })
    }

    @Test
    fun `a file the contents leave out carries the name before it, or the book's`() {
        val book = epub(
            "cover.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="ncx" media-type="application/x-dtbncx+xml" href="toc.ncx"/>
                        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                        <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                        <item id="plate" href="plate.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="cover"/><itemref idref="one"/><itemref idref="plate"/>""",
                ),
                "OEBPS/toc.ncx" to ncx("One" to "one.xhtml"),
                "OEBPS/cover.xhtml" to chapter("<p>cover</p>"),
                "OEBPS/one.xhtml" to chapter("<p>1</p>"),
                "OEBPS/plate.xhtml" to chapter("<p>plate</p>"),
            )
        )
        assertEquals(listOf("The Test Book", "One", "One"), parser.parseAndExtract(book.path).book.chapters.map { it.title })
    }

    @Test
    fun `old chapter numbers map to the files they showed`() {
        // The legacy layout numbered NCX entries (two pointing into one file, and no cover);
        // the spine is cover, one, two.
        val book = epub(
            "legacy.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    manifest = """
                        <item id="ncx" media-type="application/x-dtbncx+xml" href="toc.ncx"/>
                        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                        <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                        <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="cover"/><itemref idref="one"/><itemref idref="two"/>""",
                ),
                "OEBPS/toc.ncx" to ncx("A" to "one.xhtml", "B" to "one.xhtml#b", "C" to "two.xhtml"),
                "OEBPS/cover.xhtml" to chapter("<p>cover</p>"),
                "OEBPS/one.xhtml" to chapter("<p>1</p>"),
                "OEBPS/two.xhtml" to chapter("<p>2</p>"),
            )
        )
        assertArrayEquals(intArrayOf(1, 1, 2), parser.legacyChapterMap(book.path))
    }

    @Test
    fun `without an ncx the old numbering was the spine`() {
        val book = epub(
            "legacy-spine.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    // href before media-type: the old pattern did not find this NCX.
                    manifest = """
                        <item href="toc.ncx" id="ncx" media-type="application/x-dtbncx+xml"/>
                        <item href="a.xhtml" id="a" media-type="application/xhtml+xml"/>
                        <item href="b.xhtml" id="b" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="a"/><itemref idref="b"/>""",
                ),
                "OEBPS/toc.ncx" to ncx("Only B" to "b.xhtml"),
                "OEBPS/a.xhtml" to chapter("<p>A</p>"),
                "OEBPS/b.xhtml" to chapter("<p>B</p>"),
            )
        )
        assertArrayEquals(intArrayOf(0, 1), parser.legacyChapterMap(book.path))
    }

    @Test
    fun `metadata is read from the zip without unpacking`() {
        val book = epub(
            "meta.epub",
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(
                    metadata = defaultMetadata +
                        "<dc:description>&lt;p&gt;A &lt;i&gt;fine&lt;/i&gt; tale.&lt;/p&gt;</dc:description>" +
                        """<meta name="cover" content="img"/>""",
                    manifest = """
                        <item id="img" href="images/cover.jpg" media-type="image/jpeg"/>
                        <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    """,
                    spine = """<itemref idref="one"/>""",
                ),
                "OEBPS/images/cover.jpg" to "JPEGDATA",
                "OEBPS/one.xhtml" to chapter("<p>1</p>"),
            )
        )
        val meta = parser.readMetadata(book.path)!!
        assertEquals("The Test Book", meta.title)
        assertEquals("A fine tale.", meta.description)
        assertEquals("JPEGDATA", meta.coverImage?.decodeToString())
        assertFalse("nothing unpacked", cache.listFiles()!!.any())
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
        assertEquals("OEBPS/Text/chapter 1.xhtml", result.chapterPaths.single())
        assertTrue(result.chapterHtml(0)!!.contains("Spaced"))
        assertEquals(0, result.chapterOfPath("OEBPS/Text/chapter 1.xhtml"))
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
        assertNull(result.chapterPaths.single())
        assertNull(result.chapterHtml(0))
        assertEquals(listOf(0), result.measureChapters())
    }

    @Test
    fun `contents entries sharing one file are one chapter`() {
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
        val result = parser.parseAndExtract(book.path)
        assertEquals(1, result.book.chapters.size)
        assertEquals(listOf(TocEntry("One", 0, "one"), TocEntry("Two", 0, "two")), result.book.toc)
        assertEquals(listOf(200), result.measureChapters())
    }

    @Test
    fun `paths resolve inside the book and never out of it`() {
        assertEquals("OEBPS/Text/a.xhtml", normalizePath("OEBPS", "Text/a.xhtml"))
        assertEquals("OEBPS/a b.xhtml", normalizePath("OEBPS/Text", "../a%20b.xhtml#frag"))
        assertEquals("a.xhtml", normalizePath("OEBPS", "/a.xhtml"))
        assertNull(normalizePath("OEBPS", "../../etc/passwd"))
        assertNotNull(normalizePath("", "./x.xhtml"))
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
