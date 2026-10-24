package com.ebookreader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.ebookreader.domain.model.*
import com.ebookreader.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    override suspend fun updateSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.THEME] = settings.theme.name
            prefs[SettingsKeys.READING_THEME] = settings.readingTheme.name
            prefs[SettingsKeys.FONT_SIZE] = settings.fontSize
            prefs[SettingsKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[SettingsKeys.SORT_OPTION] = settings.sortOption.name
            prefs[SettingsKeys.FILTER_OPTION] = settings.filterOption.name
            prefs[SettingsKeys.IS_GRID_VIEW] = settings.isGridView
            prefs[SettingsKeys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
            prefs[SettingsKeys.READER_SCROLL_DIRECTION] = settings.readerScrollDirection.name
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
    return AppSettings(
        theme = if (legacySepia) AppTheme.LIGHT else enumOr(storedTheme, AppTheme.SYSTEM),
        readingTheme = enumOr(
            prefs[SettingsKeys.READING_THEME],
            if (legacySepia) ReadingTheme.SEPIA else ReadingTheme.AUTO
        ),
        fontSize = prefs[SettingsKeys.FONT_SIZE] ?: 16f,
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

/**
 * An enum value that no longer exists must fall back, not throw: valueOf on a renamed
 * constant would crash the app on every launch, before the user could change anything.
 */
private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
