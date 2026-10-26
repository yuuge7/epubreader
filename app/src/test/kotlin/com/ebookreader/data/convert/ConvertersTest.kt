package com.ebookreader.data.convert

import com.ebookreader.data.epub.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ConvertersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun base64() {
        Fb2Converter.decodeBase64 = { java.util.Base64.getMimeDecoder().decode(it) }
    }

    /** Writes [book] as an EPUB and opens it the way the reader does. */
    private fun roundTrip(book: GeneratedBook): EpubParser.EpubParseResult {
        val file = File(temp.root, "out.epub")
        EpubWriter.write(book, file)
        return EpubParser(temp.newFolder()).parseAndExtract(file.path)
    }

    // ── EPUB writer ──────────────────────────────────────────────────────────

    @Test
    fun `a written book opens with its contents, metadata and series`() {
        val parsed = roundTrip(
            GeneratedBook(
                title = "Tea & Sympathy", author = "A <Writer>", language = "en",
                chapters = listOf(GeneratedChapter("One", "<p>First.</p>"), GeneratedChapter("Two", "<p>Second.</p>")),
                series = "Kettles", seriesIndex = 2f,
            )
        )
        assertEquals("Tea & Sympathy", parsed.book.title)
        assertEquals("A <Writer>", parsed.book.author)
        assertEquals(listOf("One", "Two"), parsed.book.chapters.map { it.title })
        assertEquals("Kettles", parsed.book.series)
        assertEquals(2f, parsed.book.seriesIndex)
        assertTrue(parsed.chapterHtml(0)!!.contains("<p>First.</p>"))
    }

    // ── Plain text ───────────────────────────────────────────────────────────

    @Test
    fun `plain text is cut at its chapter headings, with Gutenberg metadata`() {
        val wrapped = "This line of prose was hard-wrapped by an editor at about seventy\ncolumns so it continues here."
        val text = """
            Title: The Test
            Author: Jane Writer
            Language: English

            CHAPTER I

            $wrapped

            CHAPTER II

            Second chapter text.

            CHAPTER III

            Third.
        """.trimIndent()
        val book = TxtConverter.convert(text.toByteArray(), "fallback")
        assertEquals("The Test", book.title)
        assertEquals("Jane Writer", book.author)
        assertEquals("en", book.language)
        assertEquals(listOf("Opening", "CHAPTER I", "CHAPTER II", "CHAPTER III"), book.chapters.map { it.title })
        // Wrapped lines are joined back into one paragraph.
        assertTrue(book.chapters[1].body.contains("seventy columns"))
    }

    @Test
    fun `text without headings is split into readable parts`() {
        val para = "word ".repeat(200).trim()
        val text = (1..60).joinToString("\n\n") { para }
        val book = TxtConverter.convert(text.toByteArray(), "Untitled")
        assertEquals("Untitled", book.title)
        assertTrue("got ${book.chapters.size}", book.chapters.size in 2..4)
    }

    @Test
    fun `windows-1252 text is read as such`() {
        val bytes = "Café crème\n\nMore.".toByteArray(charset("windows-1252"))
        assertTrue(decodeText(bytes).startsWith("Café crème"))
    }

    // ── Markdown ─────────────────────────────────────────────────────────────

    @Test
    fun `markdown chapters start at headings and inline marks are kept`() {
        val md = """
            ---
            title: "Notes"
            author: Sam
            ---
            # Part one

            Some *emphasis*, **strong** and `a < b` code, and [a link](https://example.org).

            - item one
            - item two

            # Part two

            > quoted
        """.trimIndent()
        val book = MarkdownConverter.convert(md.toByteArray(), "x")
        assertEquals("Notes", book.title)
        assertEquals("Sam", book.author)
        assertEquals(listOf("Part one", "Part two"), book.chapters.map { it.title })
        val body = book.chapters[0].body
        assertTrue(body, body.contains("<em>emphasis</em>"))
        assertTrue(body, body.contains("<strong>strong</strong>"))
        assertTrue(body, body.contains("<code>a &lt; b</code>"))
        assertTrue(body, body.contains("<a href=\"https://example.org\">a link</a>"))
        assertTrue(body, body.contains("<li>item two</li>"))
        assertTrue(book.chapters[1].body.contains("<blockquote>"))
    }

    // ── FB2 ──────────────────────────────────────────────────────────────────

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun fb2(): String = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
  <author><first-name>Lev</first-name><last-name>Tolstoy</last-name></author>
  <book-title>Hadji Murat</book-title><lang>ru</lang>
  <sequence name="Late works" number="3"/>
  <coverpage><image l:href="#cover.png"/></coverpage>
