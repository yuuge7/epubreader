package com.ebookreader.presentation.reader.epub

import com.ebookreader.data.epub.insertAtEndOfHead
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.presentation.common.BinderyColors
import com.ebookreader.presentation.common.ReadingPalettes
import com.ebookreader.presentation.common.toCssHex
import java.util.Locale

/** The id of the one style element the reader owns in every chapter. */
internal const val READER_STYLE_ID = "reader-style"

/** Class the page script puts on paragraphs whose alignment belongs to the reader. */
internal const val READER_ALIGN_CLASS = "__ra"

/** A reading theme's colours, as the page's stylesheet uses them. */
internal data class PageColors(
    val background: String,
    val text: String,
    val link: String,
    val rule: String,
    val selection: String,
    val dark: Boolean,
    /** Multiplier for images on a dark page, so a white figure does not glare. 1 = untouched. */
    val imageBrightness: Float,
) {
    companion object {
        fun of(c: BinderyColors) = PageColors(
            background = c.paper.toCssHex(),
            text = c.ink.toCssHex(),
            link = c.bookcloth.toCssHex(),
            rule = c.rule.toCssHex(),
            selection = c.bookcloth.copy(alpha = if (c.isDark) 0.45f else 0.25f).toCssRgba(),
            dark = c.isDark,
            imageBrightness = when {
                !c.isDark -> 1f
                // True black: the dimmest page, so the figures come down furthest.
                c == ReadingPalettes.Black -> 0.75f
                else -> 0.85f
            },
        )
    }
}

/** Everything a chapter's look depends on, resolved to plain values. */
internal data class PageStyle(
    val typography: ReaderTypography,
    val colors: PageColors,
    /** The system font scale, applied on top of the chosen size. */
    val fontScale: Float = 1f,
)

/** Breathing room above the first line of a chapter. */
private const val TOP_CLEARANCE = 16f

/** Clear of the reader's bottom bar when it is shown over the end of a chapter. */
private const val END_CLEARANCE = 96f

/**
 * The stylesheet the reader lays over every chapter.
 *
 * Publisher stylesheets set fonts, sizes, spacing and colours on p, div and span, which
 * beat any rule on body alone; the user's choices are therefore forced onto descendants
 * with !important, which in an author stylesheet also beats the book's inline styles.
 * Headings keep their own sizes and a tighter leading, and code keeps its monospace.
 */
internal fun readerCss(style: PageStyle): String {
    val t = style.typography
    val c = style.colors
    val face = t.font.face
    val size = t.fontSize * style.fontScale
    val hyphens = if (t.hyphenate) "auto" else "manual"
    val align = if (t.justify) "justify" else "start"

    return buildString {
        face?.files?.forEach { file ->
            append("@font-face{font-family:'${face.cssFamily}';")
            append("src:url('${ReaderOrigin.FONTS_PATH}${file.fileName}');")
            append("font-weight:${file.weight};font-style:${if (file.italic) "italic" else "normal"};")
            // Local files: wait for the face rather than flashing the fallback first.
            append("font-display:block;}\n")
        }
        append("html{scroll-behavior:auto!important;}\n")
        append("html,body{color:${c.text}!important;background-color:${c.background}!important;}\n")
        append("body{margin:0!important;")
        append("padding:${px(TOP_CLEARANCE)} ${px(t.margin)} ${px(END_CLEARANCE)} ${px(t.margin)}!important;")
        append("font-size:${px(size)}!important;line-height:${num(t.lineHeight)}!important;")
        if (face != null) append("font-family:'${face.cssFamily}',${face.fallback}!important;")
        append("overflow-wrap:break-word;")
        append("-webkit-hyphens:$hyphens!important;hyphens:$hyphens!important;}\n")
        append("body *:not(h1):not(h2):not(h3):not(h4):not(h5):not(h6):not(sup):not(sub):not(small)")
        append("{font-size:inherit!important;}\n")
        append("body *:not(h1):not(h2):not(h3):not(h4):not(h5):not(h6){line-height:inherit!important;}\n")
        append("body *{-webkit-hyphens:inherit!important;hyphens:inherit!important;}\n")
        append("h1,h2,h3,h4,h5,h6{line-height:1.3!important;}\n")
        if (face != null) {
            append("body *:not(pre):not(code):not(kbd):not(samp):not(tt):not(pre *)")
            append("{font-family:inherit!important;}\n")
        }
        append(".$READER_ALIGN_CLASS{text-align:$align!important;}\n")
        append("img{max-width:100%!important;height:auto;}svg{max-width:100%;}\n")
        append("::selection{background-color:${c.selection};}\n")
        if (c.dark) {
            // A book's own colours assume a white page: dark text, white boxes, black
            // rules. On a dark page every one of them has to go, or a passage set in
            // #333 disappears into the background.
            append("body *{color:inherit!important;background-color:transparent!important;")
            append("border-color:${c.rule}!important;}\n")
        }
        // Only real links: an <a id> is a place in the text, not something to tap.
        append("a[href],a[href]:visited,a[href] *{color:${c.link}!important;}\n")
        if (c.imageBrightness < 1f) {
            append("img,svg image,video{filter:brightness(${num(c.imageBrightness)});}\n")
        }
    }
}

/** The chapter with the reader's stylesheet as the last word in its head. */
internal fun chapterDocument(html: String, css: String): String =
    insertAtEndOfHead(html, """<style id="$READER_STYLE_ID">$css</style>""")

private fun px(value: Float) = num(value) + "px"

/** CSS wants a plain decimal point whatever the device's language. */
private fun num(value: Float): String =
    String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

private fun androidx.compose.ui.graphics.Color.toCssRgba(): String = String.format(
    Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
    (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), alpha,
)
