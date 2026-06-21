package com.ebookreader.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.BookReadingStat
import com.ebookreader.domain.model.MonthSummary
import com.ebookreader.domain.model.YearSummary
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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
    private val bookRepository: BookRepository
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

            val monthStats = sessions.filter { it.timestamp.time >= startOfMonth }
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
                    time = session.timestamp
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
                    time = session.timestamp
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
                monthlyTimeSeconds = sessions.filter { it.timestamp.time >= startOfMonth }.sumOf { it.durationSeconds },
                yearlyTimeSeconds = sessions.filter { it.timestamp.time >= startOfYear }.sumOf { it.durationSeconds },
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
}
