package com.ebookreader.presentation.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The current sitting's reading time, set in the numeral face so it holds still as it ticks. */
@Composable
fun SessionClock(seconds: Long, modifier: Modifier = Modifier) {
    Text(
        formatClock(seconds),
        style = Bindery.type.numeralSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier.semantics {
            contentDescription = "Reading for ${formatSessionTime(seconds)}"
        }
    )
}
