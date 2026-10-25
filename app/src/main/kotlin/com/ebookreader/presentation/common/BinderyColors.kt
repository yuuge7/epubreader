package com.ebookreader.presentation.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ebookreader.domain.model.AppTheme
import com.ebookreader.domain.model.ReadingTheme
import java.util.Locale

/**
 * The seven colour tokens everything else derives from. The same shape describes the app
 * chrome and each reading surface, so the reader's own bars and sheets can be dressed in
 * the page's colours by the same derivation the library uses.
 *
 * Values are pinned; BinderyContrastTest fails if one stops meeting its contrast target.
 */
@Immutable
data class BinderyColors(
    /** Window background; for a reading surface, the page itself. */
    val paper: Color,
    /** Raised surfaces: cards, sheets, dialogs, menus. */
    val page: Color,
    /** Primary text. Never pure white on dark: it flares at night. */
    val ink: Color,
    /** Secondary text and metadata. */
    val graphite: Color,
    /** Hairlines, dividers, card borders. */
    val rule: Color,
    /** The accent: progress, active state, primary action, links. */
    val bookcloth: Color,
    /** Text and icons drawn on [bookcloth]. */
    val onBookcloth: Color,
    /** Finished books and achievements. Nothing else. */
    val foil: Color,
    val isDark: Boolean,
)

object BinderyPalettes {
    val Paper = BinderyColors(
        paper = Color(0xFFFBF9F4),
        page = Color(0xFFFFFFFF),
        ink = Color(0xFF171A1F),
        graphite = Color(0xFF6A6E77),
        rule = Color(0xFFE2DED4),
        bookcloth = Color(0xFF7A3033),
        onBookcloth = Color(0xFFFFFFFF),
        foil = Color(0xFFA98548),
        isDark = false,
    )

    val Night = BinderyColors(
        paper = Color(0xFF0F1013),
        page = Color(0xFF16181C),
        ink = Color(0xFFDCD8D0),
        graphite = Color(0xFF8B8F98),
        rule = Color(0xFF262930),
        bookcloth = Color(0xFFC4736C),
        onBookcloth = Color(0xFF1C0F0E),
        foil = Color(0xFFC6A468),
        isDark = true,
    )

    /** Night with true black, for OLED screens. */
    val Black = Night.copy(
        paper = Color(0xFF000000),
        page = Color(0xFF0E0F12),
        rule = Color(0xFF1E2026),
    )
}

/** Reading surfaces. Separate from the app palettes, even where they start from them. */
object ReadingPalettes {
    val Paper = BinderyPalettes.Paper

    val Sepia = BinderyColors(
        paper = Color(0xFFF3EAD8),
        page = Color(0xFFF9F3E7),
        ink = Color(0xFF3B2F22),
        graphite = Color(0xFF6F604D),
        rule = Color(0xFFDDD0B8),
        bookcloth = Color(0xFF8A3B2E),
        onBookcloth = Color(0xFFFFFFFF),
        foil = Color(0xFF9E7B40),
        isDark = false,
    )

    /** A mid-dark page for dim rooms, softer than Night. */
    val Slate = BinderyColors(
        paper = Color(0xFF2A2E35),
        page = Color(0xFF32373F),
        ink = Color(0xFFD3D6DB),
        graphite = Color(0xFFA0A7B1),
        rule = Color(0xFF3B414A),
        bookcloth = Color(0xFFE39A92),
        onBookcloth = Color(0xFF2A1210),
        foil = Color(0xFFD2B27A),
        isDark = true,
    )

    /** Text a step dimmer than the app's Night ink: a page is stared at far longer. */
    val Night = BinderyPalettes.Night.copy(ink = Color(0xFFD3CFC7))

    /** Dimmer again on true black, where any bright stroke blooms. */
    val Black = BinderyPalettes.Black.copy(
        ink = Color(0xFFBDB9B1),
        graphite = Color(0xFF83878F),
        bookcloth = Color(0xFFB8665F),
        foil = Color(0xFFB89A60),
    )
}

