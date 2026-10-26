package com.ebookreader.presentation.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.BuildConfig
import com.ebookreader.data.backup.LibraryBackupManager
import com.ebookreader.domain.model.*
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.ReadingThemePicker
import com.ebookreader.presentation.common.label
import com.ebookreader.presentation.library.displayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The parts Settings is split into, each its own page. */
enum class SettingsSection(val route: String, val title: String, val summary: String, val icon: ImageVector) {
    READING("reading", "Reading", "Page colour, turning pages, screen, read-aloud, PDF and comics", Icons.AutoMirrored.Filled.MenuBook),
    LIBRARY("library", "Library", "The app's look, sorting and the shelf view", Icons.AutoMirrored.Filled.LibraryBooks),
    DATA("data", "Data", "Backup and restore, sync, storage", Icons.Default.Storage),
    ABOUT("about", "About", "Version, formats, fonts, privacy", Icons.Default.Info);

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/** Settings' first page: the four sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSection: (SettingsSection) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsSection.entries.forEach { section ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSection(section) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        Text(section.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One section of Settings, on a page of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            when (section) {
                SettingsSection.READING -> ReadingSettings(settings, onEditSettings)
                SettingsSection.LIBRARY -> LibrarySettings(settings, onEditSettings)
                SettingsSection.DATA -> DataSettings(settings, snackbar)
                SettingsSection.ABOUT -> AboutSettings()
            }
        }
    }
}

// ── Reading ───────────────────────────────────────────────────────────────────

@Composable
private fun ReadingSettings(settings: AppSettings, onEdit: ((AppSettings) -> AppSettings) -> Unit) {
    SettingsSectionHeader("Page")
    SettingsItem("Page colour", settings.readingTheme.description, Icons.Default.Palette) {
        ReadingThemePicker(
            selected = settings.readingTheme,
            appTheme = settings.theme,
            onSelect = { theme -> onEdit { it.copy(readingTheme = theme) } }
        )
    }
    // Size, face and spacing are set from the reader, where the page shows what each
    // one does; only their whereabouts are noted here.
    SettingsInfoItem("Text, spacing and light", "Open a book and tap Aa", Icons.Default.FormatSize)

    SettingsSectionHeader("Turning pages")
    SettingsItem(
        "Books turn",
        if (settings.typography.paged) "Page by page, sideways, like a printed book" else "As one long scroll per chapter",
        Icons.Default.SwapHoriz,
    ) {
        ChoiceRow(
            options = listOf(false to "Scroll", true to "Pages"),
            selected = settings.typography.paged,
            onSelect = { paged -> onEdit { it.copy(typography = it.typography.copy(paged = paged)) } },
        )
    }
    SettingsToggleItem(
        title = "Swipe to change chapter",
        subtitle = "In scrolling books, a sideways swipe opens the next or previous chapter",
        icon = Icons.Default.SwipeRight,
        checked = settings.readerScrollDirection == ScrollDirection.HORIZONTAL,
    ) { on -> onEdit { it.copy(readerScrollDirection = if (on) ScrollDirection.HORIZONTAL else ScrollDirection.VERTICAL) } }
    SettingsItem(
        "Auto-scroll speed",
        "${settings.autoScrollSpeed} of ${AutoScroll.MAX_SPEED}. Start it from a book's menu",
        Icons.Default.KeyboardDoubleArrowDown,
    ) {
        var speed by remember(settings.autoScrollSpeed) { mutableFloatStateOf(settings.autoScrollSpeed.toFloat()) }
        Slider(
            value = speed,
            onValueChange = { speed = it },
            onValueChangeFinished = { onEdit { it.copy(autoScrollSpeed = speed.roundToInt()) } },
            valueRange = AutoScroll.MIN_SPEED.toFloat()..AutoScroll.MAX_SPEED.toFloat(),
            steps = AutoScroll.MAX_SPEED - AutoScroll.MIN_SPEED - 1,
            modifier = Modifier.semantics { contentDescription = "Auto-scroll speed" },
        )
    }

    SettingsSectionHeader("While reading")
    SettingsToggleItem(
        title = "Keep the screen on",
        subtitle = "Only while a book is open; the library sleeps as usual",
        icon = Icons.Default.ScreenLockPortrait,
        checked = settings.keepScreenOn
    ) { on -> onEdit { it.copy(keepScreenOn = on) } }
    SettingsItem(
        "Reading focus",
        when (settings.readingFocus) {
            ReadingFocus.OFF -> "Off"
            ReadingFocus.RULER -> "A band across the page to read along"
            ReadingFocus.DIM -> "The paragraphs around the one being read are dimmed"
        },
        Icons.Default.CenterFocusStrong,
    ) {
        ChoiceRow(
            options = listOf(ReadingFocus.OFF to "Off", ReadingFocus.RULER to "Ruler", ReadingFocus.DIM to "Dim"),
            selected = settings.readingFocus,
            onSelect = { focus -> onEdit { it.copy(readingFocus = focus) } },
        )
    }
    SettingsItem(
        "Read-aloud speed",
        String.format(Locale.ROOT, "%.2g×", settings.readAloudRate),
        Icons.Default.RecordVoiceOver,
    ) {
        var rate by remember(settings.readAloudRate) { mutableFloatStateOf(settings.readAloudRate) }
        Slider(
            value = rate,
            onValueChange = { rate = it },
            onValueChangeFinished = { onEdit { it.copy(readAloudRate = (rate * 4).roundToInt() / 4f) } },
            valueRange = 0.5f..3f,
            modifier = Modifier.semantics { contentDescription = "Read-aloud speed" },
        )
    }

    SettingsSectionHeader("PDF and comics")
    SettingsToggleItem(
        title = "Night pages for PDFs",
        subtitle = "Light text on a dark page; pictures are inverted too",
        icon = Icons.Default.InvertColors,
        checked = settings.pdfNight,
    ) { on -> onEdit { it.copy(pdfNight = on) } }
    SettingsToggleItem(
        title = "Crop PDF margins",
        subtitle = "Zoom each document to its text, cutting the empty margins",
        icon = Icons.Default.Crop,
        checked = settings.pdfCropMargins,
    ) { on -> onEdit { it.copy(pdfCropMargins = on) } }
    SettingsToggleItem(
        title = "Comics right to left",
        subtitle = "Pages turn the way manga is read",
        icon = Icons.AutoMirrored.Filled.FormatTextdirectionRToL,
        checked = settings.comicRightToLeft,
    ) { on -> onEdit { it.copy(comicRightToLeft = on) } }
}

// ── Library ───────────────────────────────────────────────────────────────────

@Composable
private fun LibrarySettings(settings: AppSettings, onEdit: ((AppSettings) -> AppSettings) -> Unit) {
    SettingsSectionHeader("Look")
    SettingsItem("App theme", settings.theme.description, Icons.Default.DarkMode) {
        ChoiceRow(
            options = AppTheme.entries.map { it to it.displayName },
            selected = settings.theme,
            onSelect = { theme -> onEdit { it.copy(theme = theme) } },
        )
    }
    SettingsToggleItem(
        title = "Shelf of covers",
        subtitle = if (settings.isGridView) "Books as a grid of covers" else "Books as a list with details",
        icon = Icons.Default.GridView,
        checked = settings.isGridView,
    ) { on -> onEdit { it.copy(isGridView = on) } }

    SettingsSectionHeader("Order")
    SettingsItem(
        "Sort books by",
        settings.sortOption.displayName + if (settings.sortDescending) ", descending" else ", ascending",
        Icons.AutoMirrored.Filled.Sort,
    ) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortOption.entries.forEach { sort ->
                FilterChip(
                    selected = settings.sortOption == sort,
                    onClick = { onEdit { it.copy(sortOption = sort, sortDescending = sort.descendingByDefault) } },
                    label = { Text(sort.displayName) },
                )
            }
        }
        SettingsToggleRow(
            title = "Descending",
            checked = settings.sortDescending,
            onCheckedChange = { on -> onEdit { it.copy(sortDescending = on) } },
        )
    }
}

// ── Data ──────────────────────────────────────────────────────────────────────

@Composable
private fun DataSettings(settings: AppSettings, snackbar: SnackbarHostState, viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        state.message?.let { viewModel.consumeMessage(); snackbar.showSnackbar(it) }
    }
    var includeBooks by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryBackupManager.MIME_TYPE)
    ) { uri -> uri?.let { viewModel.backUp(it, includeBooks) } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    SettingsSectionHeader("Backup")
    SettingsItem(
        "Back up the whole library",
        "Books, covers, places, bookmarks, highlights, collections, history and settings, in one file",
        Icons.Default.Backup,
    ) {
        SettingsToggleRow(
            title = "Include the book files",
            detail = "Off for a small file, for a device that already has the books",
            checked = includeBooks,
            onCheckedChange = { includeBooks = it },
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportPicker.launch("library-backup-$stamp.zip")
            }, enabled = !state.busy) { Text("Back up") }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf(LibraryBackupManager.MIME_TYPE, "application/octet-stream")) },
                enabled = !state.busy,
            ) { Text("Restore") }
        }
        state.progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
    }
    SettingsInfoItem(
        "Reading stats alone",
        "The history as JSON or CSV: Reading stats, then the menu",
        Icons.Default.QueryStats,
    )

    SettingsSectionHeader("Sync")
    SyncSettings(settings)

    SettingsSectionHeader("Storage")
    StorageSettings(state.storage, state.busy, onClear = viewModel::clearCache)

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Books, bookmarks and collections in it are added to this library; books already here keep " +
                        "their files. Reading history is merged, and the backup's settings replace these."
                )
            },
            confirmButton = { TextButton(onClick = { confirmRestore = null; viewModel.restore(uri) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StorageSettings(use: com.ebookreader.data.library.StorageUse?, busy: Boolean, onClear: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    if (use == null) {
        SettingsInfoItem("Space used", "Measuring…", Icons.Default.Storage)
        return
    }
    SettingsItem("Space used", size(use.total) + " in all", Icons.Default.Storage) {
        listOf(
            "Books" to use.books,
            "Covers" to use.covers,
            "Library and history" to use.database,
            "Unpacked books" to use.unpacked,
            "Other cached files" to use.otherCache,
        ).forEach { (label, bytes) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(size(bytes), style = Bindery.type.numeralCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClear, enabled = !busy && use.cache > 0) {
            Icon(Icons.Default.CleaningServices, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Clear cache (${size(use.cache)})")
        }
        Text(
            "Unpacked books are unpacked again the next time they open; nothing you read or marked is lost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Sync through a folder the reader's devices already share. Without one, an explanation
 * and a button to pick it; with one, its name, when it last synced, and the actions.
 */
@Composable
private fun SyncSettings(settings: AppSettings, viewModel: SyncViewModel = hiltViewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::chooseFolder)
    }
    val folder = settings.syncFolder
    if (folder == null) {
        SettingsItem(
            "Sync through a folder",
            "Reading history, places and highlights, kept in step through a folder your devices " +
                "already share: Syncthing, or a cloud drive's folder. No account needed.",
            Icons.Default.FolderShared
        ) {
            OutlinedButton(onClick = { picker.launch(null) }) { Text("Choose folder") }
        }
        return
    }
    val name = remember(folder) { viewModel.folderName(folder) }
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val last = lastSyncAt?.let {
        val now = System.currentTimeMillis()
        if (now - it < DateUtils.MINUTE_IN_MILLIS) "Synced just now"
        else "Last synced " + DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS)
            .toString().replaceFirstChar { c -> c.lowercase() }
    } ?: "Not synced yet"
    SettingsItem(
        "Syncing through “$name”",
        listOfNotNull(viewModel.outcome, last).joinToString(" · "),
        Icons.Default.FolderShared
    ) {
        Text(
            "Syncs when you open and leave the app. Books are matched by title and author; " +
                "the files themselves stay on each device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::syncNow, enabled = !viewModel.isSyncing) {
                if (viewModel.isSyncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Sync now")
            }
            TextButton(onClick = { picker.launch(null) }) { Text("Change") }
            TextButton(onClick = viewModel::stopSyncing) { Text("Stop") }
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSettings() {
    SettingsInfoItem("EBook Reader", "Version ${BuildConfig.VERSION_NAME}", Icons.Default.AutoStories)
    SettingsInfoItem(
        "Supported formats",
        "EPUB, PDF, comics (CBZ, CBR), Kindle (MOBI, AZW3), FB2, plain text and Markdown",
        Icons.Default.Description
    )
    SettingsInfoItem(
        "Private by design",
        "No account, no tracking. Books never reach the network; only catalogues you browse do.",
        Icons.Default.Lock,
    )
    SettingsInfoItem(
        "Typefaces",
        "Literata, Bitter, Atkinson Hyperlegible, OpenDyslexic, Inter Tight and IBM Plex Mono, " +
            "under the SIL Open Font License",
        Icons.Default.FontDownload,
    )
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = Bindery.type.eyebrow,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.padding(start = 36.dp)) { content() }
    }
}

/** A setting that is on or off. The whole row is the switch, for screen readers too. */
@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, detail: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsInfoItem(title: String, subtitle: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

private val AppTheme.displayName get() = when (this) {
    AppTheme.SYSTEM -> "Auto"; AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"; AppTheme.BLACK -> "Black"
}
private val AppTheme.description get() = when (this) {
    AppTheme.SYSTEM -> "Follows the system: light by day, dark at night"
    AppTheme.LIGHT -> "Always light"
    AppTheme.DARK -> "Always dark"
    AppTheme.BLACK -> "Dark, on true black for OLED screens"
}
private val ReadingTheme.description get() = when (this) {
    ReadingTheme.AUTO -> "Matches the app: light pages in light, dark pages in dark"
    ReadingTheme.PAPER -> "$label — dark ink on warm white"
    ReadingTheme.SEPIA -> "$label — brown ink on aged paper"
    ReadingTheme.SLATE -> "$label — soft grey page for dim rooms"
    ReadingTheme.NIGHT -> "$label — light ink on near-black"
    ReadingTheme.BLACK -> "$label — dimmed ink on true black"
}
