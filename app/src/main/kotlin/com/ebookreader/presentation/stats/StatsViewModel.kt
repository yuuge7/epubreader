package com.ebookreader.presentation.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.backup.StatsBackupCodec
import com.ebookreader.data.backup.StatsBackupManager
import com.ebookreader.domain.model.BookReadingStat
import com.ebookreader.domain.model.MonthSummary
import com.ebookreader.domain.model.YearSummary
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class StatsUiState(
    val totalTimeSeconds: Long = 0,
    val monthlyTimeSeconds: Long = 0,
    val yearlyTimeSeconds: Long = 0,
    val topBooksAllTime: List<BookReadingStat> = emptyList(),
    val topBooksThisMonth: List<BookReadingStat> = emptyList(),
    val monthlyHistory: List<MonthSummary> = emptyList(),
    val yearlyHistory: List<YearSummary> = emptyList(),
    val recentSessions: List<ReadingSession> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val statsBackupManager: StatsBackupManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = bookRepository.getAllReadingSessions()
        .map { sessions ->
            val startOfMonth = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val startOfYear = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Leaderboards
            val allTimeStats = sessions.groupBy { it.bookId }
                .map { (id, bookSessions) ->
                    BookReadingStat(
                        bookId = id,
                        bookTitle = bookSessions.first().bookTitle,
                        durationSeconds = bookSessions.sumOf { it.durationSeconds }
                    )
                }.sortedByDescending { it.durationSeconds }.take(5)

            val monthStats = sessions.filter { it.startedAt.time >= startOfMonth }
                .groupBy { it.bookId }
                .map { (id, bookSessions) ->
                    BookReadingStat(
                        bookId = id,
                        bookTitle = bookSessions.first().bookTitle,
                        durationSeconds = bookSessions.sumOf { it.durationSeconds }
                    )
                }.sortedByDescending { it.durationSeconds }.take(5)

            // Monthly History
            val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
            val history = sessions.groupBy { session ->
                Calendar.getInstance().apply {
                    time = session.startedAt
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.map { (startTime, monthSessions) ->
                val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                MonthSummary(
                    monthName = monthFormat.format(Date(startTime)),
                    year = cal.get(Calendar.YEAR),
                    totalSeconds = monthSessions.sumOf { it.durationSeconds },
                    startTimestamp = startTime
                )
            }.sortedByDescending { it.startTimestamp }

            // Yearly History
            val yearlyHistory = sessions.groupBy { session ->
                Calendar.getInstance().apply {
                    time = session.startedAt
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.map { (startTime, yearSessions) ->
                val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                YearSummary(
                    year = cal.get(Calendar.YEAR),
                    totalSeconds = yearSessions.sumOf { it.durationSeconds },
                    startTimestamp = startTime
                )
            }.sortedByDescending { it.startTimestamp }

            StatsUiState(
                totalTimeSeconds = sessions.sumOf { it.durationSeconds },
                monthlyTimeSeconds = sessions.filter { it.startedAt.time >= startOfMonth }.sumOf { it.durationSeconds },
                yearlyTimeSeconds = sessions.filter { it.startedAt.time >= startOfYear }.sumOf { it.durationSeconds },
                topBooksAllTime = allTimeStats,
                topBooksThisMonth = monthStats,
                monthlyHistory = history,
                yearlyHistory = yearlyHistory,
                recentSessions = sessions.take(10),
                isLoading = false
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StatsUiState()
        )

    // ── Backup ────────────────────────────────────────────────────────────────

    private val _backupState = MutableStateFlow(StatsBackupState())
    val backupState: StateFlow<StatsBackupState> = _backupState.asStateFlow()

    /** Filename offered by the "create document" picker. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "reading-stats-$stamp.json"
    }

    fun exportStats(target: Uri) {
        if (_backupState.value.isBusy) return
        _backupState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val message = try {
                val backup = statsBackupManager.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")
                        ?.use { it.write(StatsBackupCodec.encode(backup).toByteArray()) }
                        ?: error("Could not open the selected file for writing.")
                }
                "Exported ${backup.sessions.size} sessions and ${backup.books.size} books."
            } catch (e: Exception) {
                "Export failed: ${e.message ?: "unknown error"}"
            }
            _backupState.update { it.copy(isBusy = false, message = message) }
        }
    }

    fun importStats(source: Uri, mode: StatsImportMode, restoreProgress: Boolean) {
        if (_backupState.value.isBusy) return
        _backupState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val message = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(source)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("Could not open the selected file.")
                }
                val result = statsBackupManager.import(
                    backup = StatsBackupCodec.decode(json),
                    mode = mode,
                    restoreProgress = restoreProgress
                )
                buildString {
                    append("Imported ${result.sessionsImported} sessions")
                    if (result.sessionsSkipped > 0) {
                        append(" (${result.sessionsSkipped} already present)")
                    }
                    append(". ${result.booksMatched} books matched")
                    if (result.booksUnmatched > 0) {
                        append(", ${result.booksUnmatched} not in this library")
                    }
                    append(".")
                }
            } catch (e: StatsBackupCodec.InvalidBackupException) {
                e.message ?: "That file is not a stats backup."
            } catch (e: Exception) {
                "Import failed: ${e.message ?: "unknown error"}"
            }
            _backupState.update { it.copy(isBusy = false, message = message) }
        }
    }

    fun consumeBackupMessage() = _backupState.update { it.copy(message = null) }
}

data class StatsBackupState(
    val isBusy: Boolean = false,
    val message: String? = null
)
