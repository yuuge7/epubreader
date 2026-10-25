package com.ebookreader.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertIntoHeadTest {

    private val tag = "<meta name=\"x\">"

    @Test
    fun `plain head gets the markup right after it`() {
        assertEquals(
            "<html><head>\n$tag<title>t</title></head></html>",
            insertIntoHead("<html><head><title>t</title></head></html>", tag)
        )
    }

    @Test
    fun `head with attributes is still the head`() {
        val out = insertIntoHead("<html><head profile=\"p\"><title>t</title></head></html>", tag)
        assertEquals("<html><head profile=\"p\">\n$tag<title>t</title></head></html>", out)
    }

    @Test
    fun `tag case does not matter`() {
        assertTrue(insertIntoHead("<HTML><HEAD></HEAD></HTML>", tag).startsWith("<HTML><HEAD>\n$tag"))
    }

    @Test
    fun `a header element is not mistaken for the head`() {
        val html = "<body><header>h</header></body>"
        assertEquals("<html><head>$tag</head><body>$html</body></html>", insertIntoHead(html, tag))
    }

    @Test
    fun `only the first head is touched`() {
        val out = insertIntoHead("<head></head><head></head>", tag)
        assertEquals("<head>\n$tag</head><head></head>", out)
    }
}
