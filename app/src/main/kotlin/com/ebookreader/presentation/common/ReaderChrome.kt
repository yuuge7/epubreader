package com.ebookreader.presentation.common

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class BarEdge { Top, Bottom }

/**
 * A reader's top or bottom bar: an opaque strip in the page colour with a hairline on
 * the side that faces the text. Printed rather than floating, so no shadow.
 */
@Composable
fun ReaderBar(edge: BarEdge, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            if (edge == BarEdge.Bottom) HorizontalDivider()
            content()
            if (edge == BarEdge.Top) HorizontalDivider()
        }
    }
}

/** The book's fore-edge laid along the bottom bar, with how far in and how long it took. */
@Composable
fun ReaderProgressFooter(
    progress: Float,
    totalReadingSeconds: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // Progress moves with every scroll; easing each step would only make it lag.
        ForeEdge(progress = progress, orientation = Orientation.Horizontal, animate = false)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${(progress * 100).roundToInt()}% read",
                style = Bindery.type.numeralCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val total = formatTotalReadingTime(totalReadingSeconds)
            if (total.isNotEmpty()) {
                Text(
                    "$total in this book",
                    style = Bindery.type.numeralCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
