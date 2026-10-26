package com.ebookreader.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ChapterLayout
import com.ebookreader.domain.model.ReadingStatus
import java.util.Date

// contentHash: an import looks for the same file under any name.
@Entity(tableName = "books", indices = [Index("contentHash")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /**
     * Scroll position *inside* [currentPage] (0f = top, 1f = bottom).
     * EPUB: scroll offset within the chapter. PDF: unused (page granularity).
     */
    @ColumnInfo(defaultValue = "0")
    val scrollFraction: Float = 0f,
    /** Share of the book read, 0..1. See [Book.progress]. */
    @ColumnInfo(defaultValue = "0")
    val progress: Float = 0f,
    val isFavorite: Boolean = false,
    val readingStatus: String = ReadingStatus.NOT_STARTED.name,
    val dateAdded: Long = Date().time,
    val lastRead: Long? = null,
    val fileSize: Long = 0L,
    val totalReadingSeconds: Long = 0L,
    /** Visible characters in the whole book, as the reader measured them; 0 = not yet. */
    @ColumnInfo(defaultValue = "0")
    val textLength: Int = 0,
    /** The text each chapter holds, comma-separated, in reading order. */
    val chapterWeights: String? = null,
    /** Where the reader is, in words: a chapter title, or "Page 12 of 40". */
    val positionLabel: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    /** What the file was before import converted it (TXT, MD, FB2, MOBI, AZW3, CBR). */
    val sourceFormat: String? = null,
    /** Whether the book's text is in the full-text index. */
    @ColumnInfo(defaultValue = "0")
    val textIndexed: Boolean = false,
    /**
     * How the chapters are numbered (see [ChapterLayout]). Rows from before v8 are
     * [ChapterLayout.LEGACY] until their positions are carried over; new books start current.
     */
    @ColumnInfo(defaultValue = "1")
    val chapterLayout: Int = ChapterLayout.CURRENT,
    val description: String? = null,
    val contentHash: String? = null,
    /** [BookReadingSettings] as JSON; null follows the app's settings. */
    val readingSettings: String? = null,
    val finishedAt: Long? = null,
) {
    fun toDomain() = Book(
        id = id, title = title, author = author,
        filePath = filePath, format = BookFormat.fromName(format),
        coverPath = coverPath, totalPages = totalPages, currentPage = currentPage,
        scrollFraction = scrollFraction, progress = progress,
        isFavorite = isFavorite, readingStatus = ReadingStatus.valueOf(readingStatus),
        dateAdded = Date(dateAdded), lastRead = lastRead?.let { Date(it) },
        fileSize = fileSize, totalReadingSeconds = totalReadingSeconds,
        textLength = textLength,
        chapterWeights = chapterWeights.toWeights(),
        positionLabel = positionLabel,
        series = series, seriesIndex = seriesIndex,
        sourceFormat = sourceFormat,
        textIndexed = textIndexed,
        chapterLayout = chapterLayout,
        description = description,
        contentHash = contentHash,
        readingSettings = ReadingSettingsCodec.decode(readingSettings),
        finishedAt = finishedAt?.let { Date(it) },
    )

    companion object {
        fun fromDomain(book: Book) = BookEntity(
            id = book.id, title = book.title, author = book.author,
            filePath = book.filePath, format = book.format.name,
            coverPath = book.coverPath, totalPages = book.totalPages,
            currentPage = book.currentPage, scrollFraction = book.scrollFraction,
            progress = book.progress,
            isFavorite = book.isFavorite,
            readingStatus = book.readingStatus.name, dateAdded = book.dateAdded.time,
            lastRead = book.lastRead?.time, fileSize = book.fileSize,
            totalReadingSeconds = book.totalReadingSeconds,
            textLength = book.textLength,
            chapterWeights = book.chapterWeights.toColumn(),
            positionLabel = book.positionLabel,
            series = book.series, seriesIndex = book.seriesIndex,
            sourceFormat = book.sourceFormat,
            textIndexed = book.textIndexed,
            chapterLayout = book.chapterLayout,
            description = book.description,
            contentHash = book.contentHash,
            readingSettings = ReadingSettingsCodec.encode(book.readingSettings),
            finishedAt = book.finishedAt?.time,
        )
    }
}

internal fun List<Int>.toColumn(): String? = if (isEmpty()) null else joinToString(",")

internal fun String?.toWeights(): List<Int> =
    this?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
