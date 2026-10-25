package com.ebookreader.presentation.reader.epub

import com.ebookreader.domain.model.ReaderFont
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.presentation.common.ReadingPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleTest {

    private fun css(
        typography: ReaderTypography = ReaderTypography(),
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) = readerCss(
        PageStyle(
            typography = typography,
            colors = PageColors.of(if (dark) ReadingPalettes.Night else ReadingPalettes.Paper),
            fontScale = fontScale,
        )
    )

    @Test
    fun `the chosen face is declared for every file it ships in, and nothing else is`() {
        val sheet = css(ReaderTypography(font = ReaderFont.ATKINSON))
        assertEquals(4, Regex("@font-face").findAll(sheet).count())
        assertTrue(sheet.contains("url('/fonts/atkinson_hyperlegible_bold_italic.ttf')"))
        assertFalse(sheet.contains("literata"))
        assertTrue(sheet.contains("font-family:'Reader Atkinson Hyperlegible',sans-serif!important"))
    }

    @Test
    fun `a variable face is declared across its weight range`() {
        val sheet = css(ReaderTypography(font = ReaderFont.LITERATA))
        assertTrue(sheet.contains("font-weight:400 700;font-style:normal"))
        assertTrue(sheet.contains("font-weight:400 700;font-style:italic"))
    }

    @Test
    fun `the publisher's own fonts are left alone`() {
        val sheet = css(ReaderTypography(font = ReaderFont.PUBLISHER))
        assertFalse(sheet.contains("@font-face"))
        assertFalse(sheet.contains("font-family"))
    }

    @Test
    fun `size takes the system font scale and spacing is plain CSS whatever the locale`() {
        val sheet = css(ReaderTypography(fontSize = 20f, lineHeight = 1.7f, margin = 24f), fontScale = 1.3f)
        assertTrue(sheet, sheet.contains("font-size:26px!important"))
        assertTrue(sheet, sheet.contains("line-height:1.7!important"))
        // A little air on top, and room below for the bottom bar over a chapter's end.
        assertTrue(sheet, sheet.contains("padding:16px 24px 96px 24px!important"))
    }

    @Test
    fun `alignment and hyphenation follow the settings`() {
        val ragged = css(ReaderTypography(justify = false, hyphenate = false))
        assertTrue(ragged.contains(".$READER_ALIGN_CLASS{text-align:start!important;}"))
        assertTrue(ragged.contains("hyphens:manual!important"))

        val set = css(ReaderTypography(justify = true, hyphenate = true))
        assertTrue(set.contains(".$READER_ALIGN_CLASS{text-align:justify!important;}"))
        assertTrue(set.contains("hyphens:auto!important"))
    }

    @Test
    fun `a dark page overrides the book's colours and dims its images`() {
        val night = css(dark = true)
        assertTrue(night.contains("body *{color:inherit!important;background-color:transparent!important;"))
        assertTrue(night.contains("filter:brightness(0.85)"))

        val paper = css(dark = false)
        // The reader's own marks inherit their colour on any page; the book's text does not.
        assertFalse(paper.contains("body *{color:inherit"))
        assertFalse(paper.contains("filter:"))
    }

    @Test
    fun `true black dims images furthest`() {
        val sheet = readerCss(PageStyle(ReaderTypography(), PageColors.of(ReadingPalettes.Black)))
        assertTrue(sheet.contains("filter:brightness(0.75)"))
    }

    @Test
    fun `only real links take the link colour`() {
        val sheet = css()
        assertTrue(sheet.contains("a[href],a[href]:visited,a[href] *{color:"))
        assertFalse(Regex("""(^|[\n,}])a,""").containsMatchIn(sheet))
    }

    @Test
    fun `the reader's sheet is the last thing in the head`() {
        val doc = chapterDocument(
            """<html><head><link rel="stylesheet" href="b.css"/></head><body>t</body></html>""",
            "p{}"
        )
        assertTrue(doc, doc.contains("""<link rel="stylesheet" href="b.css"/><style id="$READER_STYLE_ID">p{}</style>"""))
    }
}
