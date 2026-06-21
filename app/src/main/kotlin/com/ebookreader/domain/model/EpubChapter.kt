package com.ebookreader.domain.model

data class EpubChapter(
    val id: String,
    val title: String,
    val href: String,
    val index: Int,
    val subChapters: List<EpubChapter> = emptyList()
)

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val coverImagePath: String? = null,
    val description: String? = null
)
