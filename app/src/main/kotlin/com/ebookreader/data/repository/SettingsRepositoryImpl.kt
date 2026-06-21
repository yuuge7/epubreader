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

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SORT_OPTION = stringPreferencesKey("sort_option")
        val FILTER_OPTION = stringPreferencesKey("filter_option")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val READER_SCROLL_DIRECTION = stringPreferencesKey("reader_scroll_direction")
    }

    override fun getSettings(): Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                theme = AppTheme.valueOf(prefs[Keys.THEME] ?: AppTheme.SYSTEM.name),
                fontSize = prefs[Keys.FONT_SIZE] ?: 16f,
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                sortOption = SortOption.valueOf(prefs[Keys.SORT_OPTION] ?: SortOption.DATE_ADDED.name),
                filterOption = FilterOption.valueOf(prefs[Keys.FILTER_OPTION] ?: FilterOption.ALL.name),
                isGridView = prefs[Keys.IS_GRID_VIEW] ?: true,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                readerScrollDirection = ScrollDirection.valueOf(
                    prefs[Keys.READER_SCROLL_DIRECTION] ?: ScrollDirection.HORIZONTAL.name
                )
            )
        }

    override suspend fun updateSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME] = settings.theme.name
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.SORT_OPTION] = settings.sortOption.name
            prefs[Keys.FILTER_OPTION] = settings.filterOption.name
            prefs[Keys.IS_GRID_VIEW] = settings.isGridView
            prefs[Keys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
            prefs[Keys.READER_SCROLL_DIRECTION] = settings.readerScrollDirection.name
        }
    }

    override suspend fun setOnboardingCompleted() {
        dataStore.edit { prefs -> prefs[Keys.ONBOARDING_COMPLETED] = true }
    }

    override suspend fun isOnboardingCompleted(): Boolean =
        dataStore.data.first()[Keys.ONBOARDING_COMPLETED] ?: false
}
