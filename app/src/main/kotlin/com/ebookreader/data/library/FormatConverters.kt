package com.ebookreader.data.library

import com.ebookreader.data.convert.ComicArchives
import com.ebookreader.data.convert.EpubWriter
import com.ebookreader.data.convert.Fb2Converter
import com.ebookreader.data.convert.GeneratedBook
import com.ebookreader.data.convert.MarkdownConverter
import com.ebookreader.data.convert.MobiConverter
import com.ebookreader.data.convert.TxtConverter
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.seriesLabel
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The converters, by source format. Each one turns a file into something a reader in
 * this app opens: EPUB for the text formats, CBZ for comics.
 */
@Singleton
class FormatConverters @Inject constructor(
    private val coverMaker: CoverMaker,
) {
    /**
     * The name the converted file is stored under. The source extension stays in it:
     * "tm.txt" and "tm.mobi" are different books (or editions) and must not both
     * become "tm.epub", which the importer would take for one already imported.
     */
    fun outputName(fileName: String, kind: SourceKind): String = when (kind) {
        SourceKind.CBR -> "${baseName(fileName)}.cbz"
        else -> "$fileName.epub"
    }

    fun convert(kind: SourceKind, source: File, displayName: String, output: File): ConvertedBook {
        val fallback = baseName(displayName)
        return when (kind) {
            SourceKind.TXT -> epub(TxtConverter.convert(source.readBytes(), fallback), output)
            SourceKind.MARKDOWN -> epub(MarkdownConverter.convert(source.readBytes(), fallback), output)
            SourceKind.FB2 -> epub(Fb2Converter.convert(source.readBytes(), fallback), output)
            SourceKind.FB2_ZIP -> epub(Fb2Converter.convertZip(source.readBytes(), fallback), output)
            SourceKind.MOBI, SourceKind.AZW3 -> epub(MobiConverter.convert(source.readBytes(), fallback), output)
            SourceKind.CBR -> {
                ComicArchives.cbrToCbz(source, output)
                describeComic(output)
            }
            SourceKind.EPUB, SourceKind.PDF, SourceKind.CBZ ->
                throw IllegalArgumentException("${kind.label} needs no conversion")
        }
    }

    /** Page count, ComicInfo metadata and a cover from the first page of a CBZ. */
    fun describeComic(file: File): ConvertedBook = ZipFile(file).use { zip ->
        val pages = ComicArchives.pages(zip)
        if (pages.isEmpty()) throw IllegalArgumentException("There are no pages in this comic")
        val meta = ComicArchives.metadata(zip)
        val cover = zip.getInputStream(pages.first()).use { coverMaker.imageCover(it.readBytes(), file.name) }
        ConvertedBook(
            file = file,
            format = BookFormat.COMIC,
            title = meta.title ?: seriesLabel(meta.series, meta.number),
            author = meta.writer,
            coverPath = cover,
            series = meta.series,
            seriesIndex = meta.number,
            totalPages = pages.size,
        )
    }

    private fun epub(book: GeneratedBook, output: File): ConvertedBook {
        if (book.chapters.all { it.body.isBlank() }) throw IllegalArgumentException("No readable text in this book")
        EpubWriter.write(book, output)
        val cover = book.coverPath?.let { path -> book.resources.firstOrNull { it.path == path } }
            ?.let { coverMaker.imageCover(it.bytes, output.name) }
        return ConvertedBook(
            file = output, format = BookFormat.EPUB,
            title = book.title, author = book.author, coverPath = cover,
            series = book.series, seriesIndex = book.seriesIndex,
        )
    }

    private fun baseName(fileName: String): String =
        if (fileName.endsWith(".fb2.zip", ignoreCase = true)) fileName.dropLast(8)
        else fileName.substringBeforeLast(".")
}
