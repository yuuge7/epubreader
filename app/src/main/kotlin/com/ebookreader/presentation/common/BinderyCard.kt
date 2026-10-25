package com.ebookreader.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A card printed on the page: page-coloured, edged with a rule hairline, no shadow.
 * [selected] marks the current item in a list (this chapter's bookmark, say) with the
 * accent wash and an accent edge.
 */
@Composable
fun BinderyCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest
    )
    val edge = if (selected) scheme.primary else scheme.outlineVariant
    val border = remember(edge) { BorderStroke(1.dp, edge) }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, colors = colors, border = border, content = content)
    }
}
