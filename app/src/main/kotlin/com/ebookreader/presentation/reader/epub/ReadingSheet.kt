package com.ebookreader.presentation.reader.epub

import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReaderFont
import com.ebookreader.domain.model.ReadingFocus
import com.ebookreader.domain.model.ReaderLight
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.domain.model.ReadingTheme
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.ReadingThemePicker
import com.ebookreader.presentation.common.BinderySheet
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Type, page and light, changed from inside the book. Opens half-height over a page that
 * keeps showing, so every change is judged on the text itself; the scrim is clear for the
 * same reason. What is used most sits above the fold: size, face and page colour.
 *
 * Light is previewed through onLightChange and saved by onLightCommit once a slider is
 * let go, rather than writing settings on every frame of a drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSheet(
    typography: ReaderTypography,
    light: ReaderLight,
    readingTheme: ReadingTheme,
    appTheme: AppTheme,
    focus: ReadingFocus,
    /** Whether these settings are this book's own, apart from the app's. */
    bookOnly: Boolean,
    onTypographyChange: (ReaderTypography) -> Unit,
    onLightChange: (ReaderLight) -> Unit,
    onLightCommit: () -> Unit,
    onReadingThemeChange: (ReadingTheme) -> Unit,
    onFocusChange: (ReadingFocus) -> Unit,
    onBookOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BinderySheet(onDismissRequest = onDismiss, scrimColor = Color.Transparent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            SizeRow(typography, onTypographyChange)
            SheetSection("Typeface")
            FontPicker(typography.font) { onTypographyChange(typography.copy(font = it)) }
            SheetSection("Page")
            ReadingThemePicker(
                selected = readingTheme,
                appTheme = appTheme,
                onSelect = onReadingThemeChange,
            )

            SheetSection("Layout")
            PageModeRow(typography.paged) { onTypographyChange(typography.copy(paged = it)) }
            if (typography.paged) {
                SwitchRow(
                    label = "Two pages side by side",
                    detail = "In landscape and on large screens",
                    checked = typography.twoColumns,
                    onCheckedChange = { onTypographyChange(typography.copy(twoColumns = it)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            StepperRow(
                label = "Line spacing",
                value = typography.lineHeight,
                format = { String.format(Locale.ROOT, "%.1f", it) },
                step = ReaderTypography.LINE_HEIGHT_STEP,
                range = ReaderTypography.LINE_HEIGHT_MIN..ReaderTypography.LINE_HEIGHT_MAX,
                onChange = { onTypographyChange(typography.copy(lineHeight = it)) },
                decreaseLabel = "Tighter lines",
                increaseLabel = "Looser lines",
            )
            StepperRow(
                label = "Margins",
                value = typography.margin,
                step = ReaderTypography.MARGIN_STEP,
                range = ReaderTypography.MARGIN_MIN..ReaderTypography.MARGIN_MAX,
                onChange = { onTypographyChange(typography.copy(margin = it)) },
                decreaseLabel = "Narrower margins",
                increaseLabel = "Wider margins",
            )
            Spacer(Modifier.height(8.dp))
            AlignmentRow(typography.justify) { onTypographyChange(typography.copy(justify = it)) }
            SwitchRow(
                label = "Hyphenation",
                detail = "Break long words at the end of a line",
                checked = typography.hyphenate,
                onCheckedChange = { onTypographyChange(typography.copy(hyphenate = it)) },
            )

            SheetSection("Reading focus")
            FocusRow(focus, onFocusChange)

            SheetSection("Light")
            BrightnessRow(light, onLightChange, onLightCommit)
            WarmthRow(light, onLightChange, onLightCommit)

            SheetSection("This book")
            SwitchRow(
                label = "Only for this book",
                detail = if (bookOnly) "Type and page here apply to this book alone"
                else "Keep this book's type and page apart from your other books",
                checked = bookOnly,
                onCheckedChange = onBookOnlyChange,
            )
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title.uppercase(),
        style = Bindery.type.eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** Size is the one control used every time, so it gets the top row and big targets. */
@Composable
private fun SizeRow(typography: ReaderTypography, onChange: (ReaderTypography) -> Unit) {
    StepperRow(
        label = "Text size",
        value = typography.fontSize,
        step = ReaderTypography.FONT_SIZE_STEP,
        range = ReaderTypography.FONT_SIZE_MIN..ReaderTypography.FONT_SIZE_MAX,
        onChange = { onChange(typography.copy(fontSize = it)) },
        decreaseLabel = "Smaller text",
        increaseLabel = "Larger text",
        decreaseIcon = { Text("A", style = Bindery.type.bookTitle.copy(fontSize = 13.sp)) },
        increaseIcon = { Text("A", style = Bindery.type.bookTitle.copy(fontSize = 21.sp)) },
    )
}

/** [this] moved by [step] and snapped to its grid, or null once that leaves [range]. */
private fun Float.stepped(step: Float, range: ClosedFloatingPointRange<Float>): Float? {
    // Steps of 0.1 drift in binary; snapping keeps 1.6 at 1.6.
    val size = kotlin.math.abs(step)
    val next = ((this + step) / size).roundToInt() * size
    val slack = size / 100
    return next.takeIf { it >= range.start - slack && it <= range.endInclusive + slack }
        ?.coerceIn(range)
}

/** A value nudged a step at a time between two limits; a button greys out at its limit. */
@Composable
private fun StepperRow(
    label: String,
    value: Float,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    format: (Float) -> String = { it.roundToInt().toString() },
    decreaseIcon: @Composable () -> Unit = { Icon(Icons.Default.Remove, null) },
    increaseIcon: @Composable () -> Unit = { Icon(Icons.Default.Add, null) },
) {
    val shown = format(value)
    val lower = value.stepped(-step, range)
    val higher = value.stepped(step, range)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Bindery.type.body, modifier = Modifier.weight(1f))
        OutlinedIconButton(
            onClick = { lower?.let(onChange) },
            enabled = lower != null,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = decreaseLabel },
        ) { decreaseIcon() }
        Text(
            shown,
            style = Bindery.type.numeral.copy(fontSize = 17.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(56.dp)
                .semantics { contentDescription = "$label $shown" },
        )
        OutlinedIconButton(
            onClick = { higher?.let(onChange) },
            enabled = higher != null,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = increaseLabel },
        ) { increaseIcon() }
    }
}

/** Each face is shown set in itself, so the choice is made by looking, not by name. */
@Composable
private fun FontPicker(selected: ReaderFont, onSelect: (ReaderFont) -> Unit) {
    val colors = Bindery.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReaderFont.entries.forEach { font ->
            val isSelected = font == selected
            val shape = RoundedCornerShape(4.dp)
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(shape)
                    .selectable(selected = isSelected, onClick = { onSelect(font) }, role = Role.RadioButton)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) colors.bookcloth else colors.rule,
                        shape = shape,
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Fixed height: each face has its own ascent and descent, and the tiles
                // should line up whatever they hold.
                Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Aa",
                        fontFamily = font.face?.preview ?: FontFamily.Serif,
                        fontSize = 24.sp,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    font.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) colors.bookcloth else colors.graphite,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One long scroll per chapter, or pages turned sideways. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageModeRow(paged: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !paged,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp)) },
        ) { Text("Scroll") }
        SegmentedButton(
            selected = paged,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)) },
        ) { Text("Pages") }
    }
}

