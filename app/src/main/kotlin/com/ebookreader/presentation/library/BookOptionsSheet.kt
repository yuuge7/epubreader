package com.ebookreader.presentation.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ReadingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsSheet(
    book: Book,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMarkAsFinished: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Book info header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = if (book.format == BookFormat.PDF)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        book.format.name,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (book.format == BookFormat.PDF)
                            MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Options
            OptionItem(
                icon = Icons.Default.Edit,
                label = "Edit Details",
                subtitle = "Change title and author",
                onClick = { onEdit(); onDismiss() }
            )

            OptionItem(
                icon = if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = if (book.isFavorite) "Remove from Favorites" else "Add to Favorites",
                onClick = { onToggleFavorite(); onDismiss() }
            )

            if (book.readingStatus != ReadingStatus.FINISHED) {
                OptionItem(
                    icon = Icons.Default.CheckCircle,
                    label = "Mark as Finished",
                    subtitle = "Set reading status to finished",
                    onClick = { onMarkAsFinished(); onDismiss() }
                )
            }

            if (book.readingStatus != ReadingStatus.NOT_STARTED) {
                OptionItem(
                    icon = Icons.Default.RestartAlt,
                    label = "Mark as Unread",
                    subtitle = "Reset reading progress",
                    onClick = { onMarkAsUnread(); onDismiss() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            OptionItem(
                icon = Icons.Default.Delete,
                label = "Remove from Library",
                labelColor = MaterialTheme.colorScheme.error,
                onClick = { onDelete(); onDismiss() }
            )
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
