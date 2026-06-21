package com.ebookreader.domain.model

import java.util.Date

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    val note: String = "",
    val dateCreated: Date = Date()
)