</title-info></description>
<body>
  <section><title><p>Chapter One</p></title>
    <epigraph><p>An epigraph</p><text-author>Someone</text-author></epigraph>
    <p>Text with <emphasis>stress</emphasis> and a note<a l:href="#n1" type="note">1</a>.</p>
    <poem><stanza><v>Line one</v><v>Line two</v></stanza></poem>
    <image l:href="#cover.png"/>
  </section>
  <section><title><p>Chapter Two</p></title><p>More.</p></section>
</body>
<body name="notes"><section id="n1"><title><p>1</p></title><p>The note.</p></section></body>
<binary id="cover.png" content-type="image/png">${java.util.Base64.getEncoder().encodeToString(png)}</binary>
</FictionBook>"""

    @Test
    fun `fb2 sections become chapters with notes, poems, images and series`() {
        val book = Fb2Converter.convert(fb2().toByteArray(), "x")
        assertEquals("Hadji Murat", book.title)
        assertEquals("Lev Tolstoy", book.author)
        assertEquals("ru", book.language)
        assertEquals("Late works", book.series)
        assertEquals(3f, book.seriesIndex)
        assertEquals(listOf("Chapter One", "Chapter Two", "Notes"), book.chapters.map { it.title })
        val first = book.chapters[0].body
        assertTrue(first, first.contains("<em>stress</em>"))
        assertTrue(first, first.contains("<div class=\"verse\">"))
        assertTrue(first, first.contains("<img src=\"../images/1.png\""))
        assertTrue(first, first.contains("<blockquote>"))
        assertEquals("images/1.png", book.coverPath)
        assertEquals(1, book.resources.size)
        // And it opens.
        assertEquals(3, roundTrip(book).book.chapters.size)
    }

    // ── Kindle ───────────────────────────────────────────────────────────────

    /** A minimal MOBI: PalmDB header, record 0 with MOBI and EXTH, then text records. */
    private fun mobi(texts: List<ByteArray>, compression: Int = 1, encryption: Int = 0, extraFlags: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val exthRecords = listOf(100 to "Mary Shelley", 503 to "The Last Man", 524 to "en")
            .map { (t, v) -> u32(t) + u32(8 + v.toByteArray().size) + v.toByteArray() }
        val exth = "EXTH".toByteArray() + u32(12 + exthRecords.sumOf { it.size }) + u32(exthRecords.size) +
            exthRecords.reduce { a, b -> a + b }
        val mobiHeader = ByteArray(0xE8).also { h ->
            "MOBI".toByteArray().copyInto(h, 0)
            u32(0xE8).copyInto(h, 0x14 - 16)          // header length
            u32(2).copyInto(h, 0x18 - 16)             // type: book
            u32(65001).copyInto(h, 0x1C - 16)         // UTF-8
            u32(6).copyInto(h, 0x24 - 16)             // KF7
            u32(-1).copyInto(h, 0x6C - 16)            // no images
            u32(0x40).copyInto(h, 0x80 - 16)          // EXTH present
            u16(extraFlags).copyInto(h, 0xF2 - 16)
        }
        val r0 = u16(compression) + u16(0) + u32(texts.sumOf { it.size }) + u16(texts.size) + u16(4096) +
            u16(encryption) + u16(0) + mobiHeader + exth
        val records = listOf(r0) + texts
        val headerSize = 78 + records.size * 8 + 2
        val out = ByteArrayOutputStream()
        out.write("The_Last_Man".toByteArray().copyOf(32))
        out.write(ByteArray(28))
        out.write("BOOKMOBI".toByteArray())
        out.write(ByteArray(8))
        out.write(u16(records.size))
        var offset = headerSize
        records.forEach { out.write(u32(offset)); out.write(ByteArray(4)); offset += it.size }
        out.write(ByteArray(2))
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun `a mobi is split at its page breaks and keeps its metadata`() {
        val chapterOne = "<html><head><guide></guide></head><body><h2>Volume One</h2><p>" + "Plague. ".repeat(40) + "</p>"
        val chapterTwo = "<mbp:pagebreak/><h2>Volume Two</h2><p>" + "Alone. ".repeat(40) + "</p></body></html>"
        val book = MobiConverter.convert(mobi(listOf(chapterOne.toByteArray(), chapterTwo.toByteArray())), "x")
        assertEquals("The Last Man", book.title)
        assertEquals("Mary Shelley", book.author)
        assertEquals("en", book.language)
        assertEquals(listOf("Volume One", "Volume Two"), book.chapters.map { it.title })
        assertTrue(book.chapters.none { it.body.contains("mbp:") || it.body.contains("<head") })
    }

    @Test
    fun `trailing record entries are not read as text`() {
        // Flags 0b11: one trailing entry (3 bytes, its size in the last byte, which carries
        // the 0x80 marker), before it a multibyte tail: one spilled byte and a count of 1.
        val text = "<p>" + "Words without junk. ".repeat(20) + "</p>"
        val record = text.toByteArray() + byteArrayOf(0x41, 0x01) + byteArrayOf(0x7F, 0x7F, 0x83.toByte())
        val book = MobiConverter.convert(mobi(listOf(record), extraFlags = 3), "x")
        val body = book.chapters.single().body
        assertTrue(body, body.endsWith("</p>"))
    }

    @Test
    fun `palmdoc back-references and space pairs decompress`() {
        // "abcabcabc" as "abc" + back-reference (distance 3, length 6); then 0xC1 = " A".
        val compressed = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            0x80.toByte(), ((3 shl 3) or (6 - 3)).toByte(), 0xC1.toByte())
        assertEquals("abcabcabc A", String(MobiConverter.palmDocDecompress(compressed)))
    }

    @Test(expected = MobiConverter.UnsupportedMobi::class)
    fun `a book with DRM is refused with a reason`() {
        MobiConverter.convert(mobi(listOf("<p>x</p>".toByteArray()), encryption = 2), "x")
    }

    // ── Comics ───────────────────────────────────────────────────────────────

    @Test
    fun `comic pages sort naturally and ghost files are skipped`() {
        val names = listOf("p10.jpg", "p2.jpg", "P1.png", "__MACOSX/._p1.png", "notes.txt", "p03.webp")
        assertEquals(listOf("P1.png", "p2.jpg", "p03.webp", "p10.jpg"),
            names.filter(ComicArchives::isPage).sortedWith(ComicArchives.NaturalOrder))
    }

    @Test
    fun `comicinfo gives title, writer and series number`() {
        val meta = ComicArchives.parseComicInfo(
            """<?xml version="1.0"?><ComicInfo><Series>Little Nemo</Series><Number>4</Number><Writer>Winsor McCay</Writer></ComicInfo>"""
        )
        assertEquals(ComicMetadata(title = null, writer = "Winsor McCay", series = "Little Nemo", number = 4f), meta)
    }

    // ── Real books, when their files are at hand ─────────────────────────────

    /**
     * Converts Project Gutenberg's own Kindle and text editions of The Time Machine when
     * EBOOK_SAMPLES names a folder holding tm.mobi, tm.azw3 and tm.txt. Skipped otherwise,
     * so CI needs no binary fixtures.
     */
    @Test
    fun `gutenberg kindle and text editions convert`() {
        val dir = System.getenv("EBOOK_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (name in listOf("tm.mobi", "tm.azw3")) {
            val book = MobiConverter.convert(File(dir, name).readBytes(), "fallback")
            assertEquals(name, "The Time Machine", book.title)
            assertEquals(name, "H. G. Wells", book.author)
            assertTrue("$name: ${book.chapters.size} chapters", book.chapters.size >= 10)
            assertNotNull("$name cover", book.coverPath)
            val text = book.chapters.joinToString(" ") { com.ebookreader.data.epub.visibleText(it.body) }
            assertTrue(name, text.contains("The Time Traveller (for so it will be convenient to speak of him)"))
            println("$name: " + book.chapters.take(8).map { it.title })
            roundTrip(book)
        }
        val txt = TxtConverter.convert(File(dir, "tm.txt").readBytes(), "fallback")
        assertEquals("The Time Machine", txt.title)
        assertTrue("txt chapters ${txt.chapters.size}", txt.chapters.size >= 10)
        println("tm.txt: " + txt.chapters.take(8).map { it.title })
    }
}
