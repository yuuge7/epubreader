package com.ebookreader.data.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ebookreader.data.epub.EpubParser
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.BookTextSearch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** The kinds of file the library takes, as found by extension and then by MIME type. */
enum class SourceKind(val extensions: List<String>, val mimeTypes: List<String>) {
    EPUB(listOf("epub"), listOf("application/epub+zip")),
    PDF(listOf("pdf"), listOf("application/pdf")),
    CBZ(listOf("cbz"), listOf("application/vnd.comicbook+zip", "application/x-cbz")),
    CBR(listOf("cbr"), listOf("application/vnd.comicbook-rar", "application/x-cbr")),
    TXT(listOf("txt"), listOf("text/plain")),
    MARKDOWN(listOf("md", "markdown"), listOf("text/markdown", "text/x-markdown")),
    FB2(listOf("fb2"), listOf("application/x-fictionbook+xml", "application/x-fictionbook")),
    FB2_ZIP(listOf("fb2.zip", "fbz"), emptyList()),
    MOBI(listOf("mobi", "prc"), listOf("application/x-mobipocket-ebook")),
    AZW3(listOf("azw3", "azw", "kf8"), listOf("application/vnd.amazon.mobi8-ebook", "application/vnd.amazon.ebook"));

    companion object {
        /** Every MIME type the import picker should offer. */
        val pickerMimeTypes: Array<String> =
            (entries.flatMap { it.mimeTypes } + "application/octet-stream" + "application/zip").distinct().toTypedArray()

        fun detect(fileName: String, mimeType: String?): SourceKind? {
            val lower = fileName.lowercase()
            // Longest extension first, so "x.fb2.zip" is FB2_ZIP and not a plain zip.
            entries.flatMap { kind -> kind.extensions.map { it to kind } }
                .sortedByDescending { it.first.length }
                .firstOrNull { (ext, _) -> lower.endsWith(".$ext") }
                ?.let { return it.second }
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
            return entries.firstOrNull { mime in it.mimeTypes }
        }
    }
}

/**
 * A file ready for the library and what was found in it: a converted book, or one the
 * readers open as it is.
 */
data class ConvertedBook(
    val file: File,
    val format: BookFormat,
    val title: String?,
    val author: String?,
    val coverPath: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    /** Pages, where the format has them before it is opened (comics). */
    val totalPages: Int = 0,
)

/**
 * Brings a file into the library: copies it into the app's own storage, converts it if
 * it is in a format the readers do not open directly, reads its title, author, cover and
 * series, and adds it to the search index in the background.
 */
