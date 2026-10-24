package com.ebookreader.presentation.common

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ebookreader.R

/*
 * Three faces, bundled so the app stays fully offline:
 *  - Literata: book titles and headings. Designed for e-reading, with an optical-size axis.
 *  - Inter Tight: all UI chrome. Dense and neutral; does not compete with Literata.
 *  - IBM Plex Mono: numerals only. Fixed-width digits stop a ticking clock from
 *    re-laying-out its row every second.
 *
 * Literata and Inter Tight are variable fonts. Each family registers the weights the UI
 * can ask for as real instances, so a stray FontWeight.Bold at a call site selects a
 * drawn bold instead of smearing a synthetic one.
 */

private val UiWeights = listOf(400, 500, 600, 700)

/** A variable font registered at every UI weight, with any extra axes held fixed. */
private fun variableFamily(@FontRes font: Int, vararg fixedAxes: FontVariation.Setting) =
    FontFamily(
        UiWeights.map { w ->
            Font(
                font,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w), *fixedAxes),
            )
        }
    )

/** Literata at a fixed optical size: tighter, higher-contrast forms at display sizes. */
private val LiterataDisplay = variableFamily(R.font.literata, FontVariation.Setting("opsz", 36f))
private val LiterataText = variableFamily(R.font.literata, FontVariation.Setting("opsz", 16f))
private val InterTight = variableFamily(R.font.inter_tight)

private val PlexMono = FontFamily(Font(R.font.plex_mono_medium, FontWeight.Medium))

/**
 * Seven roles plus the reader's own face, which belongs to the user. Material's fifteen
 * slots are mapped onto these by [toMaterialTypography]; new UI should name a role here.
 */
@Immutable
data class BinderyType(
    val display: TextStyle,
    val title: TextStyle,
    val bookTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /** Small caps-height kicker above a heading. Uppercase the string at the call site. */
    val eyebrow: TextStyle,
    /** Timers, statistics, percentages. */
    val numeral: TextStyle,
) {
    /** The numeral role at inline size: a running clock, a duration in a row. */
    val numeralSmall: TextStyle = numeral.copy(fontSize = 13.sp, lineHeight = 18.sp)

    /** The numeral role at caption size: a percentage or read time under a title. */
    val numeralCaption: TextStyle = numeral.copy(fontSize = 11.sp, lineHeight = 15.sp)

    companion object {
        val Default = BinderyType(
            display = TextStyle(
                fontFamily = LiterataDisplay, fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-0.02).em,
            ),
            title = TextStyle(
                fontFamily = LiterataDisplay, fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em,
            ),
            bookTitle = TextStyle(
                fontFamily = LiterataText, fontWeight = FontWeight.Medium,
                fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.em,
            ),
            body = TextStyle(
                fontFamily = InterTight, fontWeight = FontWeight.Normal,
                fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.em,
            ),
            label = TextStyle(
                fontFamily = InterTight, fontWeight = FontWeight.Medium,
                fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.01.em,
            ),
            eyebrow = TextStyle(
                fontFamily = InterTight, fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em,
            ),
            numeral = TextStyle(
                fontFamily = PlexMono, fontWeight = FontWeight.Medium,
                fontSize = 20.sp, lineHeight = 20.sp, letterSpacing = 0.em,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/** Material's slots, each resolved to one of the roles above at the size the slot implies. */
internal fun BinderyType.toMaterialTypography(): Typography = Typography(
    displayLarge = display.copy(fontSize = 48.sp, lineHeight = 53.sp),
    displayMedium = display.copy(fontSize = 40.sp, lineHeight = 45.sp),
    displaySmall = display,
    headlineLarge = display.copy(fontSize = 30.sp, lineHeight = 35.sp),
    headlineMedium = display.copy(fontSize = 26.sp, lineHeight = 31.sp),
    headlineSmall = title,
    titleLarge = title,
    titleMedium = bookTitle.copy(fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = label.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = body,
    bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = body.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = label.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = label,
    labelSmall = label.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.02.em),
)
