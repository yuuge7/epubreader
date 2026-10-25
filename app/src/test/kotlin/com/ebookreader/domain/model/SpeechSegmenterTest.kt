package com.ebookreader.domain.model

import com.ebookreader.data.epub.visibleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private fun texts(s: String) = SpeechSegmenter.split(s).map { it.text }

    @Test
    fun `sentences end at stops, keep their closing quotes, and never cross a paragraph`() {
        assertEquals(
            listOf("“Who is there?” she said.", "Nobody.", "A heading", "Next paragraph!"),
            texts("“Who is there?” she said. Nobody.\nA heading\nNext paragraph!"),
        )
    }

    @Test
    fun `titles and initials do not end a sentence`() {
        assertEquals(
            listOf("To Mrs. Saville, from H. G. Wells.", "Dr. Who arrived."),
            texts("To Mrs. Saville, from H. G. Wells. Dr. Who arrived."),
        )
    }

    @Test
    fun `offsets point back into the text`() {
        val text = "One. Two three."
        SpeechSegmenter.split(text).forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
    }

    @Test
    fun `very long sentences are cut at a space`() {
        val long = ("word ".repeat(150)).trim() + "."
        val parts = SpeechSegmenter.split(long)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.text.length <= 300 })
    }

    @Test
    fun `wrapped markup reads as one paragraph`() {
        // Books wrap their source mid-sentence; only the block tags break paragraphs.
        val html = "<p>\nI am already far north of London, and as I walk in the streets of Petersburgh,\nI feel a cold northern breeze.\n</p><p>Next.</p>"
        assertEquals(
            listOf(
                "I am already far north of London, and as I walk in the streets of Petersburgh, I feel a cold northern breeze.",
                "Next.",
            ),
            texts(visibleText(html)),
        )
    }
}
