package com.ebookreader.presentation.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.backup.StatsBackupCodec
import com.ebookreader.data.backup.StatsBackupManager
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookReadingStat
import com.ebookreader.domain.model.ReadingCalendar
import com.ebookreader.domain.model.ReadingGoals
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.SortOption
import com.ebookreader.domain.repository.SettingsRepository
import com.ebookreader.data.reminders.ReadingReminders
import com.ebookreader.presentation.common.YearInReading
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import com.ebookreader.domain.model.MonthSummary
import com.ebookreader.domain.model.YearSummary
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.ReadingHistory
import com.ebookreader.domain.model.SessionsCsv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.format.DateTimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/** The calendar side of the stats: what the charts at the top of the screen draw. */
data class ReadingDays(
    val todaySeconds: Long = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Every day from the Monday 52 weeks back to today, oldest first. */
    val heatmap: List<Pair<LocalDate, Long>> = emptyList(),
    val lastWeek: List<Pair<LocalDate, Long>> = emptyList(),
    val lastYear: List<Pair<YearMonth, Long>> = emptyList(),
    val booksFinishedThisYear: Int = 0,
    val year: YearInReading? = null,
)

data class StatsUiState(
    val totalTimeSeconds: Long = 0,
    val monthlyTimeSeconds: Long = 0,
    val yearlyTimeSeconds: Long = 0,
    val topBooksAllTime: List<BookReadingStat> = emptyList(),
    val topBooksThisMonth: List<BookReadingStat> = emptyList(),
    val monthlyHistory: List<MonthSummary> = emptyList(),
    val yearlyHistory: List<YearSummary> = emptyList(),
    val recentSessions: List<ReadingSession> = emptyList(),
    /** Finished books, the most recently finished first. */
    val finished: List<Book> = emptyList(),
    val days: ReadingDays = ReadingDays(),
    val goals: ReadingGoals = ReadingGoals(),
    val isLoading: Boolean = true
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val history: ReadingHistory,
    private val settingsRepository: SettingsRepository,
    private val reminders: ReadingReminders,
    private val statsBackupManager: StatsBackupManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * Recomputed when the history, the books or the goals change. The history is summed
     * in SQL (see [ReadingHistory]): only per-day totals and a few rows come back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatsUiState> = combine(
        history.changes(),
        bookRepository.getAllBooks(SortOption.DATE_ADDED),
        settingsRepository.getSettings().map { it.goals }.distinctUntilChanged(),
    ) { _, books, goals -> books to goals }
        .mapLatest { (books, goals) ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val startOfYear = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val byDay = history.secondsByDay()
            val calendar = ReadingCalendar(byDay)
            val monthFormat = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
            val monthly = byDay.entries.groupBy({ YearMonth.from(it.key) }, { it.value })
                .map { (month, seconds) ->
                    val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    MonthSummary(month.format(monthFormat), month.year, seconds.sum(), start)
                }
                .sortedByDescending { it.startTimestamp }
            val yearly = byDay.entries.groupBy({ it.key.year }, { it.value })
                .map { (year, seconds) ->
                    YearSummary(year, seconds.sum(), LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
                }
                .sortedByDescending { it.startTimestamp }

            StatsUiState(
                days = readingDays(calendar, books, history.topBooks(startOfYear, 1).firstOrNull()?.bookTitle),
                goals = goals,
                totalTimeSeconds = byDay.values.sum(),
                monthlyTimeSeconds = history.totalSeconds(startOfMonth),
                yearlyTimeSeconds = history.totalSeconds(startOfYear),
                topBooksAllTime = history.topBooks(0L, 5),
                topBooksThisMonth = history.topBooks(startOfMonth, 5),
                monthlyHistory = monthly,
                yearlyHistory = yearly,
                recentSessions = history.recent(10),
                finished = books.filter { it.finishedAt != null }.sortedByDescending { it.finishedAt },
                isLoading = false
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StatsUiState()
        )

    private fun readingDays(calendar: ReadingCalendar, books: List<Book>, topBook: String?): ReadingDays {
        val today = LocalDate.now()
        val start = today.with(java.time.DayOfWeek.MONDAY).minusWeeks(52)
        val heatmap = calendar.lastDays(today, java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt() + 1)
        val longestStreak = calendar.longestStreak()
        val year = today.year
        val finishedThisYear = books.filter { book ->
            book.readingStatus == ReadingStatus.FINISHED &&
                (book.finishedAt ?: book.lastRead)?.let {
                    java.time.Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).year
                } == year
        }
        return ReadingDays(
            todaySeconds = calendar.secondsOn(today),
            currentStreak = calendar.currentStreak(today),
            longestStreak = longestStreak,
            heatmap = heatmap,
            lastWeek = calendar.lastDays(today, 7),
            lastYear = calendar.lastMonths(today, 12),
            booksFinishedThisYear = finishedThisYear.size,
            year = YearInReading(
                year = year,
                totalSeconds = calendar.secondsIn(year),
                booksFinished = finishedThisYear.size,
                daysRead = calendar.daysReadIn(year),
                longestStreak = longestStreak,
                topBook = topBook?.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun setGoals(goals: ReadingGoals) {
        viewModelScope.launch {
            settingsRepository.editSettings { it.copy(goals = goals.sanitized()) }
            reminders.schedule(goals.sanitized())
        }
    }

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

    fun suggestedCsvName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "reading-sessions-$stamp.csv"
    }

    /** Every sitting as a row of a spreadsheet: one line each, oldest first. */
    fun exportCsv(target: Uri) {
        if (_backupState.value.isBusy) return
        _backupState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val message = try {
                val sessions = history.allSessions()
                val books = bookRepository.getAllBooks(SortOption.DATE_ADDED).first().associateBy { it.id }
                val csv = SessionsCsv.write(sessions, books)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")
                        ?.use { it.write(csv.toByteArray()) }
                        ?: error("Could not open the selected file for writing.")
                }
                "Exported ${sessions.size} sittings as CSV."
            } catch (e: Exception) {
                "Export failed: ${e.message ?: "unknown error"}"
            }
            _backupState.update { it.copy(isBusy = false, message = message) }
        }
    }
}

data class StatsBackupState(
    val isBusy: Boolean = false,
    val message: String? = null
)
