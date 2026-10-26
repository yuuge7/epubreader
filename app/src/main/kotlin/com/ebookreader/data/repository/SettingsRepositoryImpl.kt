package com.ebookreader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    override fun getSettings(): Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::settingsFrom)
        // Every write rewrites all keys; an edit that changes nothing must not re-emit
        // and recompose the open reader.
        .distinctUntilChanged()

    /**
     * Only the keys whose value changed are written. Writing all of them on every change
     * made each tap rewrite the whole file, and handed every collector a "new" snapshot.
     */
    override suspend fun editSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val before = settingsFrom(prefs)
            val after = transform(before)
            if (after != before) prefs.applyChanges(after)
        }
    }

    override suspend fun setOnboardingCompleted() {
        dataStore.edit { prefs -> prefs[SettingsKeys.ONBOARDING_COMPLETED] = true }
    }

    override suspend fun isOnboardingCompleted(): Boolean =
        dataStore.data.first()[SettingsKeys.ONBOARDING_COMPLETED] ?: false
}

internal object SettingsKeys {
    val THEME = stringPreferencesKey("theme")
    val READING_THEME = stringPreferencesKey("reading_theme")
    val FONT_SIZE = floatPreferencesKey("font_size")
    val READER_FONT = stringPreferencesKey("reader_font")
    val LINE_HEIGHT = floatPreferencesKey("line_height")
    val PAGE_MARGIN = floatPreferencesKey("page_margin")
    val TEXT_JUSTIFY = booleanPreferencesKey("text_justify")
    val HYPHENATE = booleanPreferencesKey("hyphenate")
    val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
    val READER_WARMTH = floatPreferencesKey("reader_warmth")
    val GOAL_MINUTES_DAY = intPreferencesKey("goal_minutes_day")
    val GOAL_BOOKS_YEAR = intPreferencesKey("goal_books_year")
    val REMINDER = booleanPreferencesKey("reminder_enabled")
    val REMINDER_AT = intPreferencesKey("reminder_at")
    val READ_ALOUD_RATE = floatPreferencesKey("read_aloud_rate")
    val SYNC_FOLDER = stringPreferencesKey("sync_folder")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val COMIC_RIGHT_TO_LEFT = booleanPreferencesKey("comic_right_to_left")
    /** One catalogue a line, its title and address separated by a tab. */
    val CATALOGS = stringPreferencesKey("opds_catalogs")
    val SORT_OPTION = stringPreferencesKey("sort_option")
    val FILTER_OPTION = stringPreferencesKey("filter_option")
    val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val READER_SCROLL_DIRECTION = stringPreferencesKey("reader_scroll_direction")
    val PAGED = booleanPreferencesKey("reader_paged")
    val TWO_COLUMNS = booleanPreferencesKey("reader_two_columns")
    val SORT_DESCENDING = booleanPreferencesKey("sort_descending")
    val AUTO_SCROLL_SPEED = intPreferencesKey("auto_scroll_speed")
    val READING_FOCUS = stringPreferencesKey("reading_focus")
    val PDF_NIGHT = booleanPreferencesKey("pdf_night")
    val PDF_CROP = booleanPreferencesKey("pdf_crop_margins")
}

/**
 * Sepia used to be an app theme and recoloured the whole app. It is a reading theme now,
 * so a stored "SEPIA" becomes a light app with sepia pages: what that user was looking
 * at in the reader before, without the library turning brown.
 */
private const val LEGACY_SEPIA_APP_THEME = "SEPIA"

internal fun settingsFrom(prefs: Preferences): AppSettings {
    val storedTheme = prefs[SettingsKeys.THEME]
    val legacySepia = storedTheme == LEGACY_SEPIA_APP_THEME
    val type = ReaderTypography()
    val light = ReaderLight()
    val sort = enumOr(prefs[SettingsKeys.SORT_OPTION], SortOption.DATE_ADDED)
    return AppSettings(
        theme = if (legacySepia) AppTheme.LIGHT else enumOr(storedTheme, AppTheme.SYSTEM),
        readingTheme = enumOr(
            prefs[SettingsKeys.READING_THEME],
            if (legacySepia) ReadingTheme.SEPIA else ReadingTheme.AUTO
        ),
        typography = ReaderTypography(
            font = enumOr(prefs[SettingsKeys.READER_FONT], type.font),
            fontSize = prefs[SettingsKeys.FONT_SIZE] ?: type.fontSize,
            lineHeight = prefs[SettingsKeys.LINE_HEIGHT] ?: type.lineHeight,
            margin = prefs[SettingsKeys.PAGE_MARGIN] ?: type.margin,
            justify = prefs[SettingsKeys.TEXT_JUSTIFY] ?: type.justify,
            hyphenate = prefs[SettingsKeys.HYPHENATE] ?: type.hyphenate,
            paged = prefs[SettingsKeys.PAGED] ?: type.paged,
            twoColumns = prefs[SettingsKeys.TWO_COLUMNS] ?: type.twoColumns,
        ).sanitized(),
        readerLight = ReaderLight(
            brightness = prefs[SettingsKeys.READER_BRIGHTNESS] ?: light.brightness,
            warmth = prefs[SettingsKeys.READER_WARMTH] ?: light.warmth,
        ).sanitized(),
        goals = ReadingGoals(
            minutesPerDay = prefs[SettingsKeys.GOAL_MINUTES_DAY] ?: 0,
            booksPerYear = prefs[SettingsKeys.GOAL_BOOKS_YEAR] ?: 0,
            reminder = prefs[SettingsKeys.REMINDER] ?: false,
            reminderAt = prefs[SettingsKeys.REMINDER_AT] ?: ReadingGoals().reminderAt,
        ).sanitized(),
        readAloudRate = (prefs[SettingsKeys.READ_ALOUD_RATE] ?: 1f).coerceIn(0.5f, 3f),
        syncFolder = prefs[SettingsKeys.SYNC_FOLDER],
        keepScreenOn = prefs[SettingsKeys.KEEP_SCREEN_ON] ?: false,
        comicRightToLeft = prefs[SettingsKeys.COMIC_RIGHT_TO_LEFT] ?: false,
        catalogs = prefs[SettingsKeys.CATALOGS].orEmpty().lines().mapNotNull { line ->
            val (title, url) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            OpdsCatalog(title, url)
        },
        sortOption = sort,
        // A library sorted before the direction existed keeps the order it had.
        sortDescending = prefs[SettingsKeys.SORT_DESCENDING] ?: sort.descendingByDefault,
        filterOption = enumOr(prefs[SettingsKeys.FILTER_OPTION], FilterOption.ALL),
        autoScrollSpeed = (prefs[SettingsKeys.AUTO_SCROLL_SPEED] ?: AutoScroll.DEFAULT_SPEED)
            .coerceIn(AutoScroll.MIN_SPEED, AutoScroll.MAX_SPEED),
        readingFocus = enumOr(prefs[SettingsKeys.READING_FOCUS], ReadingFocus.OFF),
        pdfNight = prefs[SettingsKeys.PDF_NIGHT] ?: false,
        pdfCropMargins = prefs[SettingsKeys.PDF_CROP] ?: false,
        isGridView = prefs[SettingsKeys.IS_GRID_VIEW] ?: true,
        onboardingCompleted = prefs[SettingsKeys.ONBOARDING_COMPLETED] ?: false,
        readerScrollDirection = enumOr(
            prefs[SettingsKeys.READER_SCROLL_DIRECTION], ScrollDirection.HORIZONTAL
        )
    )
}

