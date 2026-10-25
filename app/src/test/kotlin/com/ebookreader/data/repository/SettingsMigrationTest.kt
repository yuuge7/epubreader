package com.ebookreader.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReadingTheme
import com.ebookreader.domain.model.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationTest {

    @Test
    fun `fresh install gets system app theme and pages that follow it`() {
        val s = settingsFrom(emptyPreferences())
        assertEquals(AppTheme.SYSTEM, s.theme)
        assertEquals(ReadingTheme.AUTO, s.readingTheme)
    }

    @Test
    fun `legacy sepia app theme becomes light app with sepia pages`() {
        val s = settingsFrom(preferencesOf(SettingsKeys.THEME to "SEPIA"))
        assertEquals(AppTheme.LIGHT, s.theme)
        assertEquals(ReadingTheme.SEPIA, s.readingTheme)
    }

    @Test
    fun `an explicit reading theme wins over the legacy mapping`() {
        val s = settingsFrom(
            preferencesOf(
                SettingsKeys.THEME to "SEPIA",
                SettingsKeys.READING_THEME to "NIGHT",
            )
        )
        assertEquals(ReadingTheme.NIGHT, s.readingTheme)
    }

    @Test
    fun `existing app themes carry over unchanged`() {
        for (theme in listOf("SYSTEM", "LIGHT", "DARK")) {
            val s = settingsFrom(preferencesOf(SettingsKeys.THEME to theme))
            assertEquals(AppTheme.valueOf(theme), s.theme)
            assertEquals(ReadingTheme.AUTO, s.readingTheme)
        }
    }

    @Test
    fun `unknown stored values fall back instead of crashing on launch`() {
        val s = settingsFrom(
            preferencesOf(
                SettingsKeys.THEME to "NEON",
                SettingsKeys.READING_THEME to "PARCHMENT",
                SettingsKeys.SORT_OPTION to "RANDOM",
            )
        )
        assertEquals(AppTheme.SYSTEM, s.theme)
        assertEquals(ReadingTheme.AUTO, s.readingTheme)
        assertEquals(SortOption.DATE_ADDED, s.sortOption)
    }
}
