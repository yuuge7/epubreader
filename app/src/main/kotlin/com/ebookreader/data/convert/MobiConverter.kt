package com.ebookreader.data.convert

import com.ebookreader.data.epub.visibleText
import com.ebookreader.data.epub.visibleTextLength
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Kindle books without DRM: MOBI (KF7) and AZW3 (KF8). The text is decompressed and
 * split into chapters at the page breaks a MOBI marks, or at the documents a KF8 book is
 * built from; images are taken from the image records.
 *
 * What this does not do: DRM-protected books cannot be opened by anything but Amazon's
 * own apps, and books compressed with the HUFF/CDIC dictionary scheme are rare enough
 * not to carry its decoder. Both are refused with a reason rather than shown as garbage.
 */
object MobiConverter {

    class UnsupportedMobi(message: String) : IllegalArgumentException(message)

    fun convert(bytes: ByteArray, fallbackTitle: String): GeneratedBook {
        val db = PalmDatabase(bytes)
        if (db.type != "BOOKMOBI" && db.type != "TEXtREAd") {
            throw UnsupportedMobi("This is not a Kindle book")
        }
        val header = MobiHeader(db.record(0))
        if (header.encryption != 0) {
            throw UnsupportedMobi("This Kindle book is protected by DRM and can only be read in Amazon's apps")
        }
        if (header.compression != 1 && header.compression != 2) {
            throw UnsupportedMobi("This Kindle book uses a compression this app does not read")
        }

        val raw = ByteArrayOutputStream(header.textLength.coerceAtLeast(0))
        for (i in 1..header.textRecordCount) {
            var record = db.record(i)
            record = record.copyOf(record.size - trailingSize(record, header.extraFlags))
            raw.write(if (header.compression == 2) palmDocDecompress(record) else record)
        }
        var text = raw.toByteArray()
        // KF8 keeps its stylesheets and SVG after the text, as further "flows"; only the
        // first one is the book.
        if (header.isKf8 && header.fdstIndex in 1 until db.recordCount) {
            flowBounds(db.record(header.fdstIndex))?.let { (start, end) ->
                if (end in (start + 1)..text.size) text = text.copyOfRange(start, end)
            }
        }
        val markup = String(text, header.charset)

        // Images, by the 1-based number MOBI and KF8 markup use to refer to them.
        val resources = mutableListOf<Resource>()
        val imagePaths = mutableMapOf<Int, String>()
        if (header.firstImageIndex in 1 until db.recordCount) {
            for (r in header.firstImageIndex until db.recordCount) {
                val data = db.record(r)
                val (ext, mime) = imageType(data) ?: continue
                val number = r - header.firstImageIndex + 1
                val path = "images/$number.$ext"
                resources += Resource(path, mime, data)
                imagePaths[number] = path
            }
        }

        val pieces = if (header.isKf8) splitDocuments(markup) else markup.split(PageBreak)
        val chapters = mergeShort(pieces.map { clean(it, imagePaths) }.filter { visibleTextLength(it) > 0 })
            .mapIndexed { i, body -> GeneratedChapter(titleOf(body) ?: "Chapter ${i + 1}", body) }

        val exth = header.exth
        val cover = exth[201]?.firstOrNull()?.let { int(it, 0) }?.let { imagePaths[it + 1] }
        return GeneratedBook(
            title = exth[503]?.firstOrNull()?.let { String(it, header.charset) }?.ifBlank { null }
                ?: header.fullName?.ifBlank { null }
                ?: db.name.replace('_', ' ').ifBlank { fallbackTitle },
            author = exth[100]?.joinToString(", ") { String(it, header.charset).trim() }?.ifBlank { null } ?: "Unknown Author",
            language = exth[524]?.firstOrNull()?.let { String(it, header.charset).trim() }?.ifBlank { null },
            chapters = chapters.ifEmpty { listOf(GeneratedChapter(fallbackTitle, "")) },
            resources = resources,
            coverPath = cover,
        )
    }

    // ── Container ────────────────────────────────────────────────────────────

