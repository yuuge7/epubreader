package com.ebookreader.presentation.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.sync.FolderSync
import com.ebookreader.presentation.common.plural
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The sync folder row in Settings: choose a folder, sync now, stop. */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val folderSync: FolderSync,
) : ViewModel() {

    val lastSyncAt: StateFlow<Long?> = folderSync.lastSyncAt

    var isSyncing by mutableStateOf(false)
        private set

    /** How the last sync from this screen went, until the next one. */
    var outcome by mutableStateOf<String?>(null)
        private set

    fun chooseFolder(tree: Uri) {
        viewModelScope.launch {
            folderSync.setFolder(tree)
            sync()
        }
    }

    fun syncNow() {
        viewModelScope.launch { sync() }
    }

    fun stopSyncing() {
        viewModelScope.launch {
            folderSync.clearFolder()
            outcome = null
        }
    }

    fun folderName(uri: String): String = folderSync.folderName(uri) ?: "the chosen folder"

    private suspend fun sync() {
        if (isSyncing) return
        isSyncing = true
        outcome = when (val result = folderSync.syncNow()) {
            is FolderSync.Result.Synced -> buildList {
                if (result.sessionsAdded > 0) add(plural(result.sessionsAdded, "sitting"))
                if (result.highlightsAdded > 0) add(plural(result.highlightsAdded, "highlight"))
            }.let { added -> if (added.isEmpty()) "Up to date" else "Brought in " + added.joinToString(" and ") }
            is FolderSync.Result.Failed -> result.message
            FolderSync.Result.NoFolder -> null
        }
        isSyncing = false
    }
}
