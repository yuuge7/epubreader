package com.ebookreader.data.opds

import com.ebookreader.data.library.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsParserTest {

    private fun parse(xml: String, base: String = "https://www.gutenberg.org/ebooks.opds/") =
        OpdsParser.parse(xml.byteInputStream(), base)

    @Test
    fun `navigation feed with relative links, paging and search`() {
        val feed = parse(
            """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
<title>Project Gutenberg</title>
<author><name>Project Gutenberg</name></author>
<link rel="search" type="application/opensearchdescription+xml" href="https://www.gutenberg.org/catalog/osd-books.xml"/>
<link rel="next" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?sort_order=downloads&amp;start_index=26"/>
<entry>
  <id>https://www.gutenberg.org/ebooks/1342.opds</id>
  <title>Pride and Prejudice</title>
  <content type="text">Jane Austen</content>
  <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/1342.opds"/>
  <link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
</feed>"""
        )
        assertEquals("Project Gutenberg", feed.title)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&start_index=26", feed.next)
        assertEquals("https://www.gutenberg.org/catalog/osd-books.xml", feed.searchDescription)
        val entry = feed.entries.single()
        assertEquals("Pride and Prejudice", entry.title)
        assertEquals("Jane Austen", entry.summary)
        assertEquals(false, entry.isBook)
        assertEquals("https://www.gutenberg.org/ebooks/1342.opds", entry.navigation?.href)
        // Inline icons are not covers.
        assertNull(entry.cover)
    }

    @Test
    fun `book entry with xhtml content, acquisitions, covers and related pages`() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom">
<title>Frankenstein</title>
<entry>
  <title>Frankenstein; or, The modern Prometheus</title>
  <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>
Summary:
A Gothic novel.
</p><p>Language: English</p></div></content>
  <id>urn:gutenberg:84:2</id>
  <author><name>Shelley, Mary Wollstonecraft</name></author>
  <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3" length="440131" href="https://www.gutenberg.org/ebooks/84.epub3.images"/>
  <link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" href="/ebooks/84.kf8.images"/>
  <link type="application/epub+zip" rel="http://opds-spec.org/acquisition/buy" href="/buy"/>
  <link type="image/jpeg" rel="http://opds-spec.org/image" href="/cache/epub/84/pg84.cover.medium.jpg"/>
  <link type="image/jpeg" rel="http://opds-spec.org/image/thumbnail" href="/cache/epub/84/pg84.cover.small.jpg"/>
  <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/61.opds" title="By Shelley…"/>
</entry>
</feed>""",
            base = "https://www.gutenberg.org/ebooks/84.opds",
        )
        val entry = feed.entries.single()
        assertEquals("urn:gutenberg:84:2", entry.id)
        assertEquals("Mary Wollstonecraft Shelley", entry.author)
        assertEquals("Summary: A Gothic novel.\nLanguage: English", entry.summary)
        assertTrue(entry.isBook)
        assertEquals(
            listOf("https://www.gutenberg.org/ebooks/84.epub3.images", "https://www.gutenberg.org/ebooks/84.kf8.images"),
            entry.acquisitions.map { it.href },
        )
        assertEquals(440131L, entry.acquisitions[0].length)
        // The thumbnail, where both are offered: it is what a list shows.
        assertEquals("https://www.gutenberg.org/cache/epub/84/pg84.cover.small.jpg", entry.cover)
        assertEquals(listOf(SourceKind.EPUB, SourceKind.MOBI), entry.acquisitions.map { it.kind })
        assertEquals("https://www.gutenberg.org/ebooks/author/61.opds", entry.related.single().href)
    }

    @Test
    fun `escaped html summaries are read as text`() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title>
<entry><title>T</title><id>1</id><summary type="html">&lt;p&gt;One &amp;amp; two&lt;/p&gt;&lt;p&gt;Three&lt;/p&gt;</summary></entry></feed>"""
        )
        assertEquals("One & two\nThree", feed.entries.single().summary)
    }

    @Test
    fun `opensearch description gives the atom template, filled in safely`() {
        val template = OpdsParser.parseSearchTemplate(
            """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
   <Url type="text/html" template="http://www.gutenberg.org/ebooks/search/?query={searchTerms}"/>
   <Url type="application/atom+xml" template="http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}&amp;p={startPage?}"/>
</OpenSearchDescription>""".byteInputStream(),
            "https://www.gutenberg.org/catalog/osd-books.xml",
        )
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}&p={startPage?}", template)
        assertEquals(
            "http://m.gutenberg.org/ebooks/search.opds/?query=war+%26+peace&p=",
            OpdsParser.searchUrl(template!!, " war & peace "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a page that is not a feed is refused`() {
        parse("<html><body>Not here</body></html>")
    }
}
