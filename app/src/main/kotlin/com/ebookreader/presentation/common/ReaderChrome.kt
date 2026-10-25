package com.ebookreader.presentation.common

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
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

/**
 * The app's bottom sheet. A sheet is its own window and styles the status and navigation
 * bar icons itself, from the *system's* dark mode by default: a sepia page on a phone in
 * dark mode got white icons on a light page, and a Light app theme on a dark system the
 * same. Here they follow the colours in scope, from the first frame and for as long as
 * the sheet is open, including when the sheet itself changes the theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinderySheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val light = !Bindery.colors.isDark
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        scrimColor = scrimColor,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = light,
            isAppearanceLightNavigationBars = light,
        ),
    ) {
        // The properties are read only when the sheet's window is made.
        BarsFollowTheme(light)
        content()
    }
}

@Composable
private fun BarsFollowTheme(light: Boolean) {
    val view = LocalView.current
    SideEffect {
        var parent = view.parent
        while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
        val window = (parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
