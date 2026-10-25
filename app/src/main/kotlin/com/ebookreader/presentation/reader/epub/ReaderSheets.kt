package com.ebookreader.presentation.reader.epub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.EpubChapter
import com.ebookreader.domain.model.Highlight
import com.ebookreader.domain.model.HighlightColor
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.BinderySheet

/** The four inks as swatches; the page shows them at a lighter alpha over the text. */
internal val HighlightColor.swatch: Color
    get() = when (this) {
        HighlightColor.YELLOW -> Color(0xFFF2C230)
        HighlightColor.GREEN -> Color(0xFF6BBF59)
        HighlightColor.BLUE -> Color(0xFF55A3E6)
        HighlightColor.PINK -> Color(0xFFE877A9)
    }

private val HighlightColor.label: String
    get() = when (this) {
        HighlightColor.YELLOW -> "Yellow"
        HighlightColor.GREEN -> "Green"
        HighlightColor.BLUE -> "Blue"
        HighlightColor.PINK -> "Pink"
    }

/** Bookmarks and highlights of the book, side by side. */
@Composable
internal fun MarksPanel(
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    chapters: List<EpubChapter>,
    currentChapterIndex: Int,
    onJumpToChapter: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onOpenHighlight: (Highlight) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(if (bookmarks.isEmpty() && highlights.isNotEmpty()) 1 else 0) }
    Column(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bookmarks · ${bookmarks.size}") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Highlights · ${highlights.size}") })
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 0) {
            EpubBookmarksPanel(bookmarks, currentChapterIndex, onJumpToChapter, onDeleteBookmark)
        } else if (highlights.isEmpty()) {
            Text(
                "Select text on the page and choose Highlight or Note.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                items(highlights, key = { it.id }) { h ->
                    BinderyCard(onClick = { onOpenHighlight(h) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp)) {
                            Box(
                                Modifier
                                    .width(4.dp)
                                    .heightIn(min = 36.dp)
                                    .background(h.color.swatch)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapters.getOrNull(h.chapterIndex)?.title ?: "Chapter ${h.chapterIndex + 1}",
                                    style = Bindery.type.eyebrow,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    h.text,
                                    style = Bindery.type.bookTitle.copy(fontWeight = FontWeight.Normal),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (h.note.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        h.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search inside the open book: every match, with its chapter and the words around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSearchSheet(
    search: BookSearchState,
    onQueryChange: (String) -> Unit,
    onPick: (InBookHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BinderySheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Find in this book") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQueryChange(search.query) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(8.dp))
            when {
                search.isSearching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                search.searched && search.results.isEmpty() -> Text(
                    "Not found in this book",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                search.searched -> Text(
                    if (search.results.size == 1) "1 match" else "${search.results.size} matches",
                    style = Bindery.type.numeralCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.heightIn(max = 560.dp),
            ) {
                items(search.results, key = { "${it.chapterIndex}-${it.occurrence}" }) { hit ->
                    val accent = MaterialTheme.colorScheme.primary
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(hit) }
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text(
                            hit.chapterTitle,
                            style = Bindery.type.eyebrow,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildAnnotatedString {
                                append(hit.snippet.before)
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                                    append(hit.snippet.match)
                                }
                                append(hit.snippet.after)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A highlight tapped on the page: change its ink, write a note, share it, or remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightSheet(
    highlight: Highlight,
    chapterTitle: String?,
    onColor: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BinderySheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            chapterTitle?.let {
                Text(it.uppercase(), style = Bindery.type.eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                highlight.text,
                style = Bindery.type.bookTitle.copy(fontWeight = FontWeight.Normal),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (highlight.note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    highlight.note,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HighlightColor.entries.forEach { color ->
                    val selected = color == highlight.color
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color.swatch)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { onColor(color) }
                            .semantics {
                                contentDescription = color.label
                                role = Role.RadioButton
                                this.selected = selected
                            }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNote) {
                    Icon(Icons.AutoMirrored.Filled.Notes, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (highlight.note.isBlank()) "Add note" else "Edit note")
                }
                OutlinedButton(onClick = onQuote) {
                    Icon(Icons.Default.FormatQuote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Quote card")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Remove highlight", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun NoteDialog(highlight: Highlight, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column {
                Text(
                    "“${highlight.text}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
