package com.ebookreader.domain.model

/** Colours of the app's own chrome: library, settings, stats. */
enum class AppTheme { SYSTEM, LIGHT, DARK, BLACK }

/**
 * Colours of the page being read. Kept apart from [AppTheme] because sepia or slate is
 * a reading preference, not an identity for the whole app. [AUTO] follows the app:
 * Paper while the app is light, Night while it is dark, Black while it is black.
 */
enum class ReadingTheme { AUTO, PAPER, SEPIA, SLATE, NIGHT, BLACK }

enum class ScrollDirection { HORIZONTAL, VERTICAL }

data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val readingTheme: ReadingTheme = ReadingTheme.AUTO,
    val fontSize: Float = 16f,
    val keepScreenOn: Boolean = false,
    val sortOption: SortOption = SortOption.DATE_ADDED,
    val filterOption: FilterOption = FilterOption.ALL,
    val isGridView: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val readerScrollDirection: ScrollDirection = ScrollDirection.HORIZONTAL
)
