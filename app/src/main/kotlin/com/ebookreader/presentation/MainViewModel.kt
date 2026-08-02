package com.ebookreader.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val startDestination: String? = null, // null = loading
    /** File passed in by another app via ACTION_VIEW, waiting to be imported. */
    val pendingImportUri: Uri? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getSettings()
                .collect { settings ->
                    val start = if (settings.onboardingCompleted)
                        Screen.Library.route
                    else
                        Screen.Onboarding.route

                    _uiState.update {
                        it.copy(settings = settings, startDestination = start)
                    }
                }
        }
    }

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(settings)
        }
    }

    fun onFileOpenedFromOutside(uri: Uri) =
        _uiState.update { it.copy(pendingImportUri = uri) }

    fun consumePendingImport() = _uiState.update { it.copy(pendingImportUri = null) }
}
