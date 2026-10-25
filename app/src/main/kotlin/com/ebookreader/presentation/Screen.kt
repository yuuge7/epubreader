package com.ebookreader.presentation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Library : Screen("library")
    object PdfReader : Screen("pdf_reader/{bookId}") {
        fun createRoute(bookId: Long) = "pdf_reader/$bookId"
    }
    /** [chapter] and [query] open the book at a search hit, with the words marked. */
    object EpubReader : Screen("epub_reader/{bookId}?chapter={chapter}&q={q}") {
        fun createRoute(bookId: Long, chapter: Int = -1, query: String? = null) =
            "epub_reader/$bookId?chapter=$chapter&q=${android.net.Uri.encode(query.orEmpty())}"
    }
    object ComicReader : Screen("comic_reader/{bookId}") {
        fun createRoute(bookId: Long) = "comic_reader/$bookId"
    }
    object BookStats : Screen("book_stats/{bookId}") {
        fun createRoute(bookId: Long) = "book_stats/$bookId"
    }
    object Catalog : Screen("catalog")
    object Settings : Screen("settings")
    object Stats : Screen("stats")
    object MonthlyHistory : Screen("monthly_history")
    object YearlyHistory : Screen("yearly_history")
    object SessionHistory : Screen("session_history")
}