    /** The Palm database every MOBI is: a name, a type, and a list of records. */
    internal class PalmDatabase(private val bytes: ByteArray) {
        val name: String = String(bytes, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000')
        val type: String = String(bytes, 60, 8, Charsets.ISO_8859_1)
        val recordCount: Int = u16(bytes, 76)
        private val offsets = IntArray(recordCount) { int(bytes, 78 + it * 8) }

        init {
            require(bytes.size >= 78 + recordCount * 8) { "Truncated file" }
        }

        fun record(i: Int): ByteArray {
            require(i in 0 until recordCount) { "No record $i" }
            val start = offsets[i]
            val end = if (i + 1 < recordCount) offsets[i + 1] else bytes.size
            return bytes.copyOfRange(start.coerceIn(0, bytes.size), end.coerceIn(start, bytes.size))
        }
    }

    /** Record 0: the PalmDOC header, the MOBI header, and the EXTH metadata after it. */
    internal class MobiHeader(r0: ByteArray) {
        val compression = u16(r0, 0)
        val textLength = int(r0, 4)
        val textRecordCount = u16(r0, 8)
        val encryption = u16(r0, 12)
        private val hasMobi = r0.size >= 0x84 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        private val headerLength = if (hasMobi) int(r0, 0x14) else 0
        private val encoding = if (hasMobi) int(r0, 0x1C) else 1252
        val fileVersion = if (hasMobi) int(r0, 0x24) else 0
        val isKf8 = fileVersion >= 8
        val firstImageIndex = if (hasMobi) int(r0, 0x6C) else -1
        val extraFlags = if (hasMobi && headerLength >= 0xE4 && r0.size >= 0xF4) u16(r0, 0xF2) else 0
        val fdstIndex = if (isKf8 && r0.size >= 0xC4) int(r0, 0xC0) else -1
        val fullName: String? = if (hasMobi) runCatching {
            val offset = int(r0, 0x54)
            val length = int(r0, 0x58)
            String(r0, offset, length, charsetFor(encoding))
        }.getOrNull() else null
        val charset: Charset = charsetFor(encoding)

        /** EXTH records by type; some types (authors) repeat. */
        val exth: Map<Int, List<ByteArray>> = run {
            if (!hasMobi || (int(r0, 0x80) and 0x40) == 0) return@run emptyMap()
            val start = 16 + headerLength
            if (start + 12 > r0.size || String(r0, start, 4, Charsets.ISO_8859_1) != "EXTH") return@run emptyMap()
            val count = int(r0, start + 8)
            val out = mutableMapOf<Int, MutableList<ByteArray>>()
            var p = start + 12
            repeat(count) {
                if (p + 8 > r0.size) return@repeat
                val type = int(r0, p)
                val length = int(r0, p + 4)
                if (length < 8 || p + length > r0.size) return@repeat
                out.getOrPut(type) { mutableListOf() } += r0.copyOfRange(p + 8, p + length)
                p += length
            }
            out
        }

