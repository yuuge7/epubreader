package com.ebookreader.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReaderFont
import com.ebookreader.domain.model.ReaderLight
import com.ebookreader.domain.model.ReaderTypography
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

    @Test
    fun `a font size chosen before the reader had its own sheet carries over`() {
        val s = settingsFrom(preferencesOf(SettingsKeys.FONT_SIZE to 22f))
        assertEquals(22f, s.typography.fontSize)
        // Everything the old app never stored gets the new defaults.
        assertEquals(ReaderTypography().copy(fontSize = 22f), s.typography)
        assertEquals(ReaderLight(), s.readerLight)
    }

    @Test
    fun `stored typography and light are read back`() {
        val s = settingsFrom(
            preferencesOf(
                SettingsKeys.READER_FONT to "OPENDYSLEXIC",
                SettingsKeys.FONT_SIZE to 20f,
                SettingsKeys.LINE_HEIGHT to 1.9f,
                SettingsKeys.PAGE_MARGIN to 8f,
                SettingsKeys.TEXT_JUSTIFY to true,
                SettingsKeys.HYPHENATE to true,
                SettingsKeys.READER_BRIGHTNESS to 0.4f,
                SettingsKeys.READER_WARMTH to 0.25f,
            )
        )
        assertEquals(ReaderTypography(ReaderFont.OPENDYSLEXIC, 20f, 1.9f, 8f, justify = true, hyphenate = true), s.typography)
        assertEquals(ReaderLight(brightness = 0.4f, warmth = 0.25f), s.readerLight)
        assertEquals(false, s.readerLight.followsSystem)
    }

    @Test
    fun `out of range reader values are clamped rather than kept`() {
        val s = settingsFrom(
            preferencesOf(
                SettingsKeys.READER_FONT to "WINGDINGS",
                SettingsKeys.FONT_SIZE to 400f,
                SettingsKeys.LINE_HEIGHT to 0f,
                SettingsKeys.PAGE_MARGIN to -10f,
                SettingsKeys.READER_BRIGHTNESS to 7f,
                SettingsKeys.READER_WARMTH to -1f,
            )
        )
        assertEquals(ReaderTypography().font, s.typography.font)
        assertEquals(ReaderTypography.FONT_SIZE_MAX, s.typography.fontSize)
        assertEquals(ReaderTypography.LINE_HEIGHT_MIN, s.typography.lineHeight)
        assertEquals(ReaderTypography.MARGIN_MIN, s.typography.margin)
        assertEquals(1f, s.readerLight.brightness)
        assertEquals(0f, s.readerLight.warmth)
    }

    @Test
    fun `any negative brightness means the system's`() {
        val s = settingsFrom(preferencesOf(SettingsKeys.READER_BRIGHTNESS to -0.5f))
        assertEquals(true, s.readerLight.followsSystem)
        assertEquals(ReaderLight.SYSTEM, s.readerLight.brightness)
    }

    @Test
    fun `an edit touches only the keys that changed`() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf(
            SettingsKeys.SYNC_FOLDER to "content://tree/books",
        )
        prefs.applyChanges(settingsFrom(prefs))
        val written = prefs.asMap().toMap()

        val changed = settingsFrom(prefs).copy(sortOption = SortOption.PROGRESS, syncFolder = null)
        prefs.applyChanges(changed)
        val after = prefs.asMap()

        assertEquals("PROGRESS", after[SettingsKeys.SORT_OPTION])
        // The folder setting is gone rather than written empty.
        assertEquals(false, after.containsKey(SettingsKeys.SYNC_FOLDER))
        // Everything else is as it was.
        written.forEach { (key, value) ->
            if (key != SettingsKeys.SORT_OPTION && key != SettingsKeys.SYNC_FOLDER) assertEquals(value, after[key])
        }
    }

    @Test
    fun `a library sorted before the direction existed keeps its order`() {
        assertEquals(false, settingsFrom(preferencesOf(SettingsKeys.SORT_OPTION to "TITLE")).sortDescending)
        assertEquals(true, settingsFrom(preferencesOf(SettingsKeys.SORT_OPTION to "LAST_READ")).sortDescending)
    }
}
