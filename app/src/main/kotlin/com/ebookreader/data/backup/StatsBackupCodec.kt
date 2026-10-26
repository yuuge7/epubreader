package com.ebookreader.data.backup

import com.ebookreader.domain.model.BookStatsSnapshot
import com.ebookreader.domain.model.HighlightSnapshot
import com.ebookreader.domain.model.SessionSnapshot
import com.ebookreader.domain.model.StatsBackup
import com.ebookreader.domain.model.pageProgress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes the stats backup file. Plain JSON via [org.json] — it ships with
 * the platform, so the export stays inspectable without pulling in a serializer.
 */
object StatsBackupCodec {

    const val SCHEMA = "ebookreader.stats"

    /**
     * 1: sessions carried only an end timestamp.
     * 2: sessions carry `startedAt` as well, since a sitting now has a real span.
     *    Later 2 files may also carry `highlights`; readers that predate them skip the
     *    key, so it needed no new version.
     */
    const val VERSION = 2
    const val MIME_TYPE = "application/json"

    class InvalidBackupException(message: String) : Exception(message)

    fun encode(backup: StatsBackup): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("version", VERSION)
        root.put("exportedAt", backup.exportedAt)
        root.put("appVersion", backup.appVersion)

        val books = JSONArray()
        backup.books.forEach { book ->
            books.put(
                JSONObject().apply {
                    put("title", book.title)
                    put("author", book.author)
                    put("format", book.format)
                    put("totalPages", book.totalPages)
                    put("currentPage", book.currentPage)
                    put("scrollFraction", book.scrollFraction.toDouble())
                    put("progress", book.progress.toDouble())
                    put("readingStatus", book.readingStatus)
                    put("isFavorite", book.isFavorite)
                    put("dateAdded", book.dateAdded)
                    // JSONObject.put(String, Any?) drops null keys, which is what we want:
                    // an absent lastRead means "never opened".
                    put("lastRead", book.lastRead)
                    put("totalReadingSeconds", book.totalReadingSeconds)
                    put("positionLabel", book.positionLabel)
                    put("chapterLayout", book.chapterLayout)
                }
            )
        }
        root.put("books", books)

        val sessions = JSONArray()
        backup.sessions.forEach { session ->
            sessions.put(
                JSONObject().apply {
                    put("bookTitle", session.bookTitle)
                    put("bookAuthor", session.bookAuthor)
                    put("durationSeconds", session.durationSeconds)
                    put("startedAt", session.startedAt)
                    put("timestamp", session.timestamp)
                }
            )
        }
        root.put("sessions", sessions)

        if (backup.highlights.isNotEmpty()) {
            val highlights = JSONArray()
            backup.highlights.forEach { h ->
                highlights.put(
                    JSONObject().apply {
                        put("bookTitle", h.bookTitle)
                        put("bookAuthor", h.bookAuthor)
                        put("chapterIndex", h.chapterIndex)
                        put("startOffset", h.startOffset)
                        put("endOffset", h.endOffset)
                        put("text", h.text)
                        put("note", h.note)
                        put("color", h.color)
                        put("createdAt", h.createdAt)
                        put("chapterLayout", h.chapterLayout)
                    }
                )
            }
            root.put("highlights", highlights)
        }

        return root.toString(2)
    }

    /**
     * @throws InvalidBackupException if the file is not a stats backup this build understands.
     */
    fun decode(json: String): StatsBackup {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidBackupException("Not a valid JSON file.")
        }

        if (root.optString("schema") != SCHEMA) {
            throw InvalidBackupException("This file is not an EBook Reader stats backup.")
        }
        val version = root.optInt("version", 0)
        if (version > VERSION) {
            throw InvalidBackupException(
                "Backup was written by a newer version of the app (format $version)."
            )
        }

        val books = root.optJSONArray("books").mapObjects { obj ->
            val format = obj.optString("format", "EPUB")
            val totalPages = obj.optInt("totalPages", 0)
            val currentPage = obj.optInt("currentPage", 0)
            val scrollFraction = obj.optDouble("scrollFraction", 0.0).toFloat()
            val status = obj.optString("readingStatus", "NOT_STARTED")
            BookStatsSnapshot(
                title = obj.optString("title"),
                author = obj.optString("author"),
                format = format,
                totalPages = totalPages,
                currentPage = currentPage,
                scrollFraction = scrollFraction,
                // Files written before progress was stored get the estimate the app
                // showed at the time, the same one the v5 -> v6 migration seeds.
                progress = obj.optDouble("progress", Double.NaN).toFloat()
                    .takeUnless { it.isNaN() }
                    ?: legacyProgress(format, status, currentPage, scrollFraction, totalPages),
                readingStatus = status,
                isFavorite = obj.optBoolean("isFavorite", false),
                dateAdded = obj.optLong("dateAdded", 0L),
                lastRead = if (obj.isNull("lastRead")) null else obj.optLong("lastRead"),
                totalReadingSeconds = obj.optLong("totalReadingSeconds", 0L),
                positionLabel = if (obj.isNull("positionLabel")) null else obj.optString("positionLabel").ifBlank { null },
                chapterLayout = if (obj.has("chapterLayout")) obj.optInt("chapterLayout") else null,
            )
        }.filter { it.title.isNotBlank() }

        val sessions = root.optJSONArray("sessions").mapObjects { obj ->
            val endedAt = obj.optLong("timestamp", 0L)
            val duration = obj.optLong("durationSeconds", 0L)
            SessionSnapshot(
                bookTitle = obj.optString("bookTitle"),
                bookAuthor = obj.optString("bookAuthor"),
                durationSeconds = duration,
                // Format 1 stored only the end. Working back from it is the same estimate
                // the v4 -> v5 database migration makes for rows of that vintage.
                startedAt = obj.optLong("startedAt", 0L)
                    .takeIf { it > 0L } ?: (endedAt - duration * 1000L),
                timestamp = endedAt
            )
        }.filter { it.durationSeconds > 0 && it.timestamp > 0 }

        val highlights = root.optJSONArray("highlights").mapObjects { obj ->
            HighlightSnapshot(
                bookTitle = obj.optString("bookTitle"),
                bookAuthor = obj.optString("bookAuthor"),
                chapterIndex = obj.optInt("chapterIndex", -1),
                startOffset = obj.optInt("startOffset", -1),
                endOffset = obj.optInt("endOffset", -1),
                text = obj.optString("text"),
                note = obj.optString("note"),
                color = obj.optString("color", "YELLOW"),
                createdAt = obj.optLong("createdAt", 0L),
                chapterLayout = if (obj.has("chapterLayout")) obj.optInt("chapterLayout") else null,
            )
        }.filter { it.bookTitle.isNotBlank() && it.chapterIndex >= 0 && it.endOffset > it.startOffset && it.startOffset >= 0 }

        if (books.isEmpty() && sessions.isEmpty()) {
            throw InvalidBackupException("Backup contains no readable stats.")
        }

        return StatsBackup(
            exportedAt = root.optLong("exportedAt", 0L),
            appVersion = root.optString("appVersion"),
            books = books,
            sessions = sessions,
            highlights = highlights,
        )
    }

    private fun legacyProgress(
        format: String,
        status: String,
        page: Int,
        scrollFraction: Float,
        totalPages: Int,
    ): Float = when {
        status == "FINISHED" -> 1f
        status == "NOT_STARTED" || totalPages <= 0 -> 0f
        format == "PDF" -> pageProgress(page, totalPages)
        else -> ((page + scrollFraction) / totalPages).coerceIn(0f, 1f)
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
    }
}