        private fun charsetFor(code: Int): Charset =
            if (code == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    /**
     * Bytes at the end of a text record that are not text: one variable-width entry per
     * flag bit above the lowest, then, with the lowest bit, the tail of a multibyte
     * character that spills into the next record.
     */
    internal fun trailingSize(record: ByteArray, flags: Int): Int {
        var size = 0
        var bits = flags ushr 1
        while (bits != 0) {
            if ((bits and 1) != 0) size += backwardVarint(record, record.size - size)
            bits = bits ushr 1
        }
        if ((flags and 1) != 0 && record.size - size - 1 >= 0) {
            size += (record[record.size - size - 1].toInt() and 0x3) + 1
        }
        return size.coerceAtMost(record.size)
    }

    /** A size stored backwards from [end]: seven bits a byte, the first byte flagged. */
    private fun backwardVarint(data: ByteArray, end: Int): Int {
        var result = 0
        var shift = 0
        var pos = end
        while (pos > 0) {
            val v = data[pos - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl shift)
            shift += 7
            pos--
            if ((v and 0x80) != 0 || shift >= 28) break
        }
        return result
    }

    /** PalmDOC's LZ77: literals, runs of literals, back-references, and space-prefixed bytes. */
    internal fun palmDocDecompress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        val buffer = ByteArray(input.size * 8 + 16)
        var size = 0
        fun put(b: Int) {
            if (size == buffer.size) return
            buffer[size++] = b.toByte()
        }
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 0x01..0x08 -> repeat(c) { if (i < input.size) put(input[i++].toInt() and 0xFF) }
                c in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (pair ushr 3) and 0x7FF
                    val length = (pair and 0x7) + 3
                    if (distance == 0 || distance > size) continue
                    repeat(length) { put(buffer[size - distance].toInt() and 0xFF) }
                }
                else -> { put(0x20); put(c xor 0x80) }
            }
        }
        out.write(buffer, 0, size)
        return out.toByteArray()
    }

    /** KF8's flow table: where flow 0, the book's markup, starts and ends. */
    private fun flowBounds(fdst: ByteArray): Pair<Int, Int>? {
        if (fdst.size < 20 || String(fdst, 0, 4, Charsets.ISO_8859_1) != "FDST") return null
        val dataStart = int(fdst, 4)
        val count = int(fdst, 8)
        if (count < 1 || dataStart + 8 > fdst.size) return null
        return int(fdst, dataStart) to int(fdst, dataStart + 4)
    }

    // ── Markup ───────────────────────────────────────────────────────────────

    private val PageBreak = Regex("""<mbp:pagebreak\s*/?>""", RegexOption.IGNORE_CASE)
    private val DocumentStart = Regex("""(?=<\?xml|<html[\s>])""", RegexOption.IGNORE_CASE)

    /** A KF8 text stream is its documents one after another, each with its fragments. */
    private fun splitDocuments(markup: String): List<String> = markup.split(DocumentStart)

    private val Removals = listOf(
        Regex("""<head\b.*?</head\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<\?xml[^>]*>|<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""</?(html|body)\b[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""</?mbp:[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE),
        Regex("""<guide\b.*?</guide\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    )
    private val ImgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RecIndex = Regex("""recindex\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
    private val KindleEmbed = Regex("""kindle:embed:([0-9A-Va-v]{4})(\?[^"'\s>]*)?""")
    private val InternalHref = Regex("""\s(filepos|href)\s*=\s*["']?(kindle:[^"'\s>]*|\d+)["']?""", RegexOption.IGNORE_CASE)

    private fun clean(piece: String, images: Map<Int, String>): String {
        var s = piece
        Removals.forEach { s = it.replace(s, "") }
        // <img recindex="00003"> in MOBI, src="kindle:embed:0003" in KF8.
        s = ImgTag.replace(s) { m ->
            val tag = m.value
            val number = RecIndex.find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: KindleEmbed.find(tag)?.groupValues?.get(1)?.let(::base32)
            val path = number?.let { images[it] } ?: return@replace ""
            "<img src=\"../$path\" alt=\"\"/>"
        }
        // Positions inside the original file mean nothing in the converted book.
        s = InternalHref.replace(s, "")
        return s.trim()
    }

    /** KF8's resource numbers are base 32, digits then A to V. */
    private fun base32(text: String): Int =
        text.uppercase().fold(0) { acc, c -> acc * 32 + if (c.isDigit()) c - '0' else c - 'A' + 10 }

    /** Page breaks also sit between a title page and a dedication: fold the scraps forward. */
    private fun mergeShort(bodies: List<String>): List<String> {
        val out = mutableListOf<String>()
        var carry = ""
        var carryLength = 0
        for (b in bodies) {
            val joined = if (carry.isEmpty()) b else "$carry\n$b"
            val length = carryLength + visibleTextLength(b)
            if (length < 200) { carry = joined; carryLength = length }
            else { out += joined; carry = ""; carryLength = 0 }
        }
        if (carry.isNotEmpty()) {
            if (out.isEmpty()) out += carry else out[out.lastIndex] = out.last() + "\n" + carry
        }
        return out
    }

    private val HeadingTag = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val Paragraph = Regex("""<p\b[^>]*>(.*?)</p\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LineBreak = Regex("""<br\b[^>]*>(</br>)?""", RegexOption.IGNORE_CASE)
    private val Spaces = Regex("""\s+""")

    /**
     * A chapter's heading, or else its first paragraph when that is short: older MOBI
     * books set headings as big centred type ("I." then "Introduction"), not h1 to h3.
     */
    private fun titleOf(body: String): String? {
        fun text(markup: String) = visibleText(LineBreak.replace(markup, " ")).replace(Spaces, " ").trim()
        HeadingTag.find(body)?.let { m -> text(m.groupValues[1]).takeIf { it.isNotEmpty() && it.length <= 120 }?.let { return it } }
        val first = Paragraph.findAll(body).map { text(it.groupValues[1]) }.firstOrNull { it.isNotEmpty() }
        return first?.takeIf { it.length <= 80 }
    }
}

private fun u16(b: ByteArray, at: Int): Int =
    if (at + 2 > b.size) 0 else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

private fun int(b: ByteArray, at: Int): Int =
    if (at + 4 > b.size) 0
    else ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
