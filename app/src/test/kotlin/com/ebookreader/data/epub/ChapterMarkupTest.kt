package com.ebookreader.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterMarkupTest {

    @Test
    fun `visible text skips markup, head, scripts, styles and comments`() {
        val html = """
            <html><head><title>Not counted</title><style>p { color: red }</style></head>
            <body><!-- hidden --><p>Hello <b>world</b></p><script>var x = 1;</script></body></html>
        """.trimIndent()
        assertEquals("Helloworld".length, visibleTextLength(html))
    }

    @Test
    fun `an entity counts as the one character it stands for`() {
        assertEquals(3, visibleTextLength("<p>a&amp;b</p>"))
        assertEquals(3, visibleTextLength("<p>a&#8212;b</p>"))
        assertEquals(3, visibleTextLength("<p>a&#x2014;b</p>"))
    }

    @Test
    fun `an ampersand that starts no entity is one character`() {
        assertEquals(5, visibleTextLength("<p>a & b; c</p>"))
        assertEquals(3, visibleTextLength("<p>R&D</p>"))
    }

    @Test
    fun `unclosed scripts and comments end the count rather than looping`() {
        assertEquals(2, visibleTextLength("<p>ok</p><script>never closed"))
        assertEquals(2, visibleTextLength("<p>ok</p><!-- never closed"))
    }

    @Test
    fun `a header element is text, not the head`() {
        assertEquals(5, visibleTextLength("<body><header>Title</header></body>"))
    }

    @Test
    fun `hrefs are percent-decoded as paths`() {
        assertEquals("Text/chapter 1.xhtml", decodeHref("Text/chapter%201.xhtml"))
        assertEquals("caf\u00e9.xhtml", decodeHref("caf%C3%A9.xhtml"))
        // A plus is a plus in a path, unlike in a query string.
        assertEquals("a+b.xhtml", decodeHref("a+b.xhtml"))
        // Malformed escapes are kept as they are rather than failing the book.
        assertEquals("100%.xhtml", decodeHref("100%.xhtml"))
        assertEquals("%zz.xhtml", decodeHref("%zz.xhtml"))
    }

    @Test
    fun `self-closing anchors are closed explicitly`() {
        assertEquals(
            """<p><a id="ch2"></a>Text</p>""",
            expandSelfClosingTags("""<p><a id="ch2"/>Text</p>""")
        )
        assertEquals("<div></div>", expandSelfClosingTags("<div/>"))
    }

    @Test
    fun `void elements keep their self-closing form`() {
        val html = """<br/><img src="a.png" alt="x"/><hr /><meta charset="utf-8"/>"""
        assertEquals(html, expandSelfClosingTags(html))
    }

    @Test
    fun `svg children survive expansion`() {
        assertEquals(
            """<svg><image href="c.jpg" width="10"></image></svg>""",
            expandSelfClosingTags("""<svg><image href="c.jpg" width="10"/></svg>""")
        )
    }

    @Test
    fun `markup goes at the end of the head, after the book's stylesheets`() {
        val html = """<html><head><link rel="stylesheet" href="s.css"/></head><body/></html>"""
        assertEquals(
            """<html><head><link rel="stylesheet" href="s.css"/><style>x</style>""" + "\n" + """</head><body/></html>""",
            insertAtEndOfHead(html, "<style>x</style>")
        )
    }

    @Test
    fun `a document without a head still gets the markup`() {
        assertEquals(
            "<html><head><style>x</style></head><body><p>hi</p></body></html>",
            insertAtEndOfHead("<p>hi</p>", "<style>x</style>")
        )
    }

    @Test
    fun `an unclosed head falls back to its top`() {
        assertEquals("<head>\n<style>x</style><p>hi</p>", insertAtEndOfHead("<head><p>hi</p>", "<style>x</style>"))
    }
}
