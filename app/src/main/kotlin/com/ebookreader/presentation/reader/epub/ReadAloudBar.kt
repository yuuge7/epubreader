package com.ebookreader.presentation.reader.epub

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebookreader.data.readaloud.ReadAloudState
import com.ebookreader.presentation.common.BarEdge
import com.ebookreader.presentation.common.Bindery
import com.ebookreader.presentation.common.ReaderBar
import java.util.Locale

/** The speeds the rate button steps through, slowest first. */
private val Rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Read-aloud's controls, at the foot of the page while it reads: the chapter, a sentence
 * back, play or pause, a sentence on, the speed, and stop.
 */
@Composable
internal fun ReadAloudBar(
    state: ReadAloudState,
    insetBottom: Boolean,
    onToggle: () -> Unit,
    onSkip: (Int) -> Unit,
    onRate: (Float) -> Unit,
    onStop: () -> Unit,
) {
    ReaderBar(BarEdge.Bottom) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (insetBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("READING ALOUD", style = Bindery.type.eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    state.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onSkip(-1) }) { Icon(Icons.Default.SkipPrevious, "Previous sentence") }
            FilledIconButton(onClick = onToggle) {
                if (state.isPlaying) Icon(Icons.Default.Pause, "Pause")
                else Icon(Icons.Default.PlayArrow, "Play")
            }
            IconButton(onClick = { onSkip(+1) }) { Icon(Icons.Default.SkipNext, "Next sentence") }
            val label = formatRate(state.rate)
            TextButton(
                onClick = { onRate(Rates.firstOrNull { it > state.rate + 0.01f } ?: Rates.first()) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "Speed $label, tap to change" },
            ) {
                Text(label, style = Bindery.type.numeralSmall)
            }
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop reading aloud") }
        }
    }
}

/** "1×", "1.25×", "0.75×". */
private fun formatRate(rate: Float): String =
    String.format(Locale.getDefault(), "%.2f", rate).trimEnd('0').trimEnd('.', ',') + "×"
