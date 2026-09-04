package com.ebookreader.presentation.stats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.data.backup.StatsBackupCodec
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMonthlyHistory: () -> Unit,
    onNavigateToYearlyHistory: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showBackupMenu by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(StatsBackupCodec.MIME_TYPE)
    ) { uri: Uri? -> uri?.let(viewModel::exportStats) }

    // Not every provider tags a .json file as application/json, so accept the usual
    // stand-ins too — the file itself is validated when it is decoded.
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> pendingImportUri = uri }

    LaunchedEffect(backupState.message) {
        backupState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeBackupMessage()
        }
    }

    pendingImportUri?.let { uri ->
        ImportOptionsDialog(
            onDismiss = { pendingImportUri = null },
            onConfirm = { mode, restoreProgress ->
                pendingImportUri = null
                viewModel.importStats(uri, mode, restoreProgress)
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Reading Stats", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToMonthlyHistory) {
                        Icon(Icons.Default.History, "Monthly History")
                    }
                    IconButton(onClick = onNavigateToYearlyHistory) {
                        Icon(Icons.AutoMirrored.Filled.EventNote, "Yearly History")
                    }
                    IconButton(
                        onClick = { showBackupMenu = true },
                        enabled = !backupState.isBusy
                    ) {
                        Icon(Icons.Default.MoreVert, "Backup options")
                    }
                    DropdownMenu(
                        expanded = showBackupMenu,
                        onDismissRequest = { showBackupMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export stats") },
                            leadingIcon = { Icon(Icons.Default.Upload, null) },
                            onClick = {
                                showBackupMenu = false
                                exportPicker.launch(viewModel.suggestedFileName())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import stats") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                showBackupMenu = false
                                importPicker.launch(
                                    arrayOf(
                                        StatsBackupCodec.MIME_TYPE,
                                        "text/plain",
                                        "application/octet-stream"
                                    )
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            "Overview",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                label = "This Month",
                                value = formatTotalReadingTime(uiState.monthlyTimeSeconds).ifBlank { "0m" },
                                icon = Icons.Default.CalendarMonth,
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                label = "This Year",
                                value = formatTotalReadingTime(uiState.yearlyTimeSeconds).ifBlank { "0m" },
                                icon = Icons.AutoMirrored.Filled.TrendingUp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        StatCard(
                            label = "Total Reading Time",
                            value = formatTotalReadingTime(uiState.totalTimeSeconds).ifBlank { "0m" },
                            icon = Icons.Default.History,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Top Books Section
                    if (uiState.topBooksThisMonth.isNotEmpty()) {
                        item {
                            Text(
                                "Top Books (This Month)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(uiState.topBooksThisMonth) { stat ->
                            LeaderboardItem(stat)
                        }
                    }

                    if (uiState.topBooksAllTime.isNotEmpty()) {
                        item {
                            Text(
                                "Top Books (All Time)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(uiState.topBooksAllTime) { stat ->
                            LeaderboardItem(stat)
                        }
                    }

                    item {
                        Text(
                            "Recent Sessions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (uiState.recentSessions.isEmpty()) {
                        item {
                            Text(
                                "No reading sessions recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        items(uiState.recentSessions) { session ->
                            SessionItem(session)
                        }
                    }
                }
            }

            if (backupState.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun ImportOptionsDialog(
    onDismiss: () -> Unit,
    onConfirm: (StatsImportMode, Boolean) -> Unit
) {
    var mode by remember { mutableStateOf(StatsImportMode.MERGE) }
    var restoreProgress by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text("Import stats") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImportModeOption(
                    title = "Merge",
                    subtitle = "Keep what is on this device and add anything missing.",
                    selected = mode == StatsImportMode.MERGE,
                    onClick = { mode = StatsImportMode.MERGE }
                )
                ImportModeOption(
                    title = "Replace",
                    subtitle = "Delete this device's reading history and use the file instead.",
                    selected = mode == StatsImportMode.REPLACE,
                    onClick = { mode = StatsImportMode.REPLACE }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = restoreProgress,
                        onCheckedChange = { restoreProgress = it }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Restore reading positions", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Only for books already in this library, and only where the " +
                                "backup is more recent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (mode == StatsImportMode.REPLACE) {
                    Text(
                        "Replace cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode, restoreProgress) }) {
                Text(if (mode == StatsImportMode.REPLACE) "Replace" else "Merge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LeaderboardItem(stat: com.ebookreader.domain.model.BookReadingStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                stat.bookTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                formatTotalReadingTime(stat.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SessionItem(session: com.ebookreader.domain.model.ReadingSession) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.bookTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    dateFormat.format(session.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
