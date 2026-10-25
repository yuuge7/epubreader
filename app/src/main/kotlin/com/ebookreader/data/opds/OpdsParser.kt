package com.ebookreader.data.opds

import com.ebookreader.data.epub.visibleText
import com.ebookreader.data.library.SourceKind
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder

data class OpdsLink(
    val href: String,
    val rel: String? = null,
    val type: String? = null,
    val title: String? = null,
    val length: Long? = null,
) {
    val isFeed: Boolean get() = type?.contains("atom+xml") == true

    /** The format this file would be imported as, by its type or address; null if unreadable. */
    val kind: SourceKind? get() = SourceKind.detect(href.substringAfterLast('/'), type)
}

/** One entry of a catalogue page: a book to download, or a further page to open. */
data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String?,
    val summary: String?,
    /** A cover image's address, the small one where the catalogue offers both. */
    val cover: String?,
    /** Files this book can be downloaded as. Empty for a navigation entry. */
    val acquisitions: List<OpdsLink>,
    /** The page this entry leads to, for a navigation entry or a book's own page. */
    val navigation: OpdsLink?,
    /** "More by this author", "More on this subject". */
    val related: List<OpdsLink>,
) {
    val isBook: Boolean get() = acquisitions.isNotEmpty()
}

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val next: String? = null,
    /** A search address with {searchTerms} in it, when the feed gives one directly. */
    val searchTemplate: String? = null,
    /** An OpenSearch description, which holds the search address. */
    val searchDescription: String? = null,
)

/**
 * Reads OPDS 1.x, the Atom-based format online libraries publish their catalogues in.
 * Addresses are made absolute against the page they were read from.
 */
object OpdsParser {

    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private val Whitespace = Regex("""\s+""")

    fun parse(input: InputStream, baseUrl: String): OpdsFeed {
        val parser = newParser()
        parser.setInput(input, null)
        var feedTitle = ""
        val feedLinks = mutableListOf<OpdsLink>()
        val entries = mutableListOf<OpdsEntry>()
        var entry: EntryBuilder? = null
        var sawFeed = false
        var authorDepth = -1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "feed" -> sawFeed = true
                    "entry" -> entry = EntryBuilder()
                    "link" -> link(parser, baseUrl)?.let { if (entry != null) entry.links += it else feedLinks += it }
                    "title" -> {
                        val text = readText(parser)
                        if (entry != null) entry.title = text else if (feedTitle.isEmpty()) feedTitle = text
                    }
                    "author" -> authorDepth = parser.depth
                    "name" -> if (authorDepth > 0 && entry != null) readText(parser).takeIf { it.isNotBlank() }?.let { entry.authors += it }
                    "id" -> if (entry != null) entry.id = readText(parser)
                    "summary" -> if (entry != null) entry.summary = readText(parser)
                    "content" -> if (entry != null) entry.content = readText(parser)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "entry" -> { entry?.let { entries += it.build() }; entry = null }
                    "author" -> authorDepth = -1
                }
            }
            event = parser.next()
        }
        if (!sawFeed) throw IllegalArgumentException("Not an OPDS catalogue")

        val search = feedLinks.filter { it.rel == "search" }
        return OpdsFeed(
            title = feedTitle,
            entries = entries,
            next = feedLinks.firstOrNull { it.rel == "next" }?.href,
            searchTemplate = search.firstOrNull { it.isFeed && "{searchTerms}" in it.href }?.href,
            searchDescription = search.firstOrNull { it.type?.contains("opensearchdescription") == true }?.href,
        )
    }

    /** The Atom search address in an OpenSearch description. */
    fun parseSearchTemplate(input: InputStream, baseUrl: String): String? {
        val parser = newParser()
        parser.setInput(input, null)
        var event = parser.eventType
        val templates = mutableListOf<Pair<String, String>>()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Url") {
                val type = parser.getAttributeValue(null, "type").orEmpty()
                parser.getAttributeValue(null, "template")?.let { templates += type to resolve(baseUrl, it) }
            }
            event = parser.next()
        }
        return templates.firstOrNull { it.first.contains("atom+xml") }?.second
    }

    /** [template] with the query filled in and any optional parameters left out. */
    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query.trim(), "UTF-8"))
            // Android's regex engine needs the closing brace escaped too.
            .replace(Regex("""\{[^}]*\?\}"""), "")

    private fun newParser(): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()

    private fun link(parser: XmlPullParser, baseUrl: String): OpdsLink? {
        val href = parser.getAttributeValue(null, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return OpdsLink(
            href = resolve(baseUrl, href),
            rel = parser.getAttributeValue(null, "rel"),
            type = parser.getAttributeValue(null, "type"),
            title = parser.getAttributeValue(null, "title"),
            length = parser.getAttributeValue(null, "length")?.toLongOrNull(),
        )
    }

    /**
     * The text of the current element, nested markup and all: an entry's content is often
     * XHTML. Paragraphs and line breaks become line breaks; leaves the parser on the end tag.
     */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val escapedHtml = parser.getAttributeValue(null, "type") == "html"
        val out = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (event) {
                // Inside XHTML a line break in the source is only a space ("Title:" on one
                // line and the title on the next); the paragraphs make the breaks.
                XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                    out.append(if (parser.depth > depth) parser.text.replace(Whitespace, " ") else parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "p" || parser.name == "div") out.append('\n')
                XmlPullParser.START_TAG -> if (parser.name == "br") out.append('\n')
                XmlPullParser.END_DOCUMENT -> break
            }
            event = parser.next()
        }
        val text = if (escapedHtml) visibleText(out.toString()) else out.toString()
        return text.lines().map { it.trim().replace(Whitespace, " ") }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    internal fun resolve(base: String, href: String): String =
        if (href.startsWith("data:")) href else runCatching { URL(URL(base), href).toString() }.getOrDefault(href)

    private class EntryBuilder {
        var id = ""
        var title = ""
        val authors = mutableListOf<String>()
        var summary: String? = null
        var content: String? = null
        val links = mutableListOf<OpdsLink>()

        fun build(): OpdsEntry {
            val acquisitions = links.filter { it.rel == ACQUISITION || it.rel == "$ACQUISITION/open-access" }
            fun image(rel: String) = links.firstOrNull { it.rel == rel && !it.href.startsWith("data:") }?.href
            return OpdsEntry(
                id = id.ifBlank { title },
                title = title.ifBlank { "Untitled" },
                author = authors.distinct().joinToString(", ") { invertName(it) }.ifBlank { null },
                summary = (summary ?: content)?.ifBlank { null },
                cover = image(THUMBNAIL) ?: image(IMAGE),
                acquisitions = acquisitions,
                navigation = links.firstOrNull { it.isFeed && it.rel != "related" && it.rel != "self" },
                related = links.filter { it.isFeed && it.rel == "related" },
            )
        }

        /** "Shelley, Mary Wollstonecraft" reads as "Mary Wollstonecraft Shelley". */
        private fun invertName(name: String): String {
            val parts = name.split(", ")
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) "${parts[1]} ${parts[0]}" else name
        }
    }
}