@Singleton
class BookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val epubParser: EpubParser,
    private val coverMaker: CoverMaker,
    private val converters: FormatConverters,
    private val textSearch: BookTextSearch,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    sealed interface Result {
        /** The book as it is in the library afterwards, new or already there; null on failure. */
        val book: Book? get() = null
        /** What happened, in words for the reader. */
        val message: String

        data class Imported(override val book: Book) : Result {
            override val message get() = "Added “${book.title}”"
        }
        data class AlreadyImported(override val book: Book) : Result {
            override val message get() = "Already in your library"
        }
        data class Unsupported(val fileName: String) : Result {
            override val message get() = "$fileName is not a format this app reads"
        }
        data class Failed(override val message: String) : Result
    }

    private val booksDir: File get() = File(context.filesDir, "books").also { it.mkdirs() }

    /** Imports from a content or file URI, as given by the picker or another app. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = displayName(uri)
        val kind = SourceKind.detect(fileName, runCatching { resolver.getType(uri) }.getOrNull())
            ?: return@withContext Result.Unsupported(fileName)
        val totalBytes = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
        // A provider refuses with an exception, not a null: a grant that has expired, a
        // file that went away.
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return@withContext Result.Failed("Could not read $fileName")
        input.use { importStream(it, fileName, kind, totalBytes, onProgress) }
    }

    /** Imports a file already on the device, a catalogue download for example. */
    suspend fun import(file: File, fileName: String, kind: SourceKind): Result = withContext(Dispatchers.IO) {
        file.inputStream().use { importStream(it, fileName, kind, file.length(), {}) }
    }

    private suspend fun importStream(
        input: InputStream,
        rawName: String,
        kind: SourceKind,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ): Result {
        val fileName = rawName.replace(Regex("""[/\\]"""), "_")
        val direct = kind == SourceKind.EPUB || kind == SourceKind.PDF || kind == SourceKind.CBZ
        val finalName = if (direct) fileName else converters.outputName(fileName, kind)
        // A file that comes as it is can be told from another of the same name by its size.
        val dest = uniqueDestination(finalName, expectedSize = if (direct) totalBytes else -1L)
        bookRepository.getBookByFilePath(dest.absolutePath)?.let { return Result.AlreadyImported(it) }

        val staging = if (direct) dest else File(context.cacheDir, "import_${System.nanoTime()}_$fileName")
        try {
            copyWithProgress(input, staging, totalBytes, onProgress)
            val found = if (direct) describe(dest, kind) else converters.convert(kind, staging, fileName, dest)
            val book = Book(
                title = found.title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast("."),
                author = found.author?.takeIf { it.isNotBlank() } ?: "Unknown Author",
                filePath = found.file.absolutePath,
                format = found.format,
                coverPath = found.coverPath,
                totalPages = found.totalPages,
                fileSize = found.file.length(),
                series = found.series,
                seriesIndex = found.seriesIndex,
                sourceFormat = if (direct) null else kind.label,
            )
            val id = bookRepository.insertBook(book)
            if (book.format == BookFormat.EPUB) appScope.launch { textSearch.indexBook(id) }
            return Result.Imported(book.copy(id = id))
        } catch (e: CancellationException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            return Result.Failed(e.message ?: "Could not import $fileName")
        } finally {
            if (staging != dest) staging.delete()
        }
    }

    /** Title, author, cover and series of a file the readers open as it is. */
    private fun describe(file: File, kind: SourceKind): ConvertedBook = when (kind) {
        SourceKind.EPUB -> {
            val meta = epubParser.readMetadata(file.absolutePath)
                ?: throw IllegalArgumentException("This EPUB could not be read")
            ConvertedBook(
                file = file,
                format = BookFormat.EPUB,
                title = meta.title.takeIf { it != "Unknown Title" },
                author = meta.author,
                coverPath = meta.coverImagePath?.let { coverMaker.keepCover(it, file.name) },
                series = meta.series,
                seriesIndex = meta.seriesIndex,
            )
        }
        SourceKind.PDF -> ConvertedBook(file, BookFormat.PDF, title = null, author = null, coverPath = coverMaker.pdfCover(file))
        SourceKind.CBZ -> converters.describeComic(file)
        else -> error("Not a direct format: $kind")
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
    }

    /**
     * Two unrelated books can share a file name. Reuse a path only when it holds the same
     * book already in the library (the same size, when [expectedSize] is known); otherwise
     * pick a free name instead of overwriting.
     */
    private suspend fun uniqueDestination(fileName: String, expectedSize: Long): File {
        val base = fileName.substringBeforeLast(".", fileName)
        val ext = fileName.substringAfterLast(".", "")
        for (index in 1 until 1000) {
            val name = if (index == 1) fileName else if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            val candidate = File(booksDir, name)
            if (!candidate.exists()) return candidate
            val inLibrary = bookRepository.getBookByFilePath(candidate.absolutePath) != null
            if (inLibrary && (expectedSize <= 0 || candidate.length() == expectedSize)) return candidate
        }
        return File(booksDir, fileName)
    }
}

/** The badge label for a converted book's original format. */
val SourceKind.label: String
    get() = when (this) {
        SourceKind.MARKDOWN -> "MD"
        SourceKind.FB2_ZIP -> "FB2"
        else -> name
    }
