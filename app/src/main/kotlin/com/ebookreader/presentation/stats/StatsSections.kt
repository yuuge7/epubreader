package com.ebookreader.presentation.stats

import android.Manifest
import com.ebookreader.presentation.common.plural
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ebookreader.domain.model.ReadingGoals
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.BinderyCard
import com.ebookreader.presentation.common.ShareCards
import com.ebookreader.presentation.common.formatSessionTime
import com.ebookreader.presentation.common.formatTotalReadingTime
import java.time.format.TextStyle

/** Today, the streak, the goals and the calendar charts, above the totals and leaderboards. */
@Composable
fun ReadingDaysSection(
    days: ReadingDays,
    goals: ReadingGoals,
    onSetGoals: (ReadingGoals) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app follows a language change without a restart; so do the day and month names.
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Bindery.colors
    var editGoals by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Today", formatTotalReadingTime(days.todaySeconds).ifBlank { "0m" }, Modifier.weight(1f))
            StatTile("Streak", plural(days.currentStreak, "day"), Modifier.weight(1f))
            StatTile("Longest", plural(days.longestStreak, "day"), Modifier.weight(1f))
        }

        BinderyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Goals", Modifier.weight(1f))
                    TextButton(onClick = { editGoals = true }) {
                        Icon(Icons.Default.Flag, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (goals.minutesPerDay == 0 && goals.booksPerYear == 0) "Set goals" else "Change")
                    }
                }
                if (goals.minutesPerDay > 0) {
                    val minutes = (days.todaySeconds / 60).toInt()
                    GoalMeter(
                        label = "Minutes today",
                        value = "$minutes of ${goals.minutesPerDay}",
                        progress = minutes / goals.minutesPerDay.toFloat(),
                    )
                }
                if (goals.booksPerYear > 0) {
                    GoalMeter(
                        label = "Books this year",
                        value = "${days.booksFinishedThisYear} of ${goals.booksPerYear}",
                        progress = days.booksFinishedThisYear / goals.booksPerYear.toFloat(),
                    )
                }
                if (goals.minutesPerDay == 0 && goals.booksPerYear == 0) {
                    Text(
                        "A daily reading time or a number of books for the year, measured as you read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        BinderyCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("The last twelve months")
                Spacer(Modifier.height(12.dp))
                DayMarks(days.heatmap)
            }
        }

        if (days.lastWeek.isNotEmpty()) {
            BinderyCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("This week")
                    Spacer(Modifier.height(8.dp))
                    PeriodBars(
                        values = days.lastWeek.map { it.second },
                        labels = days.lastWeek.map {
                            it.first.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                        },
                        emphasis = days.lastWeek.lastIndex,
                        describe = { i ->
                            val (day, s) = days.lastWeek[i]
                            "${day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}: ${formatSessionTime(s)}"
                        },
                    )
                }
            }
        }

        if (days.lastYear.isNotEmpty()) {
            BinderyCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("By month")
                    Spacer(Modifier.height(8.dp))
                    PeriodBars(
                        values = days.lastYear.map { it.second },
                        labels = days.lastYear.map {
                            it.first.month.getDisplayName(TextStyle.NARROW, locale)
                        },
                        emphasis = days.lastYear.lastIndex,
                        describe = { i ->
                            val (month, s) = days.lastYear[i]
                            "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}: ${formatSessionTime(s)}"
                        },
                    )
                }
            }
        }

        days.year?.takeIf { it.totalSeconds > 0 }?.let { year ->
            OutlinedButton(
                onClick = { scope.launch { ShareCards.shareYear(context, year, colors) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share ${year.year} in reading")
            }
        }
    }

    if (editGoals) {
        GoalsDialog(goals, onSave = { onSetGoals(it); editGoals = false }, onDismiss = { editGoals = false })
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Bindery.type.eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** A label and one figure. The figure is the chart. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    BinderyCard(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = Bindery.type.numeral.copy(fontSize = Bindery.type.numeral.fontSize * 0.9f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalsDialog(goals: ReadingGoals, onSave: (ReadingGoals) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(goals.minutesPerDay.takeIf { it > 0 }?.toString().orEmpty()) }
    var books by remember { mutableStateOf(goals.booksPerYear.takeIf { it > 0 }?.toString().orEmpty()) }
    var reminder by remember { mutableStateOf(goals.reminder) }
    val time = rememberTimePickerState(goals.reminderAt / 60, goals.reminderAt % 60, is24Hour = true)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminder = granted
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading goals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes a day") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = books,
                    onValueChange = { books = it.filter(Char::isDigit).take(4) },
                    label = { Text("Books a year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Evening reminder", style = Bindery.type.body)
                        Text(
                            "Only on days the minutes are not in yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = reminder,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else reminder = on
                        },
                    )
                }
                if (reminder) TimeInput(state = time)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ReadingGoals(
                        minutesPerDay = minutes.toIntOrNull() ?: 0,
                        booksPerYear = books.toIntOrNull() ?: 0,
                        reminder = reminder,
                        reminderAt = time.hour * 60 + time.minute,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

