package com.ebookreader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.util.Date

class SessionsCsvTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun `one row per sitting, oldest first, in local time`() {
        val book = Book(id = 1, title = "Emma", author = "Jane Austen", filePath = "", format = BookFormat.EPUB)
        val sessions = listOf(
            ReadingSession(bookId = 1, bookTitle = "Emma", durationSeconds = 1800,
                startedAt = Date(1_700_003_600_000), endedAt = Date(1_700_005_400_000)),
            ReadingSession(bookId = 1, bookTitle = "Emma", durationSeconds = 90,
                startedAt = Date(1_700_000_000_000), endedAt = Date(1_700_000_090_000)),
        )
        val lines = SessionsCsv.write(sessions, mapOf(1L to book), utc).trimEnd().split("\r\n")
        assertEquals("date,start,end,minutes,seconds,title,author", lines[0])
        assertEquals("2023-11-14,22:13,22:14,1.5,90,Emma,Jane Austen", lines[1])
        assertEquals("2023-11-14,23:13,23:43,30.0,1800,Emma,Jane Austen", lines[2])
    }

    @Test
    fun `fields with commas or quotes are quoted`() {
        assertEquals("\"Frankenstein; or, The Modern Prometheus\"", SessionsCsv.quote("Frankenstein; or, The Modern Prometheus"))
        assertEquals("\"The \"\"Real\"\" Book\"", SessionsCsv.quote("The \"Real\" Book"))
        assertEquals("Plain", SessionsCsv.quote("Plain"))
    }

    @Test
    fun `a sitting of a removed book keeps its title`() {
        val csv = SessionsCsv.write(
            listOf(ReadingSession(bookId = 9, bookTitle = "Gone", durationSeconds = 60, startedAt = Date(0), endedAt = Date(60_000))),
            emptyMap(), utc,
        )
        assertEquals("1970-01-01,00:00,00:01,1.0,60,Gone,", csv.trimEnd().split("\r\n")[1])
    }
}
