package com.ebookreader.domain.model

/**
 * What a file name says about the book in it, for files that carry no metadata of their
 * own (PDFs, text files) or carry it blank. Libraries are commonly named
 * "Author - Title (Year)", sometimes with the series between: "Author - Series 03 - Title".
 */
data class FileNameMetadata(
    val title: String,
    val author: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val year: Int? = null,
) {
    companion object {
        private val Extension = Regex("""(?i)\.(fb2\.zip|epub|pdf|cbz|cbr|txt|md|markdown|fb2|fbz|mobi|prc|azw3|azw|kf8)$""")
        private val TrailingYear = Regex("""\s*[(\[]\s*(\d{4})\s*[)\]]\s*$""")
        private val Separator = Regex("""\s+[-–—]\s+""")
        private val SeriesWithNumber = Regex("""^(.*?)[\s,#]*(?:(?:book|vol\.?|volume|no\.?|part)\s*)?#?(\d{1,3}(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
        private val Spaces = Regex("""\s+""")

        /** [fileName] without its book extension(s): "x.fb2.zip" is "x". */
        fun stem(fileName: String): String = fileName.substringAfterLast('/').replace(Extension, "")

        /** Null when nothing usable is left of the name. */
        fun parse(fileName: String): FileNameMetadata? {
            var name = fileName.substringAfterLast('/').replace(Extension, "")
            // "Jane_Austen_-_Emma" is the same name with its spaces filled in.
            if (!name.contains(' ')) name = name.replace('_', ' ')
            name = name.replace(Spaces, " ").trim()
            var year: Int? = null
            TrailingYear.find(name)?.let { m ->
                year = m.groupValues[1].toInt().takeIf { it in 1400..2100 }
                if (year != null) name = name.substring(0, m.range.first).trim()
            }
            val parts = name.split(Separator).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            if (parts.size == 1) return FileNameMetadata(title = parts[0], year = year)

            val author = parts.first().takeIf { looksLikeAuthor(it) }
                ?: return FileNameMetadata(title = parts.joinToString(" - "), year = year)
            val title = parts.last()
            var series: String? = null
            var index: Float? = null
            if (parts.size > 2) {
                val middle = parts.subList(1, parts.size - 1).joinToString(" - ")
                val m = SeriesWithNumber.find(middle)
                if (m != null && m.groupValues[1].isNotBlank()) {
                    series = m.groupValues[1].trim()
                    index = m.groupValues[2].toFloatOrNull()
                } else {
                    series = middle
                }
            }
            return FileNameMetadata(
                title = title,
                author = naturalOrder(author),
                series = series,
                seriesIndex = index,
                year = year,
            )
        }

        /** A name rather than a title fragment: short, mostly letters, not a number. */
        private fun looksLikeAuthor(text: String): Boolean =
            text.length in 2..60 && text.count { it.isLetter() } >= text.length / 2 && text.none { it.isDigit() }

        /** "Austen, Jane" reads as "Jane Austen"; several authors are left as they are. */
        private fun naturalOrder(author: String): String {
            if (author.contains('&') || author.contains(" and ", ignoreCase = true) || author.count { it == ',' } != 1) return author
            val (last, first) = author.split(',').map { it.trim() }
            return if (first.isNotEmpty() && last.isNotEmpty()) "$first $last" else author
        }
    }
}
