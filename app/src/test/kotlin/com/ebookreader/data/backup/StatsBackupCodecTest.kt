package com.ebookreader.data.backup

import com.ebookreader.domain.model.BookStatsSnapshot
import com.ebookreader.domain.model.HighlightSnapshot
import com.ebookreader.domain.model.StatsBackup
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsBackupCodecTest {

    private fun book(progress: Float) = BookStatsSnapshot(
        title = "T", author = "A", format = "EPUB", totalPages = 10, currentPage = 4,
        scrollFraction = 0.5f, progress = progress, readingStatus = "READING",
        isFavorite = false, dateAdded = 1L, lastRead = 2L, totalReadingSeconds = 60L,
    )

    @Test
    fun `stored progress survives a round trip`() {
        val backup = StatsBackup(exportedAt = 5L, appVersion = "1.6", books = listOf(book(0.37f)), sessions = emptyList())
        val decoded = StatsBackupCodec.decode(StatsBackupCodec.encode(backup))
        assertEquals(0.37f, decoded.books.single().progress, 1e-6f)
    }

    @Test
    fun `highlights and the place in words survive a round trip`() {
        val highlight = HighlightSnapshot(
            bookTitle = "T", bookAuthor = "A", chapterIndex = 3, startOffset = 10, endOffset = 42,
            text = "a \"quoted\" line", note = "mine", color = "GREEN", createdAt = 7L,
        )
        val backup = StatsBackup(
            exportedAt = 5L, appVersion = "1.6",
            books = listOf(book(0.5f).copy(positionLabel = "Chapter 4")),
            sessions = emptyList(),
            highlights = listOf(highlight),
        )
        val decoded = StatsBackupCodec.decode(StatsBackupCodec.encode(backup))
        assertEquals(listOf(highlight), decoded.highlights)
        assertEquals("Chapter 4", decoded.books.single().positionLabel)
    }

    @Test
    fun `a file from before progress was stored gets the old estimate`() {
        val json = """
            {"schema":"ebookreader.stats","version":2,"books":[
              {"title":"E","author":"A","format":"EPUB","totalPages":10,"currentPage":4,"scrollFraction":0.5,"readingStatus":"READING"},
              {"title":"P","author":"A","format":"PDF","totalPages":200,"currentPage":49,"readingStatus":"READING"},
              {"title":"F","author":"A","format":"EPUB","totalPages":10,"currentPage":9,"readingStatus":"FINISHED"}
            ],"sessions":[]}
        """.trimIndent()
        val books = StatsBackupCodec.decode(json).books.associateBy { it.title }
        assertEquals(0.45f, books.getValue("E").progress, 1e-6f)
        assertEquals(0.25f, books.getValue("P").progress, 1e-6f)
        assertEquals(1f, books.getValue("F").progress, 1e-6f)
    }
}
