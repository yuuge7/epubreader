package com.ebookreader.presentation.settings

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.backup.LibraryBackupManager
import com.ebookreader.data.library.StorageInfo
import com.ebookreader.data.library.StorageUse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataState(
    val storage: StorageUse? = null,
    val busy: Boolean = false,
    /** 0..1 while a backup or restore runs. */
    val progress: Float? = null,
    val message: String? = null,
)

/** Settings' Data page: the full backup, and the storage the app uses. */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val backup: LibraryBackupManager,
    private val storage: StorageInfo,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DataState())
    val state: StateFlow<DataState> = _state.asStateFlow()

    init {
        measure()
    }

    private fun measure() {
        viewModelScope.launch { _state.update { it.copy(storage = storage.measure()) } }
    }

    fun clearCache() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val freed = storage.clearCache()
            _state.update {
                it.copy(busy = false, message = "Cleared " + Formatter.formatShortFileSize(context, freed))
            }
            measure()
        }
    }

    fun backUp(target: Uri, includeBooks: Boolean) = run("Backup saved") {
        backup.export(target, includeBooks) { p -> _state.update { it.copy(progress = p) } }
    }

    fun restore(source: Uri) = run(null) {
        backup.import(source) { p -> _state.update { it.copy(progress = p) } }.message
    }

    private fun run(done: String?, work: suspend () -> Any?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = 0f) }
        viewModelScope.launch {
            val message = try {
                val result = work()
                done ?: result as? String ?: "Done"
            } catch (e: LibraryBackupManager.InvalidBackupException) {
                e.message
            } catch (e: Exception) {
                "Failed: ${e.message ?: "unknown error"}"
            }
            _state.update { it.copy(busy = false, progress = null, message = message) }
            measure()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
