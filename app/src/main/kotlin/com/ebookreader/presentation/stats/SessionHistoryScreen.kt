package com.ebookreader.presentation.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (uiState.books.size > 1) {
                BookFilterRow(
                    books = uiState.books,
                    selectedBookId = uiState.selectedBookId,
                    onSelect = viewModel::selectBook
                )
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                uiState.days.isEmpty() -> EmptyHistory()

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item { HistorySummary(uiState) }

                    uiState.days.forEach { day ->
                        item(key = "day-${day.dayStart}") { DayHeader(day) }
                        items(
                            items = day.sessions,
                            key = { session -> session.id }
                        ) { session ->
                            SessionRow(session, showBookTitle = uiState.selectedBookId == null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySummary(state: SessionHistoryUiState) {
    val dayFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                formatTotalReadingTime(state.totalSeconds).ifBlank { "0m" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            val sittings = if (state.sittingCount == 1) "1 sitting" else "${state.sittingCount} sittings"
            Text(
                sittings,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.firstRead?.let { first ->
                Text(
                    "Started ${dayFormat.format(first)}" +
                        (state.lastRead?.let { " · last read ${dayFormat.format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BookFilterRow(
    books: List<BookFilterOption>,
    selectedBookId: Long?,
    onSelect: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedBookId == null,
            onClick = { onSelect(null) },
            label = { Text("All books") },
            leadingIcon = if (selectedBookId == null) {
                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
            } else null
        )
        books.forEach { book ->
            val selected = selectedBookId == book.bookId
            FilterChip(
                selected = selected,
                onClick = { onSelect(if (selected) null else book.bookId) },
                label = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
private fun DayHeader(day: SessionDay) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            rememberDayLabel(day.dayStart),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatTotalReadingTime(day.totalSeconds).ifBlank { "0m" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SessionRow(session: ReadingSession, showBookTitle: Boolean) {
    val clockFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (showBookTitle) {
                    Text(
                        session.bookTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${clockFormat.format(session.startedAt)}–${clockFormat.format(session.endedAt)}",
                    style = if (showBookTitle) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.bodyLarge,
                    color = if (showBookTitle) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                formatSessionTime(session.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.HistoryToggleOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                "Nothing read yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Open a book and your sittings will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** "Today" and "Yesterday" read faster than a date; everything older gets the date. */
@Composable
private fun rememberDayLabel(dayStart: Long): String {
    val format = remember { SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()) }
    return remember(dayStart) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayMs = 24L * 60 * 60 * 1000
        when (dayStart) {
            today -> "Today"
            today - dayMs -> "Yesterday"
            else -> format.format(Date(dayStart))
        }
    }
}
