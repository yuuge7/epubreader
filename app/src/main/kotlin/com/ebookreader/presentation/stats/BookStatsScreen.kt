package com.ebookreader.presentation.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ReadingCalendar
import com.ebookreader.domain.model.ReadingPace
import com.ebookreader.domain.model.ReadingSession
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.model.SortOption
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.BookCover
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import com.ebookreader.presentation.library.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.roundToInt

data class BookStats(
    val book: Book,
    val sittings: List<ReadingSession>,
    val lastTwoWeeks: List<Pair<LocalDate, Long>>,
    /** Words a minute for a reflowable book; minutes a page for a paged one. */
    val pace: String?,
    val minutesLeft: Int?,
    val projectedFinish: LocalDate?,
)

@HiltViewModel
class BookStatsViewModel @Inject constructor(
    bookRepository: BookRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val bookId: Long = savedState.get<Long>("bookId") ?: -1L

    val stats: StateFlow<BookStats?> = combine(
        bookRepository.getAllBooks(SortOption.DATE_ADDED),
        bookRepository.getAllReadingSessions(),
    ) { books, sessions ->
        val book = books.firstOrNull { it.id == bookId } ?: return@combine null
        val mine = sessions.filter { it.bookId == bookId }
        val today = LocalDate.now()
        val calendar = ReadingCalendar(mine)
        val recent = calendar.lastDays(today, 14)
        val globalPace = ReadingPace.charactersPerMinute(books)
        val ownPace = ReadingPace.charactersPerMinute(listOf(book)).takeIf { it != ReadingPace.DEFAULT_CHARS_PER_MINUTE }
        val minutesLeft = ReadingPace.minutesLeftInBook(book, ownPace ?: globalPace)
        // At the pace of the last two weeks with this book, counting the days it was read.
        val minutesPerDay = recent.sumOf { it.second } / 60.0 / 14.0
        val finish = if (minutesLeft != null && minutesLeft > 0 && minutesPerDay >= 1.0 &&
            book.readingStatus != ReadingStatus.FINISHED
        ) today.plusDays(ceil(minutesLeft / minutesPerDay).toLong()) else null
        BookStats(
            book = book,
            sittings = mine.sortedByDescending { it.startedAt },
            lastTwoWeeks = recent,
            pace = pace(book, ownPace),
            minutesLeft = minutesLeft,
            projectedFinish = finish,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun pace(book: Book, charsPerMinute: Float?): String? = when (book.format) {
        // About 4.7 letters to an English word, spaces aside.
        BookFormat.EPUB -> charsPerMinute?.let { "about ${(it / 4.7f).roundToInt()} words a minute" }
        else -> ReadingPace.minutesPerPage(book)?.let { "${"%.1f".format(Locale.getDefault(), it)} minutes a page" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookStatsScreen(onNavigateBack: () -> Unit, viewModel: BookStatsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        }
    ) { padding ->
        val s = stats ?: return@Scaffold Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(s.book, Modifier.width(72.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.book.title, style = Bindery.type.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(s.book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${(s.book.readingProgress * 100).roundToInt()}% read",
                            style = Bindery.type.numeralCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Time with it", formatTotalReadingTime(s.book.totalReadingSeconds).ifBlank { "0m" }, Modifier.weight(1f))
                    StatTile("Sittings", s.sittings.size.toString(), Modifier.weight(1f))
                    StatTile(
                        "Average",
                        if (s.sittings.isEmpty()) "—"
                        else formatSessionTime(s.sittings.sumOf { it.durationSeconds } / s.sittings.size),
                        Modifier.weight(1f),
                    )
                }
            }
            item {
                BinderyCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle("Pace")
                        Text(s.pace?.replaceFirstChar { it.uppercase() } ?: "Read a little longer to learn your pace", style = Bindery.type.body)
                        s.minutesLeft?.takeIf { it > 0 && s.book.readingStatus != ReadingStatus.FINISHED }?.let {
                            Text("${formatMinutes(it)} left at that pace", style = Bindery.type.body)
                        }
                        Text(
                            when {
                                s.book.readingStatus == ReadingStatus.FINISHED -> "Finished"
                                s.projectedFinish != null ->
                                    "At the last two weeks' rate, finished around ${s.projectedFinish.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
                                else -> "Read it on a few days to see when you'll finish"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                BinderyCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("The last two weeks")
                        Spacer(Modifier.height(8.dp))
                        PeriodBars(
                            values = s.lastTwoWeeks.map { it.second },
                            labels = s.lastTwoWeeks.map { it.first.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) },
                            emphasis = s.lastTwoWeeks.lastIndex,
                            describe = { i ->
                                val (day, sec) = s.lastTwoWeeks[i]
                                "${day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}: ${formatSessionTime(sec)}"
                            },
                        )
                    }
                }
            }
            if (s.sittings.isNotEmpty()) {
                item { SectionTitle("Sittings") }
                items(s.sittings.take(30), key = { it.id }) { sitting ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(sitting.startedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatSessionTime(sitting.durationSeconds), style = Bindery.type.numeralSmall)
                    }
                }
            }
        }
    }
}

