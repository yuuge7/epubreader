package com.ebookreader.presentation.library

import com.ebookreader.domain.model.seriesLabel
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderySheet
import com.ebookreader.presentation.common.BookCover

/** Everything that can be done to one book, from the selection bar's "More". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsSheet(
    book: Book,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onShowStats: () -> Unit,
    onMarkAsFinished: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    BinderySheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BookCover(book, Modifier.width(44.dp), foreEdgeThickness = 3.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = Bindery.type.bookTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(book.author, book.seriesLabel).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OptionItem(Icons.Default.Edit, "Edit details", "Title, author and series") { onEdit(); onDismiss() }
            OptionItem(
                if (book.isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                if (book.isFavorite) "Remove from favourites" else "Add to favourites",
            ) { onToggleFavorite(); onDismiss() }
            OptionItem(Icons.Default.LibraryAdd, "Add to a collection") { onAddToCollection(); onDismiss() }
            OptionItem(Icons.Default.QueryStats, "Reading stats", "Sittings, pace and finish date") {
                onShowStats(); onDismiss()
            }
            if (book.readingStatus != ReadingStatus.FINISHED) {
                OptionItem(Icons.Default.CheckCircle, "Mark as finished") { onMarkAsFinished(); onDismiss() }
            }
            if (book.readingStatus != ReadingStatus.NOT_STARTED) {
                OptionItem(Icons.Default.RestartAlt, "Mark as unread", "Reset reading progress") {
                    onMarkAsUnread(); onDismiss()
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            OptionItem(
                icon = Icons.Default.Delete,
                label = "Remove from library",
                labelColor = MaterialTheme.colorScheme.error,
            ) { onDelete(); onDismiss() }
        }
    }
}

@Composable
private fun OptionItem(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, null, tint = labelColor)
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
