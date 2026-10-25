package com.ebookreader.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

/** Every sitting that began on one day, newest first, with that day's total. */
data class SessionDay(
    val dayStart: Long,
    val totalSeconds: Long,
    val sessions: List<ReadingSession>
)

/** A book that appears somewhere in the history, offered as a filter. */
data class BookFilterOption(
    val bookId: Long,
    val title: String
)

data class SessionHistoryUiState(
    val days: List<SessionDay> = emptyList(),
    val books: List<BookFilterOption> = emptyList(),
    val selectedBookId: Long? = null,
    /** Summary of whatever is currently shown, so it answers the filter too. */
    val totalSeconds: Long = 0L,
    val sittingCount: Int = 0,
    val firstRead: Date? = null,
    val lastRead: Date? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    bookRepository: BookRepository
) : ViewModel() {

    private val selectedBookId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<SessionHistoryUiState> =
        combine(
            bookRepository.getAllReadingSessions(),
            selectedBookId
        ) { sessions, bookId ->
            // The filter list always describes the whole history, otherwise picking a book
            // would leave you with no way back to the others.
            val books = sessions
                .distinctBy { it.bookId }
                .map { BookFilterOption(it.bookId, it.bookTitle) }
                .sortedBy { it.title.lowercase() }

            val shown = if (bookId == null) sessions else sessions.filter { it.bookId == bookId }

            val days = shown
                .groupBy { startOfDay(it.startedAt) }
                .map { (dayStart, daySessions) ->
                    SessionDay(
                        dayStart = dayStart,
                        totalSeconds = daySessions.sumOf { it.durationSeconds },
                        sessions = daySessions.sortedByDescending { it.startedAt }
                    )
                }
                .sortedByDescending { it.dayStart }

            SessionHistoryUiState(
                days = days,
                books = books,
                selectedBookId = bookId,
                totalSeconds = shown.sumOf { it.durationSeconds },
                sittingCount = shown.size,
                firstRead = shown.minByOrNull { it.startedAt }?.startedAt,
                lastRead = shown.maxByOrNull { it.endedAt }?.endedAt,
                isLoading = false
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionHistoryUiState()
        )

    fun selectBook(bookId: Long?) {
        selectedBookId.value = bookId
    }

    private fun startOfDay(date: Date): Long = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
