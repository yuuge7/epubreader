package com.ebookreader

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app driven as a reader drives it: past the welcome pages into the library, through
 * the settings, and into a book opened from outside, with its contents.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        skipWelcome()
    }

    @After
    fun close() {
        scenario?.close()
    }

    /**
     * Whether a node matching [matcher] is on screen. False rather than an error while the
     * activity has no content yet: it is still starting, or another one is taking over.
     */
    private fun exists(matcher: SemanticsMatcher): Boolean =
        runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { exists(matcher) }

    private fun skipWelcome() {
        compose.waitUntil(10_000) { exists(hasText("Skip")) || exists(hasContentDescription("Library menu")) }
        if (exists(hasText("Skip"))) compose.onNodeWithText("Skip").performClick()
        waitFor(hasContentDescription("Library menu"))
    }

    @Test
    fun settings_are_split_into_four_pages() {
        compose.onNodeWithContentDescription("Library menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        for (section in listOf("Reading", "Library", "Data", "About")) {
            waitFor(hasText(section), 5_000)
        }
        compose.onNodeWithText("Data").performClick()
        waitFor(hasText("Back up the whole library"), 5_000)
        waitFor(hasText("Space used"), 5_000)
    }

    @Test
    fun a_book_opened_from_outside_is_imported_and_shows_its_contents() {
        val book = writeEpub(File(context.cacheDir, "flow-test-${System.nanoTime()}.epub"))
        scenario?.onActivity { activity ->
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(book), "application/epub+zip")
            )
        }
        // The reader opens on the book, named from its contents.
        waitFor(hasContentDescription("Text and page"), 15_000)
        waitFor(hasText("The Flow Test"), 5_000)
        compose.onNodeWithContentDescription("Contents").performClick()
        waitFor(hasText("Second Chapter"), 5_000)
        compose.onNodeWithText("Second Chapter").performClick()
        waitFor(hasText("Second Chapter"), 5_000)
    }

    private fun writeEpub(file: File): File {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Flow Test</dc:title><dc:creator>Test Writer</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val nav = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">First Chapter</a></li><li><a href="two.xhtml">Second Chapter</a></li></ol></nav></body></html>"""
        fun chapter(title: String) = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>${"<p>Words upon words, page after page.</p>".repeat(40)}</body></html>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/one.xhtml" to chapter("First Chapter"),
                "OEBPS/two.xhtml" to chapter("Second Chapter"),
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
