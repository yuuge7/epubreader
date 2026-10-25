package com.ebookreader.domain.repository

import com.ebookreader.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettings(): Flow<AppSettings>

    /**
     * Applies [transform] to the settings as currently stored, atomically. This is the
     * only way to write them: whole-object writes built from a copy the caller held
     * would undo any change that landed in between, and quick changes from different
     * screens do land in between.
     */
    suspend fun editSettings(transform: (AppSettings) -> AppSettings)

    suspend fun setOnboardingCompleted()
    suspend fun isOnboardingCompleted(): Boolean
}
