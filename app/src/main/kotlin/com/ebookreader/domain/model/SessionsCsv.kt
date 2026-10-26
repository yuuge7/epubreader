package com.ebookreader.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The reading history as CSV, for a spreadsheet: one row per sitting, oldest first, in
 * local time. Fields are quoted the RFC 4180 way, so a title with a comma or a quote
 * stays in its column.
 */
object SessionsCsv {
    val HEADER = listOf("date", "start", "end", "minutes", "seconds", "title", "author")

    fun write(sessions: List<ReadingSession>, books: Map<Long, Book>, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ISO_LOCAL_DATE
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val out = StringBuilder()
        out.append(HEADER.joinToString(",")).append("\r\n")
        sessions.sortedBy { it.startedAt }.forEach { s ->
            val start = Instant.ofEpochMilli(s.startedAt.time).atZone(zone)
            val end = Instant.ofEpochMilli(s.endedAt.time).atZone(zone)
            val book = books[s.bookId]
            val row = listOf(
                start.format(date),
                start.format(time),
                end.format(time),
                String.format(java.util.Locale.ROOT, "%.1f", s.durationSeconds / 60.0),
                s.durationSeconds.toString(),
                book?.title ?: s.bookTitle,
                book?.author.orEmpty(),
            )
            out.append(row.joinToString(",") { quote(it) }).append("\r\n")
        }
        return out.toString()
    }

    internal fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\""
        else field
}
