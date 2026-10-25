package com.ebookreader.presentation.common

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReadingTheme

private val LocalBinderyColors = staticCompositionLocalOf { BinderyPalettes.Paper }

/**
 * Where an open reader asks for the system bars to match its page. Null while no reader
 * is on screen, so the bars follow the app.
 */
private val LocalReaderBarsDark = staticCompositionLocalOf<MutableState<Boolean?>?> { null }

private val BinderyTypography = BinderyType.Default.toMaterialTypography()

/** The design tokens in scope. Inside a reader these are the page's colours, not the app's. */
object Bindery {
    val colors: BinderyColors
        @Composable @ReadOnlyComposable get() = LocalBinderyColors.current

    val type: BinderyType = BinderyType.Default
}

/**
 * The app's theme. Colours are always the app's own palettes: wallpaper-derived dynamic
 * colour is deliberately not used, because it replaced the palette on most devices,
 * left the app with no consistent look, and could not be checked for contrast.
 */
@Composable
fun EBookReaderTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    val colors = appColorsFor(appTheme, isSystemInDarkTheme())
    val readerBarsDark = remember { mutableStateOf<Boolean?>(null) }
    BinderyTheme(colors) {
        SystemBars(appDark = colors.isDark, readerDark = readerBarsDark)
        CompositionLocalProvider(LocalReaderBarsDark provides readerBarsDark, content = content)
    }
}

/**
 * Re-themes everything inside it — bars, sheets, dialogs — in the colours of the page
 * being read, so a sepia book does not sit inside a white or black frame.
 */
@Composable
fun ReaderTheme(
    appTheme: AppTheme,
    readingTheme: ReadingTheme,
    content: @Composable () -> Unit
) {
    val colors = readingColorsFor(readingTheme, appTheme, isSystemInDarkTheme())
    val barsDark = LocalReaderBarsDark.current
    DisposableEffect(barsDark, colors.isDark) {
        barsDark?.value = colors.isDark
        onDispose { barsDark?.value = null }
    }
    BinderyTheme(colors) {
        Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun BinderyTheme(colors: BinderyColors, content: @Composable () -> Unit) {
    val colorScheme = remember(colors) { colors.toColorScheme() }
    CompositionLocalProvider(LocalBinderyColors provides colors) {
        MaterialTheme(colorScheme = colorScheme, typography = BinderyTypography, content = content)
    }
}

/**
 * The one place the status and navigation bars are styled: the open reader's page if
 * there is one, the app otherwise.
 *
 * Goes through enableEdgeToEdge rather than only flipping the icon colour: its default
 * style picks the bar scrim from the system's dark mode, so a light app on a dark system
 * would otherwise get dark navigation buttons on a dark scrim on API 26-28.
 */
@Composable
private fun SystemBars(appDark: Boolean, readerDark: State<Boolean?>) {
    val view = LocalView.current
    if (view.isInEditMode) return
    // Read here, not in the caller, so a reader opening restyles the bars without
    // recomposing the whole app.
    val dark = readerDark.value ?: appDark
    DisposableEffect(view, dark) {
        (view.context as? ComponentActivity)?.styleSystemBars(darkContent = !dark)
        onDispose { }
    }
}

/** Matches androidx.activity's own fallback scrim, used where icons cannot be made dark. */
private val FallbackDarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

private fun ComponentActivity.styleSystemBars(darkContent: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (darkContent) SystemBarStyle.light(transparent, FallbackDarkScrim)
    else SystemBarStyle.dark(transparent)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