/** A band to read along, or the paragraphs around the one being read dimmed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusRow(focus: ReadingFocus, onChange: (ReadingFocus) -> Unit) {
    val options = listOf(ReadingFocus.OFF to "Off", ReadingFocus.RULER to "Ruler", ReadingFocus.DIM to "Dim the rest")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = focus == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
    if (focus != ReadingFocus.OFF) {
        Text(
            "Drag the tab at the right edge to move the reading line.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlignmentRow(justify: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !justify,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, null, Modifier.size(18.dp)) },
        ) { Text("Left") }
        SegmentedButton(
            selected = justify,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.Default.FormatAlignJustify, null, Modifier.size(18.dp)) },
        ) { Text("Justified") }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Bindery.type.body)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row is the toggle; the switch only shows its state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Brightness for the reader only. "Auto" hands it back to the system; dragging takes it
 * over, starting from roughly where the system had it so the screen does not jump.
 */
@Composable
private fun BrightnessRow(light: ReaderLight, onChange: (ReaderLight) -> Unit, onCommit: () -> Unit) {
    val context = LocalContext.current
    val systemLevel = remember {
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
            // Some devices count past 255; only a starting point is wanted here.
            .coerceIn(ReaderLight.MIN_BRIGHTNESS, 1f)
    }
    val shown = if (light.followsSystem) systemLevel else light.brightness
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.BrightnessLow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown,
            onValueChange = { onChange(light.copy(brightness = it.coerceAtLeast(ReaderLight.MIN_BRIGHTNESS))) },
            onValueChangeFinished = onCommit,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = "Brightness"
                    if (light.followsSystem) stateDescription = "Automatic"
                },
        )
        Icon(Icons.Default.BrightnessHigh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = light.followsSystem,
            onClick = {
                onChange(
                    if (light.followsSystem) light.copy(brightness = systemLevel)
                    else light.copy(brightness = ReaderLight.SYSTEM)
                )
                onCommit()
            },
            label = { Text("Auto") },
            leadingIcon = if (light.followsSystem) {
                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
            } else null,
        )
    }
}

/** An amber wash over the page and the reader's bars, for reading in bed. */
@Composable
private fun WarmthRow(light: ReaderLight, onChange: (ReaderLight) -> Unit, onCommit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.WbTwilight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = light.warmth,
            onValueChange = { onChange(light.copy(warmth = it)) },
            onValueChangeFinished = onCommit,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = "Warmth" },
        )
        Text(
            "${(light.warmth * 100).roundToInt()}%",
            style = Bindery.type.numeralSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}
