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

    override suspend fun editSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> prefs.write(transform(settingsFrom(prefs))) }
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
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SORT_OPTION = stringPreferencesKey("sort_option")
    val FILTER_OPTION = stringPreferencesKey("filter_option")
    val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val READER_SCROLL_DIRECTION = stringPreferencesKey("reader_scroll_direction")
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
        ).sanitized(),
        readerLight = ReaderLight(
            brightness = prefs[SettingsKeys.READER_BRIGHTNESS] ?: light.brightness,
            warmth = prefs[SettingsKeys.READER_WARMTH] ?: light.warmth,
        ).sanitized(),
        keepScreenOn = prefs[SettingsKeys.KEEP_SCREEN_ON] ?: false,
        sortOption = enumOr(prefs[SettingsKeys.SORT_OPTION], SortOption.DATE_ADDED),
        filterOption = enumOr(prefs[SettingsKeys.FILTER_OPTION], FilterOption.ALL),
        isGridView = prefs[SettingsKeys.IS_GRID_VIEW] ?: true,
        onboardingCompleted = prefs[SettingsKeys.ONBOARDING_COMPLETED] ?: false,
        readerScrollDirection = enumOr(
            prefs[SettingsKeys.READER_SCROLL_DIRECTION], ScrollDirection.HORIZONTAL
        )
    )
}

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
    this[SettingsKeys.READER_BRIGHTNESS] = settings.readerLight.brightness
    this[SettingsKeys.READER_WARMTH] = settings.readerLight.warmth
    this[SettingsKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
    this[SettingsKeys.SORT_OPTION] = settings.sortOption.name
    this[SettingsKeys.FILTER_OPTION] = settings.filterOption.name
    this[SettingsKeys.IS_GRID_VIEW] = settings.isGridView
    this[SettingsKeys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
    this[SettingsKeys.READER_SCROLL_DIRECTION] = settings.readerScrollDirection.name
}

/**
 * An enum value that no longer exists must fall back, not throw: valueOf on a renamed
 * constant would crash the app on every launch, before the user could change anything.
 */
private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
