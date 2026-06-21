package com.ebookreader.domain.repository

import com.ebookreader.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettings(): Flow<AppSettings>
    suspend fun updateSettings(settings: AppSettings)
    suspend fun setOnboardingCompleted()
    suspend fun isOnboardingCompleted(): Boolean
}
