package com.ebookreader.data.backup

import androidx.room.withTransaction
import com.ebookreader.BuildConfig
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.ReadingSessionEntity
import com.ebookreader.domain.model.BookStatsSnapshot
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.SessionSnapshot
import com.ebookreader.domain.model.StatsBackup
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.domain.model.StatsImportResult
import com.ebookreader.domain.model.bookMatchKey
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Builds and applies stats backups.
 *
 * Books are matched across devices by title + author ([bookMatchKey]); row ids are local
 * and worthless in a file. Sessions whose book is not in the library still get imported —
 * the sessions table has no foreign key precisely so history outlives the book — and are
 * filed under a stable synthetic id so the leaderboards still group them by title.
 */
@Singleton
class StatsBackupManager @Inject constructor(
    private val database: AppDatabase,
    private val bookDao: BookDao,
    private val sessionDao: ReadingSessionDao
) {

    suspend fun export(): StatsBackup {
        val books = bookDao.getAllBooksOnce()
        val booksById = books.associateBy { it.id }
        val sessions = sessionDao.getAllSessionsOnce()

        return StatsBackup(
            exportedAt = Date().time,
            appVersion = BuildConfig.VERSION_NAME,
            books = books.map { it.toSnapshot() },
            sessions = sessions.map { session ->
                val book = booksById[session.bookId]
                SessionSnapshot(
                    // Pre-v4 rows have a blank snapshotted title; fall back to the live book.
                    bookTitle = session.bookTitle.ifBlank { book?.title ?: UNKNOWN_TITLE },
                    bookAuthor = book?.author.orEmpty(),
                    durationSeconds = session.durationSeconds,
                    startedAt = session.startedAt,
                    timestamp = session.timestamp
                )
            }
        )
    }

    /**
     * @param restoreProgress also puts each matched book back where the backup left it —
     *   but only when the backup is the more recent of the two, so a merge can never
     *   rewind progress made on this device.
     */
    suspend fun import(
        backup: StatsBackup,
        mode: StatsImportMode,
        restoreProgress: Boolean
    ): StatsImportResult = database.withTransaction {
        val localBooks = bookDao.getAllBooksOnce()
        val localIdByKey = localBooks.associate { bookMatchKey(it.title, it.author) to it.id }

        // Resolve every incoming session to a book id before anything is written, so
        // duplicate detection and the per-book totals below agree on the same ids.
        val resolved = backup.sessions.map { session ->
            val key = bookMatchKey(session.bookTitle, session.bookAuthor)
            ReadingSessionEntity(
                bookId = localIdByKey[key] ?: syntheticBookId(key),
                bookTitle = session.bookTitle.ifBlank { UNKNOWN_TITLE },
                durationSeconds = session.durationSeconds,
                startedAt = session.startedAt,
                timestamp = session.timestamp
            )
        }

        val toInsert = when (mode) {
            StatsImportMode.REPLACE -> {
                sessionDao.deleteAllSessions()
                // Two exports of the same history can still overlap inside one file.
                resolved.withoutOverlaps(emptyList())
            }

            StatsImportMode.MERGE -> {
                resolved.withoutOverlaps(sessionDao.getAllSessionsOnce())
            }
        }
        val skipped = resolved.size - toInsert.size

        if (toInsert.isNotEmpty()) {
            sessionDao.insertSessions(toInsert)
        }

        val backupBooksByKey = backup.books.associateBy { bookMatchKey(it.title, it.author) }
        val matchedKeys = backupBooksByKey.keys.intersect(localIdByKey.keys)

        when (mode) {
            // The backup is the whole truth: a book it never heard of has no time on it.
            StatsImportMode.REPLACE -> {
                val secondsFromSessions = toInsert.groupBy { it.bookId }
                    .mapValues { (_, rows) -> rows.sumOf { it.durationSeconds } }
                localBooks.forEach { book ->
                    val key = bookMatchKey(book.title, book.author)
                    val seconds = backupBooksByKey[key]?.totalReadingSeconds
                        ?: secondsFromSessions[book.id]
                        ?: 0L
                    bookDao.setReadingSeconds(book.id, seconds)
                }
            }

            // Only the sessions that were actually new may move a running total.
            StatsImportMode.MERGE -> {
                toInsert.groupBy { it.bookId }
                    .forEach { (bookId, rows) ->
                        if (bookId > 0) {
                            bookDao.addReadingSeconds(bookId, rows.sumOf { it.durationSeconds })
                        }
                    }
            }
        }

        if (restoreProgress) {
            matchedKeys.forEach { key ->
                val snapshot = backupBooksByKey.getValue(key)
                val localId = localIdByKey.getValue(key)
                val local = localBooks.first { it.id == localId }
                if (mode == StatsImportMode.REPLACE || snapshot.isNewerThan(local)) {
                    bookDao.restoreProgress(
                        bookId = localId,
                        page = snapshot.currentPage.coerceAtLeast(0),
                        scrollFraction = snapshot.scrollFraction.coerceIn(0f, 1f),
                        status = snapshot.safeStatus(),
                        lastRead = snapshot.lastRead,
                        isFavorite = snapshot.isFavorite
                    )
                }
            }
        }

        StatsImportResult(
            sessionsImported = toInsert.size,
            sessionsSkipped = skipped,
            booksMatched = matchedKeys.size,
            booksUnmatched = backupBooksByKey.size - matchedKeys.size
        )
    }

    private fun BookEntity.toSnapshot() = BookStatsSnapshot(
        title = title,
        author = author,
        format = format,
        totalPages = totalPages,
        currentPage = currentPage,
        scrollFraction = scrollFraction,
        readingStatus = readingStatus,
        isFavorite = isFavorite,
        dateAdded = dateAdded,
        lastRead = lastRead,
        totalReadingSeconds = totalReadingSeconds
    )

    /** A session is the same session if it is the same book, instant and length. */
    /**
     * Drops incoming sittings that the device already has.
     *
     * Matching on exact (book, end, duration) used to be enough, but a sitting's end and
     * duration both move as it is extended, and the v4 -> v5 migration rewrote old rows
     * for the same reason. So overlap decides instead: nobody reads the same book in two
     * places at once, which makes two overlapping sittings for one book the same sitting.
     * Incoming rows are checked against each other as well, since one file can contain
     * two exports of the same history.
     */
    private fun List<ReadingSessionEntity>.withoutOverlaps(
        existing: List<ReadingSessionEntity>
    ): List<ReadingSessionEntity> {
        val spansByBook = existing.groupByTo(mutableMapOf(), { it.bookId }) {
            it.startedAt to it.timestamp
        }
        val kept = mutableListOf<ReadingSessionEntity>()
        for (session in sortedBy { it.startedAt }) {
            val spans = spansByBook.getOrPut(session.bookId) { mutableListOf() }
            val overlaps = spans.any { (start, end) ->
                session.startedAt <= end && start <= session.timestamp
            }
            if (!overlaps) {
                spans += session.startedAt to session.timestamp
                kept += session
            }
        }
        return kept
    }

    private fun BookStatsSnapshot.isNewerThan(local: BookEntity): Boolean {
        val theirs = lastRead ?: return false
        val ours = local.lastRead ?: return true
        return theirs > ours
    }

    private fun BookStatsSnapshot.safeStatus(): String =
        ReadingStatus.entries.firstOrNull { it.name == readingStatus }?.name
            ?: ReadingStatus.READING.name

    /**
     * Id for a session whose book is not in this library. Negative so it can never collide
     * with a real (autoincrement, positive) row id, and derived from the title so repeated
     * imports of the same orphan land on the same id instead of piling up duplicates.
     */
    private fun syntheticBookId(matchKey: String): Long =
        -(matchKey.hashCode().toLong().absoluteValue + 1)

    private companion object {
        const val UNKNOWN_TITLE = "Unknown Book"
    }
}
