package com.ebookreader.data.backup

import com.ebookreader.domain.model.BookStatsSnapshot
import com.ebookreader.domain.model.SessionSnapshot
import com.ebookreader.domain.model.StatsBackup
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
                    put("readingStatus", book.readingStatus)
                    put("isFavorite", book.isFavorite)
                    put("dateAdded", book.dateAdded)
                    // JSONObject.put(String, Any?) drops null keys, which is what we want:
                    // an absent lastRead means "never opened".
                    put("lastRead", book.lastRead)
                    put("totalReadingSeconds", book.totalReadingSeconds)
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
            BookStatsSnapshot(
                title = obj.optString("title"),
                author = obj.optString("author"),
                format = obj.optString("format", "EPUB"),
                totalPages = obj.optInt("totalPages", 0),
                currentPage = obj.optInt("currentPage", 0),
                scrollFraction = obj.optDouble("scrollFraction", 0.0).toFloat(),
                readingStatus = obj.optString("readingStatus", "NOT_STARTED"),
                isFavorite = obj.optBoolean("isFavorite", false),
                dateAdded = obj.optLong("dateAdded", 0L),
                lastRead = if (obj.isNull("lastRead")) null else obj.optLong("lastRead"),
                totalReadingSeconds = obj.optLong("totalReadingSeconds", 0L)
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

        if (books.isEmpty() && sessions.isEmpty()) {
            throw InvalidBackupException("Backup contains no readable stats.")
        }

        return StatsBackup(
            exportedAt = root.optLong("exportedAt", 0L),
            appVersion = root.optString("appVersion"),
            books = books,
            sessions = sessions
        )
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
    }
}
