package com.ebookreader.presentation.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.ebookreader.domain.model.heatLevel
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.formatSessionTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

/**
 * The bookcloth ramp for the heat map: one hue, five steps, laid over the card so the
 * steps stay in order on any page colour. Empty days are the rule colour, an unread leaf.
 */
@Composable
private fun heatRamp(): List<Color> {
    val c = Bindery.colors
    return listOf(c.rule) + listOf(0.30f, 0.52f, 0.76f, 1f).map { c.bookcloth.copy(alpha = it).compositeOver(c.page) }
}

/**
 * A year of reading as page marks: one narrow mark per day, a week to a column, like the
 * leaves of a fore-edge stained by how long each day was read. Tap a mark for its day.
 */
@Composable
fun DayMarks(days: List<Pair<LocalDate, Long>>, modifier: Modifier = Modifier) {
    val ramp = heatRamp()
    val graphite = Bindery.colors.graphite
    // The outline of a picked mark: the page's ink, which reads against every step.
    val ink = Bindery.colors.ink
    val measurer = rememberTextMeasurer()
    val labelStyle = Bindery.type.numeralCaption.copy(color = graphite)
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val columns = ceil(days.size / 7f).toInt().coerceAtLeast(1)
    val readDays = days.count { heatLevel(it.second) > 0 }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .semantics { contentDescription = "$readDays days with reading in the last year" }
                .pointerInput(days) {
                    detectTapGestures { tap ->
                        val col = (tap.x / (size.width / columns.toFloat())).toInt()
                        val row = ((tap.y - 22.dp.toPx()) / ((size.height - 22.dp.toPx()) / 7f)).toInt()
                        val index = col * 7 + row
                        selected = index.takeIf { row in 0..6 && it in days.indices }
                    }
                }
        ) {
            val top = 22.dp.toPx()
            val slotW = size.width / columns
            val gap = 2.dp.toPx()
            val markW = (slotW - gap).coerceAtLeast(1f)
            val slotH = (size.height - top) / 7f
            val markH = slotH - gap
            var lastLabelCol = -10
            days.forEachIndexed { i, (day, seconds) ->
                val col = i / 7
                val row = i % 7
                val x = col * slotW
                val y = top + row * slotH
                drawRect(ramp[heatLevel(seconds)], Offset(x, y), Size(markW, markH))
                if (i == selected) {
                    drawRect(
                        ink, Offset(x - 1f, y - 1f), Size(markW + 2f, markH + 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)
                    )
                }
                // A month's name over the first week that starts in it, when there is room.
                if (row == 0 && day.dayOfMonth <= 7 && col - lastLabelCol >= 4) {
                    val label = day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    drawText(measurer, label, Offset(x, 0f), labelStyle)
                    lastLabelCol = col
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pick = selected?.let { days.getOrNull(it) }
            Text(
                if (pick != null) "${pick.first.format(DayFormat)} · ${if (pick.second > 0) formatSessionTime(pick.second) else "no reading"}"
                else "Tap a day",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // The scale, since a ramp without one is a guess.
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            ramp.forEach {
                Box(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .size(width = 6.dp, height = 11.dp)
                        .background(it)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/**
 * One series of columns: the current period in bookcloth, the others a lighter step of
 * it. Each column ends in a rounded cap on a square foot. Tap one for its value; the
 * current period's value shows until then.
 */
@Composable
fun PeriodBars(
    values: List<Long>,
    labels: List<String>,
    emphasis: Int,
    describe: (index: Int) -> String,
    modifier: Modifier = Modifier,
) {
    val c = Bindery.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = c.graphite)
    val valueStyle = Bindery.type.numeralCaption.copy(color = c.ink)
    var selected by remember(values) { mutableStateOf(emphasis) }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val muted = c.bookcloth.copy(alpha = 0.42f).compositeOver(c.page)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = values.indices.joinToString("; ") { describe(it) } }
            .pointerInput(values) {
                detectTapGestures { tap ->
                    val slot = size.width / values.size.toFloat()
                    selected = (tap.x / slot).toInt().coerceIn(0, values.lastIndex)
                }
            }
    ) {
        val labelBand = 20.dp.toPx()
        val valueBand = 18.dp.toPx()
        val plotH = size.height - labelBand - valueBand
        val baseline = valueBand + plotH
        val slot = size.width / values.size
        val barW = minOf(24.dp.toPx(), slot - 2.dp.toPx())
        val radius = 4.dp.toPx()

        drawLine(c.rule, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            if (v > 0) {
                val h = (plotH * v / max).coerceAtLeast(radius)
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = cx - barW / 2, top = baseline - h, right = cx + barW / 2, bottom = baseline,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                        )
                    )
                }
                drawPath(path, if (i == emphasis) c.bookcloth else muted)
            }
            val label = measurer.measure(labels[i], labelStyle)
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, baseline + 4.dp.toPx()))
            if (i == selected && v > 0) {
                val text = measurer.measure(formatSessionTime(v), valueStyle)
                val h = (plotH * v / max).coerceAtLeast(radius)
                val x = (cx - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, baseline - h - text.size.height - 2.dp.toPx()))
            }
        }
    }
}

/** Progress towards a goal as a ruled band, the fore-edge laid flat, with the words beside it. */
@Composable
fun GoalMeter(label: String, value: String, progress: Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = Bindery.type.body, modifier = Modifier.weight(1f))
            Text(value, style = Bindery.type.numeralSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.clip(RoundedCornerShape(2.dp))) {
            com.ebookreader.presentation.common.ForeEdge(
                progress = progress.coerceIn(0f, 1f),
                orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                thickness = 8.dp,
            )
        }
    }
}