fun appColorsFor(theme: AppTheme, systemDark: Boolean): BinderyColors = when (theme) {
    AppTheme.SYSTEM -> if (systemDark) BinderyPalettes.Night else BinderyPalettes.Paper
    AppTheme.LIGHT -> BinderyPalettes.Paper
    AppTheme.DARK -> BinderyPalettes.Night
    AppTheme.BLACK -> BinderyPalettes.Black
}

fun readingColorsFor(
    reading: ReadingTheme,
    app: AppTheme,
    systemDark: Boolean,
): BinderyColors = when (reading) {
    ReadingTheme.AUTO -> when {
        app == AppTheme.BLACK -> ReadingPalettes.Black
        appColorsFor(app, systemDark).isDark -> ReadingPalettes.Night
        else -> ReadingPalettes.Paper
    }
    ReadingTheme.PAPER -> ReadingPalettes.Paper
    ReadingTheme.SEPIA -> ReadingPalettes.Sepia
    ReadingTheme.SLATE -> ReadingPalettes.Slate
    ReadingTheme.NIGHT -> ReadingPalettes.Night
    ReadingTheme.BLACK -> ReadingPalettes.Black
}

/**
 * Straight per-channel blend in sRGB. Compose's own lerp works in Oklab, which reads
 * nicer for animation but would make the derived roles below impossible to check by
 * hand; this one is what the contrast test reproduces.
 */
internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

/** The derived Material roles, exposed so the contrast test checks exactly what ships. */
internal class DerivedRoles(c: BinderyColors) {
    val primaryContainer = mix(c.page, c.bookcloth, if (c.isDark) 0.24f else 0.12f)
    val onPrimaryContainer = mix(c.bookcloth, c.ink, if (c.isDark) 0.7f else 0.45f)
    val tertiaryContainer = mix(c.page, c.foil, if (c.isDark) 0.24f else 0.16f)
    val onTertiaryContainer = mix(c.foil, c.ink, if (c.isDark) 0.7f else 0.6f)
    val onTertiary = if (c.isDark) c.paper else mix(c.ink, Color.Black, 0.6f)
    val surfaceVariant = mix(c.paper, c.rule, 0.25f)
    val outline = mix(c.graphite, c.paper, 0.2f)
    val inversePrimary = mix(c.bookcloth, c.paper, 0.65f)
}

/**
 * Maps the tokens onto Material's roles so every stock component inherits them. All
 * raised containers resolve to [BinderyColors.page]: surfaces are printed, not floating,
 * and the hierarchy comes from hairlines rather than from tonal steps.
 */
fun BinderyColors.toColorScheme(): ColorScheme {
    val d = DerivedRoles(this)
    // Only the error roles are taken from the Material baseline.
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = bookcloth,
        onPrimary = onBookcloth,
        primaryContainer = d.primaryContainer,
        onPrimaryContainer = d.onPrimaryContainer,
        inversePrimary = d.inversePrimary,
        // Selected chips, slider tracks and the like are an active state, so they take
        // the bookcloth wash rather than a second, unrelated accent.
        secondary = graphite,
        onSecondary = page,
        secondaryContainer = d.primaryContainer,
        onSecondaryContainer = d.onPrimaryContainer,
        tertiary = foil,
        onTertiary = d.onTertiary,
        tertiaryContainer = d.tertiaryContainer,
        onTertiaryContainer = d.onTertiaryContainer,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = d.surfaceVariant,
        onSurfaceVariant = graphite,
        // Tonal elevation tints a surface towards this colour; paper over paper is a no-op.
        surfaceTint = paper,
        inverseSurface = ink,
        inverseOnSurface = paper,
        outline = d.outline,
        outlineVariant = rule,
        scrim = Color.Black,
        surfaceBright = page,
        surfaceDim = d.surfaceVariant,
        surfaceContainerLowest = page,
        surfaceContainerLow = page,
        surfaceContainer = page,
        surfaceContainerHigh = page,
        surfaceContainerHighest = page,
    )
}

/** "#RRGGBB", for colours handed to a WebView stylesheet. */
fun Color.toCssHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
