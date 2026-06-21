package com.ebookreader.presentation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Library : Screen("library")
    object PdfReader : Screen("pdf_reader/{bookId}") {
        fun createRoute(bookId: Long) = "pdf_reader/$bookId"
    }
    object EpubReader : Screen("epub_reader/{bookId}") {
        fun createRoute(bookId: Long) = "epub_reader/$bookId"
    }
    object Settings : Screen("settings")
    object Stats : Screen("stats")
    object MonthlyHistory : Screen("monthly_history")
    object YearlyHistory : Screen("yearly_history")
}
