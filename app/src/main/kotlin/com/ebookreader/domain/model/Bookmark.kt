package com.ebookreader.domain.model

import java.util.Date

/**
 * A place marked in a book. In an EPUB [page] is the chapter and the place inside it is
 * [textOffset] (characters into the chapter's text) when known, else [scrollFraction];
 * several bookmarks can sit in one long chapter. In a PDF [page] is the page.
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    val note: String = "",
    val dateCreated: Date = Date(),
    /** 0 = top of the chapter or page, 1 = bottom. */
    val scrollFraction: Float = 0f,
    /** Characters into the chapter's text where the bookmark is; -1 when not known. */
    val textOffset: Int = -1,
    /** The first words on the screen when it was made, to tell bookmarks apart. */
    val excerpt: String = "",
)
