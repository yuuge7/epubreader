package com.ebookreader.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.ebookreader.data.backup.StatsBackupCodec
import com.ebookreader.data.backup.StatsBackupManager
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.StatsBackup
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.domain.model.bookMatchKey
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps reading history, progress and highlights in step between devices through a
 * folder the reader owns: one their phones already sync (Syncthing, or a cloud drive's
 * folder). No account and no server; the app reads and writes one file there.
 *
 * A sync merges the folder's file into this device (new sittings and highlights added,
 * a book's place taken from whichever device read it last) and writes the merged result
 * back, keeping what the file holds about books this device does not have. The books
 * themselves are not copied; they are matched by title and author.
 *
 * It runs every time the app is opened and left, so it does as little as it can: the
 * file is found once and remembered, and written only when the merge changed it.
 */
@Singleton
class FolderSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: StatsBackupManager,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    sealed interface Result {
        data class Synced(val sessionsAdded: Int, val highlightsAdded: Int) : Result
        data object NoFolder : Result
        data class Failed(val message: String) : Result
    }

    private val running = Mutex()

    // Kept apart from the app's settings: every change there redraws the open screens.
    private val prefs = context.getSharedPreferences("folder_sync", Context.MODE_PRIVATE)
    private val _lastSyncAt = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
    /** When a sync last finished, or null. */
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()

    /** Keeps access to [tree] across restarts, and makes it the sync folder. */
    suspend fun setFolder(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, ACCESS) }
        forget()
        settingsRepository.editSettings { it.copy(syncFolder = tree.toString()) }
    }

    suspend fun clearFolder() {
        settingsRepository.getSettings().first().syncFolder?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), ACCESS) }
        }
        forget()
        settingsRepository.editSettings { it.copy(syncFolder = null) }
    }

    /** The folder's name as its app shows it, for the settings row. */
    fun folderName(uri: String): String? =
        runCatching { DocumentFile.fromTreeUri(context, uri.toUri())?.name }.getOrNull()

    /** A sync in the background, when there is a folder: on opening and leaving the app. */
    fun syncInBackground() {
        appScope.launch { syncNow() }
    }

    suspend fun syncNow(): Result = running.withLock {
        withContext(Dispatchers.IO) {
            val folder = settingsRepository.getSettings().first().syncFolder ?: return@withContext Result.NoFolder
            try {
                val file = syncFile(folder)
                    ?: return@withContext Result.Failed("The sync folder can't be reached. Choose it again.")
                val remote = context.contentResolver.openInputStream(file.uri)
                    ?.use { it.readBytes().decodeToString() }
                    ?.takeIf { it.isNotBlank() }
                    ?.let(StatsBackupCodec::decode)
                val imported = remote?.let { backupManager.import(it, StatsImportMode.MERGE, restoreProgress = true) }
                val merged = withOthers(backupManager.export(), remote)
                if (remote == null || !sameContent(merged, remote)) {
                    // "wt" truncates: a shorter file must not keep the tail of the longer one.
                    context.contentResolver.openOutputStream(file.uri, "wt")
                        ?.use { it.write(StatsBackupCodec.encode(merged).toByteArray()) }
                        ?: return@withContext Result.Failed("The sync file could not be written.")
                }
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_LAST_SYNC, now) }
                _lastSyncAt.value = now
                Result.Synced(imported?.sessionsImported ?: 0, imported?.highlightsImported ?: 0)
            } catch (e: StatsBackupCodec.InvalidBackupException) {
                Result.Failed("The sync file in that folder is damaged: ${e.message}")
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Sync failed")
            }
        }
    }

    /** The sync file, found (or made) once and then remembered; null if the folder is gone. */
    private fun syncFile(folder: String): DocumentFile? {
        prefs.getString(KEY_FILE, null)?.let { known ->
            DocumentFile.fromSingleUri(context, known.toUri())?.takeIf { it.exists() }?.let { return it }
        }
        val tree = DocumentFile.fromTreeUri(context, folder.toUri())?.takeIf { it.canWrite() } ?: return null
        val file = tree.findFile(FILE_NAME) ?: tree.createFile(StatsBackupCodec.MIME_TYPE, FILE_NAME) ?: return null
        prefs.edit { putString(KEY_FILE, file.uri.toString()) }
        return file
    }

    /**
     * This device's export, plus what [remote] says about books this device does not have,
     * so a phone without a book does not wipe its place and highlights for the others.
     */
    private fun withOthers(local: StatsBackup, remote: StatsBackup?): StatsBackup {
        if (remote == null) return local
        val here = local.books.mapTo(HashSet()) { bookMatchKey(it.title, it.author) }
        return local.copy(
            books = local.books + remote.books.filter { bookMatchKey(it.title, it.author) !in here },
            highlights = local.highlights + remote.highlights.filter { bookMatchKey(it.bookTitle, it.bookAuthor) !in here },
        )
    }

    /** Whether writing [merged] would change nothing that [remote] holds; order aside. */
    private fun sameContent(merged: StatsBackup, remote: StatsBackup): Boolean =
        merged.books.toSet() == remote.books.toSet() &&
            merged.sessions.toSet() == remote.sessions.toSet() &&
            merged.highlights.toSet() == remote.highlights.toSet()

    private fun forget() {
        prefs.edit { remove(KEY_FILE).remove(KEY_LAST_SYNC) }
        _lastSyncAt.value = null
    }

    companion object {
        /** The name sync tools and the reader see in the folder. */
        const val FILE_NAME = "ebook-reader-sync.json"
        private const val KEY_FILE = "file"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
