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

/** Faces a reflowable book can be set in. [PUBLISHER] keeps the book's own fonts. */
enum class ReaderFont { PUBLISHER, LITERATA, BITTER, ATKINSON, OPENDYSLEXIC, INTER }

/**
 * How the text of a reflowable book is set. Every value overrides what the book's own
 * stylesheet asks for, except [ReaderFont.PUBLISHER].
 */
data class ReaderTypography(
    val font: ReaderFont = ReaderFont.LITERATA,
    /** CSS pixels at a system font scale of 1; the reader multiplies in the scale. */
    val fontSize: Float = 18f,
    /** Unitless multiple of the font size. */
    val lineHeight: Float = 1.6f,
    /** Space on either side of the text column, CSS pixels. */
    val margin: Float = 20f,
    val justify: Boolean = false,
    val hyphenate: Boolean = false,
) {
    /** Clamped into the ranges the reader offers, so a corrupt stored value cannot stick. */
    fun sanitized() = copy(
        fontSize = fontSize.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX),
        lineHeight = lineHeight.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX),
        margin = margin.coerceIn(MARGIN_MIN, MARGIN_MAX),
    )

    companion object {
        const val FONT_SIZE_MIN = 12f
        const val FONT_SIZE_MAX = 32f
        const val FONT_SIZE_STEP = 1f
        const val LINE_HEIGHT_MIN = 1.2f
        const val LINE_HEIGHT_MAX = 2.2f
        const val LINE_HEIGHT_STEP = 0.1f
        const val MARGIN_MIN = 0f
        const val MARGIN_MAX = 48f
        const val MARGIN_STEP = 4f
    }
}

/** Screen light while reading. Kept to the reader; the rest of the app is left alone. */
data class ReaderLight(
    /** 0..1 of the panel's range, or [SYSTEM] to leave brightness to the system. */
    val brightness: Float = SYSTEM,
    /** 0 = untouched, 1 = the strongest amber filter the reader draws. */
    val warmth: Float = 0f,
) {
    val followsSystem: Boolean get() = brightness < 0f

    fun sanitized() = copy(
        brightness = if (brightness < 0f) SYSTEM else brightness.coerceIn(MIN_BRIGHTNESS, 1f),
        warmth = warmth.coerceIn(0f, 1f),
    )

    companion object {
        const val SYSTEM = -1f
        /** A panel set to zero goes black on some devices, which looks like a crash. */
        const val MIN_BRIGHTNESS = 0.02f
    }
}

/** Targets the reader sets themselves; 0 means no target. */
data class ReadingGoals(
    val minutesPerDay: Int = 0,
    val booksPerYear: Int = 0,
    /** A nudge in the evening when today's minutes are not in yet. */
    val reminder: Boolean = false,
    /** When the nudge comes, in minutes after midnight. */
    val reminderAt: Int = 21 * 60,
) {
    fun sanitized() = copy(
        minutesPerDay = minutesPerDay.coerceIn(0, 24 * 60),
        booksPerYear = booksPerYear.coerceIn(0, 1000),
        reminderAt = reminderAt.coerceIn(0, 24 * 60 - 1),
    )
}

/** An online book catalogue in the OPDS format, by the address of its first page. */
data class OpdsCatalog(val title: String, val url: String)

data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val readingTheme: ReadingTheme = ReadingTheme.AUTO,
    val typography: ReaderTypography = ReaderTypography(),
    val readerLight: ReaderLight = ReaderLight(),
    val goals: ReadingGoals = ReadingGoals(),
    /** Speech rate for reading aloud, 1 = the engine's normal pace. */
    val readAloudRate: Float = 1f,
    /** A folder the reader chose (content:// tree URI) that their devices sync, or null. */
    val syncFolder: String? = null,
    val keepScreenOn: Boolean = false,
    /** Comics turn right to left, as manga is read. */
    val comicRightToLeft: Boolean = false,
    /** OPDS catalogues the reader added, after the built-in ones. */
    val catalogs: List<OpdsCatalog> = emptyList(),
    val sortOption: SortOption = SortOption.DATE_ADDED,
    val filterOption: FilterOption = FilterOption.ALL,
    val isGridView: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val readerScrollDirection: ScrollDirection = ScrollDirection.HORIZONTAL
)
