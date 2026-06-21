package com.ebookreader.domain.model

enum class AppTheme { SYSTEM, LIGHT, DARK, SEPIA }
enum class ScrollDirection { HORIZONTAL, VERTICAL }

data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val fontSize: Float = 16f,
    val keepScreenOn: Boolean = false,
    val sortOption: SortOption = SortOption.DATE_ADDED,
    val filterOption: FilterOption = FilterOption.ALL,
    val isGridView: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val readerScrollDirection: ScrollDirection = ScrollDirection.HORIZONTAL
)
