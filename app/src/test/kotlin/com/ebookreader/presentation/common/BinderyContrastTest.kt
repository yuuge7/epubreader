package com.ebookreader.presentation.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Pins every palette to WCAG contrast targets, so retuning a token cannot quietly
 * produce unreadable text. Covers the hand-picked tokens and the roles derived from them.
 */
class BinderyContrastTest {

    private val palettes = mapOf(
        "app Paper" to BinderyPalettes.Paper,
        "app Night" to BinderyPalettes.Night,
        "app Black" to BinderyPalettes.Black,
        "reading Paper" to ReadingPalettes.Paper,
        "reading Sepia" to ReadingPalettes.Sepia,
        "reading Slate" to ReadingPalettes.Slate,
        "reading Night" to ReadingPalettes.Night,
        "reading Black" to ReadingPalettes.Black,
    )

    @Test
    fun `body text clears AAA on every surface`() = forEachPalette { c, _ ->
        listOf("ink on paper" to (c.ink to c.paper), "ink on page" to (c.ink to c.page))
    }.assertAtLeast(7.0)

    @Test
    fun `secondary and accent text clear AA`() = forEachPalette { c, d ->
        listOf(
            "graphite on paper" to (c.graphite to c.paper),
            "graphite on page" to (c.graphite to c.page),
            "graphite on surfaceVariant" to (c.graphite to d.surfaceVariant),
            "bookcloth on paper" to (c.bookcloth to c.paper),
            "bookcloth on page" to (c.bookcloth to c.page),
            "onBookcloth on bookcloth" to (c.onBookcloth to c.bookcloth),
            "onPrimaryContainer on primaryContainer" to (d.onPrimaryContainer to d.primaryContainer),
            "ink on primaryContainer" to (c.ink to d.primaryContainer),
            "onSecondary on graphite" to (c.page to c.graphite),
            "onTertiary on foil" to (d.onTertiary to c.foil),
            "onTertiaryContainer on tertiaryContainer" to (d.onTertiaryContainer to d.tertiaryContainer),
            "inversePrimary on inverseSurface" to (d.inversePrimary to c.ink),
        )
    }.assertAtLeast(4.5)

    @Test
    fun `component boundaries and graphics clear 3 to 1`() = forEachPalette { c, d ->
        listOf(
            "outline on paper" to (d.outline to c.paper),
            "outline on page" to (d.outline to c.page),
            "foil on paper" to (c.foil to c.paper),
            "foil on page" to (c.foil to c.page),
        )
    }.assertAtLeast(3.0)

    private fun forEachPalette(
        pairs: (BinderyColors, DerivedRoles) -> List<Pair<String, Pair<Color, Color>>>,
    ): List<Pair<String, Double>> = palettes.flatMap { (name, colors) ->
        pairs(colors, DerivedRoles(colors)).map { (label, fgBg) ->
            "$name: $label" to contrast(fgBg.first, fgBg.second)
        }
    }

    private fun List<Pair<String, Double>>.assertAtLeast(target: Double) {
        val failures = filter { it.second < target }
        assertTrue(
            failures.joinToString("\n") { "${it.first} = ${"%.2f".format(it.second)} < $target" },
            failures.isEmpty()
        )
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
