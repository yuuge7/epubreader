package com.ebookreader.presentation.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.domain.model.Book
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BookCover
import com.ebookreader.presentation.common.formatTotalReadingTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The latest finished books as a row of covers, for the stats screen. */
@Composable
internal fun FinishedStrip(books: List<Book>, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        books.forEach { book ->
            BookCover(
                book,
                Modifier
                    .width(64.dp)
                    .clickable(onClickLabel = "Open the timeline", onClick = onOpen),
            )
        }
    }
}

/**
 * Every finished book on a line through time: by year, newest first, each with the day it
 * was finished and how long it took. The gilt edge on each cover is the finish itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FinishedTimelineScreen(
    onNavigateBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val day = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val byYear = remember(state.finished) {
        state.finished.groupBy { Instant.ofEpochMilli(it.finishedAt!!.time).atZone(zone).year }
            .toSortedMap(compareByDescending { it })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finished books") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        }
    ) { padding ->
        if (!state.isLoading && byYear.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Books you finish show up here, with the day you finished them.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            byYear.forEach { (year, books) ->
                stickyHeader(key = "y$year") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(year.toString(), style = Bindery.type.numeral, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (books.size == 1) "1 book" else "${books.size} books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(books, key = { it.id }) { book ->
                    TimelineRow(book, day.format(Instant.ofEpochMilli(book.finishedAt!!.time).atZone(zone))) { onOpenBook(book) }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(book: Book, date: String, onClick: () -> Unit) {
    val rule = Bindery.colors.rule
    val foil = Bindery.colors.foil
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The line through time, with a foil mark at each finish.
        Box(Modifier.width(16.dp).height(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(rule))
            Box(Modifier.size(9.dp).background(foil, androidx.compose.foundation.shape.CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        BookCover(book, Modifier.width(48.dp), foreEdgeThickness = 3.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(date, style = Bindery.type.eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(book.title, style = Bindery.type.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(book.author, formatTotalReadingTime(book.totalReadingSeconds).takeIf { it.isNotEmpty() }?.let { "$it reading" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
