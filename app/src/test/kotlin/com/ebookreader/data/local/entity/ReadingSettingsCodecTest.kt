package com.ebookreader.data.local.entity

import com.ebookreader.domain.model.BookReadingSettings
import com.ebookreader.domain.model.ReaderFont
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.domain.model.ReadingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSettingsCodecTest {

    @Test
    fun `a book's settings survive the trip through its row`() {
        val settings = BookReadingSettings(
            typography = ReaderTypography(font = ReaderFont.BITTER, fontSize = 22f, paged = true, twoColumns = false),
            readingTheme = ReadingTheme.SEPIA,
        )
        assertEquals(settings, ReadingSettingsCodec.decode(ReadingSettingsCodec.encode(settings)))
    }

    @Test
    fun `nothing set is stored as nothing`() {
        assertNull(ReadingSettingsCodec.encode(BookReadingSettings()))
        assertNull(ReadingSettingsCodec.encode(null))
        assertNull(ReadingSettingsCodec.decode(null))
    }

    @Test
    fun `a damaged or out-of-range value falls back instead of failing`() {
        assertNull(ReadingSettingsCodec.decode("{not json"))
        val decoded = ReadingSettingsCodec.decode("""{"type":{"font":"COMIC_SANS","size":400}}""")!!
        assertEquals(ReaderFont.LITERATA, decoded.typography!!.font)
        assertEquals(ReaderTypography.FONT_SIZE_MAX, decoded.typography!!.fontSize)
        assertNull(decoded.readingTheme)
    }
}
