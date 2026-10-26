package com.ebookreader.domain.model

import java.util.Date

/**
 * How a book is read. Formats the app converts at import (TXT, Markdown, FB2, MOBI,
 * AZW3) become [EPUB]; CBR comics become [COMIC], like CBZ.
 */
enum class BookFormat {
    PDF, EPUB, COMIC;

    companion object {
        /** A stored name this build does not know reads as EPUB rather than crashing. */
        fun fromName(name: String): BookFormat = entries.firstOrNull { it.name == name } ?: EPUB
    }
}

enum class ReadingStatus { NOT_STARTED, READING, FINISHED }

/**
 * How the chapters of an EPUB are numbered.
 *
 * [LEGACY]: one chapter per entry of the NCX contents list, or per spine item when the NCX
 * was not found. Two entries pointing into one file were two chapters showing that whole
 * file, and spine items the NCX left out (a cover, a title page) could not be reached.
 *
 * [SPINE]: one chapter per file of the reading order, which is what is shown. The contents
 * list is kept apart from it, nested, and points at places inside those files.
 */
object ChapterLayout {
    const val LEGACY = 1
    const val SPINE = 2
    const val CURRENT = SPINE
}
enum class SortOption(
    /** Newest, longest, furthest first; names A to Z. */
    val descendingByDefault: Boolean,
) {
    TITLE(false), AUTHOR(false), DATE_ADDED(true), LAST_READ(true),
    PROGRESS(true), READING_TIME(true), FILE_SIZE(true);

    /** Orders books by this key alone, ascending. */
    val keyComparator: Comparator<Book>
        get() = when (this) {
            TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.sortKey() }
            AUTHOR -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.author.sortKey() }
            DATE_ADDED -> compareBy { it.dateAdded }
            // Never-opened books sort as the oldest.
            LAST_READ -> compareBy { it.lastRead?.time ?: Long.MIN_VALUE }
            PROGRESS -> compareBy { it.readingProgress }
            READING_TIME -> compareBy { it.totalReadingSeconds }
            FILE_SIZE -> compareBy { it.fileSize }
        }
}

/**
 * Books sorted by [sort], from the top or the bottom of its order. Ties go A to Z by
 * title either way: only the key turns around.
 */
fun List<Book>.sortedBy(sort: SortOption, descending: Boolean): List<Book> {
    val key = if (descending) sort.keyComparator.reversed() else sort.keyComparator
    return sortedWith(key.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title.sortKey() })
}

/** "The Hobbit" files under H: a leading article does not decide where a title sorts. */
internal fun String.sortKey(): String {
    val t = trim()
    for (article in listOf("the ", "a ", "an ")) {
        if (t.length > article.length && t.regionMatches(0, article, 0, article.length, ignoreCase = true)) {
            return t.substring(article.length)
        }
    }
    return t
}
enum class FilterOption { ALL, PDF, EPUB, COMIC, FAVORITES, READING, FINISHED }

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val format: BookFormat,
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /** Scroll position inside [currentPage]: 0f = top of page/chapter, 1f = bottom. */
    val scrollFraction: Float = 0f,
    /**
     * Share of the book read, 0..1, as the reader last measured it. For EPUB it is
     * weighted by how much text each chapter holds, so it cannot be derived from
     * [currentPage] and [totalPages] alone.
     */
    val progress: Float = 0f,
    val isFavorite: Boolean = false,
    val readingStatus: ReadingStatus = ReadingStatus.NOT_STARTED,
    val dateAdded: Date = Date(),
    val lastRead: Date? = null,
    val fileSize: Long = 0L,
    val totalReadingSeconds: Long = 0L,
    /** Visible characters in the whole book, as the reader measured them; 0 = not yet. */
    val textLength: Int = 0,
    /** How much text each chapter holds, in reading order; empty until measured. */
    val chapterWeights: List<Int> = emptyList(),
    /** Where the reader is, in words: a chapter title, or "Page 12 of 40". */
    val positionLabel: String? = null,
    val series: String? = null,
    /** Place in [series]; fractional for novellas slotted between books (2.5). */
    val seriesIndex: Float? = null,
    /** What the file was before import converted it, if it was: TXT, MD, FB2, MOBI... */
    val sourceFormat: String? = null,
    /** Whether the text is in the full-text search index. */
    val textIndexed: Boolean = false,
    /**
     * How an EPUB's chapters are numbered, which every saved position, bookmark and
     * highlight is stored against. See [ChapterLayout].
     */
    val chapterLayout: Int = ChapterLayout.CURRENT,
    /** The publisher's blurb, as plain text. */
    val description: String? = null,
    /** SHA-256 of the file as it was imported, to know the same book under another name. */
    val contentHash: String? = null,
    /** Type and page for this book alone; null follows the app's settings. */
    val readingSettings: BookReadingSettings? = null,
    /** When the book was finished, for the timeline; kept if it is read again. */
    val finishedAt: Date? = null,
) {
    val readingProgress: Float
        get() = when {
            readingStatus == ReadingStatus.FINISHED -> 1f
            readingStatus == ReadingStatus.NOT_STARTED -> 0f
            else -> progress.coerceIn(0f, 1f)
        }

    /** The label for the file type: the original one for a converted book. */
    val formatLabel: String get() = sourceFormat ?: format.name

    val formattedReadingTime: String
        get() {
            if (totalReadingSeconds <= 0L) return ""
            val h = totalReadingSeconds / 3600
            val m = (totalReadingSeconds % 3600) / 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m"
                else -> "<1m"
            }
        }
}

/** Progress through a paged book: the page you are on counts as reached, so 1/100 is 1%. */
fun pageProgress(page: Int, totalPages: Int): Float =
    if (totalPages <= 0) 0f else ((page + 1).toFloat() / totalPages).coerceIn(0f, 1f)

/** "3", or "2.5" for a book between two others. */
fun formatSeriesIndex(index: Float): String =
    if (index % 1f == 0f) index.toInt().toString() else index.toString()

/** "Discworld 3", or the series alone when the book has no number in it. */
fun seriesLabel(series: String?, index: Float?): String? =
    series?.let { s -> index?.let { "$s ${formatSeriesIndex(it)}" } ?: s }

val Book.seriesLabel: String? get() = seriesLabel(series, seriesIndex)
