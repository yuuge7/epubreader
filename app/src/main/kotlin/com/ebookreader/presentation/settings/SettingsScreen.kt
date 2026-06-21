package com.ebookreader.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.ebookreader.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Appearance ────────────────────────────────────────────────
            SettingsSectionHeader("Appearance", Icons.Default.Palette)

            SettingsItem("Theme", settings.theme.displayName, Icons.Default.DarkMode) {
                ThemeSelector(
                    currentTheme = settings.theme
                ) { onSettingsChange(settings.copy(theme = it)) }
            }

            SettingsItem("Font Size", "${settings.fontSize.toInt()}sp", Icons.Default.FormatSize) {
                Column {
                    Slider(
                        value = settings.fontSize,
                        onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
                        valueRange = 10f..32f,
                        steps = 21,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("A", style = MaterialTheme.typography.bodySmall)
                        Text("A", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Reading ───────────────────────────────────────────────────
            SettingsSectionHeader("Reading", Icons.AutoMirrored.Filled.MenuBook)

            SettingsToggleItem(
                title = "Keep Screen On",
                subtitle = "Prevent screen from sleeping while reading",
                icon = Icons.Default.ScreenLockPortrait,
                checked = settings.keepScreenOn
            ) { onSettingsChange(settings.copy(keepScreenOn = it)) }

            // Scroll direction — applies to BOTH PDF and EPUB
            SettingsItem(
                title = "Scroll Direction",
                subtitle = when (settings.readerScrollDirection) {
                    ScrollDirection.VERTICAL -> "Vertical — scroll up/down (PDF & EPUB)"
                    ScrollDirection.HORIZONTAL -> "Horizontal — swipe left/right (PDF & EPUB)"
                },
                icon = when (settings.readerScrollDirection) {
                    ScrollDirection.VERTICAL -> Icons.Default.SwipeDown
                    ScrollDirection.HORIZONTAL -> Icons.Default.SwipeRight
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScrollDirection.entries.forEach { dir ->
                        FilterChip(
                            selected = settings.readerScrollDirection == dir,
                            onClick = { onSettingsChange(settings.copy(readerScrollDirection = dir)) },
                            label = {
                                Text(
                                    if (dir == ScrollDirection.VERTICAL) "Vertical"
                                    else "Horizontal"
                                )
                            },
                            leadingIcon = if (settings.readerScrollDirection == dir) {
                                { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Library ───────────────────────────────────────────────────
            SettingsSectionHeader("Library", Icons.AutoMirrored.Filled.LibraryBooks)

            SettingsItem("Default Sort", settings.sortOption.displayName, Icons.AutoMirrored.Filled.Sort) {
                SortSelector(
                    currentSort = settings.sortOption,
                    onSortSelected = { onSettingsChange(settings.copy(sortOption = it)) }
                )
            }

            SettingsToggleItem(
                title = "Grid View",
                subtitle = "Show books as a grid (toggle for list view)",
                icon = Icons.Default.GridView,
                checked = settings.isGridView,
                onCheckedChange = { onSettingsChange(settings.copy(isGridView = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── About ─────────────────────────────────────────────────────
            SettingsSectionHeader("About", Icons.Default.Info)
            SettingsInfoItem("EBook Reader", "Version 1.0.0", Icons.Default.AutoStories)
            SettingsInfoItem("Supported Formats", "PDF, EPUB", Icons.Default.Description)

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
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

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

@Composable
private fun ThemeSelector(currentTheme: AppTheme, onThemeSelected: (AppTheme) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTheme.entries.forEach { theme ->
            FilterChip(
                selected = currentTheme == theme,
                onClick = { onThemeSelected(theme) },
                label = { Text(theme.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (currentTheme == theme) {
                    { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SortSelector(currentSort: SortOption, onSortSelected: (SortOption) -> Unit) {
    val options = SortOption.entries
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in options.indices step 2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = currentSort == options[i],
                    onClick = { onSortSelected(options[i]) },
                    label = { Text(options[i].displayName) },
                    leadingIcon = if (currentSort == options[i]) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                if ((i + 1) < options.size) {
                    FilterChip(
                        selected = currentSort == options[i + 1],
                        onClick = { onSortSelected(options[i + 1]) },
                        label = { Text(options[i + 1].displayName) },
                        leadingIcon = if (currentSort == options[i + 1]) {
                            { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// Extension display names
private val AppTheme.displayName get() = when (this) {
    AppTheme.SYSTEM -> "Auto"; AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"; AppTheme.SEPIA -> "Sepia"
}
private val SortOption.displayName get() = when (this) {
    SortOption.TITLE -> "Title"; SortOption.AUTHOR -> "Author"
    SortOption.DATE_ADDED -> "Date Added"; SortOption.LAST_READ -> "Last Read"
}
