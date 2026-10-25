package com.ebookreader.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReadingTheme

/**
 * Reading themes shown as small pages set in their own colours, so the choice is made by
 * looking at the page rather than by reading a name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingThemePicker(
    selected: ReadingTheme,
    appTheme: AppTheme,
    onSelect: (ReadingTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReadingTheme.entries.forEach { theme ->
            PageSwatch(
                theme = theme,
                appTheme = appTheme,
                selected = theme == selected,
                onClick = { onSelect(theme) },
            )
        }
    }
}

@Composable
private fun PageSwatch(
    theme: ReadingTheme,
    appTheme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val frame = Bindery.colors
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 56.dp)
                .clip(shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) frame.bookcloth else frame.rule,
                    shape = shape,
                ),
        ) {
            // Auto is shown as what it can resolve to: split when it follows the system,
            // a plain page when the app theme pins it to one palette.
            val day = readingColorsFor(theme, appTheme, systemDark = false)
            val night = readingColorsFor(theme, appTheme, systemDark = true)
            if (day != night) {
                SplitPage(day, night)
            } else {
                val page = day
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(page.paper),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", style = Bindery.type.bookTitle.copy(fontSize = 17.sp), color = page.ink)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) frame.bookcloth else frame.graphite,
        )
    }
}

/** A page that changes with the system, cut diagonally: light by day, dark by night. */
@Composable
private fun SplitPage(day: BinderyColors, night: BinderyColors) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(day.paper)
            val cut = Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(cut, night.paper)
        }
        Text(
            "A", style = Bindery.type.bookTitle.copy(fontSize = 15.sp), color = day.ink,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 6.dp),
        )
        Text(
            "a", style = Bindery.type.bookTitle.copy(fontSize = 15.sp), color = night.ink,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 6.dp),
        )
    }
}

val ReadingTheme.label: String
    get() = when (this) {
        ReadingTheme.AUTO -> "Auto"
        ReadingTheme.PAPER -> "Paper"
        ReadingTheme.SEPIA -> "Sepia"
        ReadingTheme.SLATE -> "Slate"
        ReadingTheme.NIGHT -> "Night"
        ReadingTheme.BLACK -> "Black"
    }
