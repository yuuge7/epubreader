package com.ebookreader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameMetadataTest {

    @Test
    fun `author, title and year`() {
        val m = FileNameMetadata.parse("Mary Shelley - Frankenstein (1818).epub")!!
        assertEquals("Mary Shelley", m.author)
        assertEquals("Frankenstein", m.title)
        assertEquals(1818, m.year)
        assertNull(m.series)
    }

    @Test
    fun `a series with its number between author and title`() {
        val m = FileNameMetadata.parse("Brandon Sanderson - Mistborn 01 - The Final Empire (2006).pdf")!!
        assertEquals("Brandon Sanderson", m.author)
        assertEquals("The Final Empire", m.title)
        assertEquals("Mistborn", m.series)
        assertEquals(1f, m.seriesIndex)
    }

    @Test
    fun `last name first is turned around`() {
        val m = FileNameMetadata.parse("Austen, Jane - Emma.txt")!!
        assertEquals("Jane Austen", m.author)
        assertEquals("Emma", m.title)
    }

    @Test
    fun `underscores stand for spaces`() {
        val m = FileNameMetadata.parse("Jane_Austen_-_Persuasion.epub")!!
        assertEquals("Jane Austen", m.author)
        assertEquals("Persuasion", m.title)
    }

    @Test
    fun `a name without a separator is all title`() {
        val m = FileNameMetadata.parse("pride and prejudice [1813].fb2.zip")!!
        assertEquals("pride and prejudice", m.title)
        assertEquals(1813, m.year)
        assertNull(m.author)
    }

    @Test
    fun `a leading part that cannot be a name stays in the title`() {
        val m = FileNameMetadata.parse("2023 - Annual Report.pdf")!!
        assertNull(m.author)
        assertEquals("2023 - Annual Report", m.title)
    }

    @Test
    fun `several authors are left as written`() {
        val m = FileNameMetadata.parse("Pratchett & Gaiman - Good Omens.epub")!!
        assertEquals("Pratchett & Gaiman", m.author)
    }

    @Test
    fun `the stem drops every book extension`() {
        assertEquals("Emma", FileNameMetadata.stem("Emma.fb2.zip"))
        assertEquals("Star Wars - A New Hope", FileNameMetadata.stem("Star Wars - A New Hope.epub"))
    }
}
