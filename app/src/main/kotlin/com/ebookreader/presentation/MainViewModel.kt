package com.ebookreader.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val startDestination: String? = null, // null = loading
    /** File passed in by another app via ACTION_VIEW, waiting to be imported. */
    val pendingImportUri: Uri? = null,
    /** A book to open straight away, as the home-screen widget asks. */
    val pendingOpenBook: Book? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val bookRepository: BookRepository,
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

    fun editSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.editSettings(transform) }
    }

    fun onFileOpenedFromOutside(uri: Uri) =
        _uiState.update { it.copy(pendingImportUri = uri) }

    fun consumePendingImport() = _uiState.update { it.copy(pendingImportUri = null) }

    fun openBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.getBookById(bookId)?.let { book -> _uiState.update { it.copy(pendingOpenBook = book) } }
        }
    }

    fun consumePendingOpen() = _uiState.update { it.copy(pendingOpenBook = null) }
}
