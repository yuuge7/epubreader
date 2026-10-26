package com.ebookreader.domain.model

/**
 * One file of the book's reading order: what the reader shows as a page of text and what
 * positions, bookmarks and highlights are stored against. Its [title] is the contents
 * entry that leads to it, or the one before it for a file the contents leave out.
 */
data class EpubChapter(
    val id: String,
    val title: String,
    /** The file, as a path inside the book. */
    val href: String,
    val index: Int,
)

/**
 * An entry of the book's own table of contents. Entries nest (parts, chapters, sections)
 * and can point anywhere inside a chapter file: [fragment] is the element id to scroll to.
 */
data class TocEntry(
    val title: String,
    val chapterIndex: Int,
    val fragment: String? = null,
    val children: List<TocEntry> = emptyList(),
)

/** The entries of a contents tree in reading order, each with how deep it sits. */
fun List<TocEntry>.flatten(depth: Int = 0): List<Pair<TocEntry, Int>> =
    flatMap { listOf(it to depth) + it.children.flatten(depth + 1) }

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    /** The book's contents, nested; empty when it has none. */
    val toc: List<TocEntry> = emptyList(),
    val coverImagePath: String? = null,
    /** The publisher's blurb, as plain text. */
    val description: String? = null,
    /** BCP 47 language tag from the package metadata, if the book declares one. */
    val language: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null
)