/** Writes the keys of [settings] that differ from what is stored, and only those. */
internal fun MutablePreferences.applyChanges(settings: AppSettings) {
    val wanted = mutablePreferencesOf().also { it.write(settings) }.asMap()
    for ((key, value) in wanted) {
        @Suppress("UNCHECKED_CAST")
        if (this[key] != value) this[key as Preferences.Key<Any>] = value
    }
    // A setting that is now unset (the sync folder) is a key to take away.
    for (key in OptionalKeys) if (key !in wanted && contains(key)) remove(key)
}

/** Keys written only while their setting has a value. */
private val OptionalKeys = listOf(SettingsKeys.SYNC_FOLDER)

private fun MutablePreferences.write(settings: AppSettings) {
    this[SettingsKeys.THEME] = settings.theme.name
    this[SettingsKeys.READING_THEME] = settings.readingTheme.name
    val type = settings.typography
    this[SettingsKeys.READER_FONT] = type.font.name
    this[SettingsKeys.FONT_SIZE] = type.fontSize
    this[SettingsKeys.LINE_HEIGHT] = type.lineHeight
    this[SettingsKeys.PAGE_MARGIN] = type.margin
    this[SettingsKeys.TEXT_JUSTIFY] = type.justify
    this[SettingsKeys.HYPHENATE] = type.hyphenate
    this[SettingsKeys.PAGED] = type.paged
    this[SettingsKeys.TWO_COLUMNS] = type.twoColumns
    this[SettingsKeys.READER_BRIGHTNESS] = settings.readerLight.brightness
    this[SettingsKeys.READER_WARMTH] = settings.readerLight.warmth
    this[SettingsKeys.GOAL_MINUTES_DAY] = settings.goals.minutesPerDay
    this[SettingsKeys.GOAL_BOOKS_YEAR] = settings.goals.booksPerYear
    this[SettingsKeys.REMINDER] = settings.goals.reminder
    this[SettingsKeys.REMINDER_AT] = settings.goals.reminderAt
    this[SettingsKeys.READ_ALOUD_RATE] = settings.readAloudRate
    settings.syncFolder?.let { this[SettingsKeys.SYNC_FOLDER] = it } ?: remove(SettingsKeys.SYNC_FOLDER)
    this[SettingsKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
    this[SettingsKeys.COMIC_RIGHT_TO_LEFT] = settings.comicRightToLeft
    this[SettingsKeys.CATALOGS] = settings.catalogs.joinToString("\n") { c ->
        c.title.replace(Regex("""\s"""), " ") + "\t" + c.url.trim()
    }
    this[SettingsKeys.SORT_OPTION] = settings.sortOption.name
    this[SettingsKeys.SORT_DESCENDING] = settings.sortDescending
    this[SettingsKeys.FILTER_OPTION] = settings.filterOption.name
    this[SettingsKeys.IS_GRID_VIEW] = settings.isGridView
    this[SettingsKeys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
    this[SettingsKeys.READER_SCROLL_DIRECTION] = settings.readerScrollDirection.name
    this[SettingsKeys.AUTO_SCROLL_SPEED] = settings.autoScrollSpeed
    this[SettingsKeys.READING_FOCUS] = settings.readingFocus.name
    this[SettingsKeys.PDF_NIGHT] = settings.pdfNight
    this[SettingsKeys.PDF_CROP] = settings.pdfCropMargins
}

/**
 * An enum value that no longer exists must fall back, not throw: valueOf on a renamed
 * constant would crash the app on every launch, before the user could change anything.
 */
private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
