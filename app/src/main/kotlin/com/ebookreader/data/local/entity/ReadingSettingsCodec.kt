package com.ebookreader.data.local.entity

import com.ebookreader.domain.model.BookReadingSettings
import com.ebookreader.domain.model.ReaderFont
import com.ebookreader.domain.model.ReaderTypography
import com.ebookreader.domain.model.ReadingTheme
import org.json.JSONObject

/**
 * A book's own reading settings as a small JSON object in its row. Every field is
 * optional when read back, so a value written by a later version, or a corrupt one,
 * falls back to the defaults instead of failing to open the book.
 */
object ReadingSettingsCodec {

    fun encode(settings: BookReadingSettings?): String? {
        if (settings == null || settings.isEmpty) return null
        val root = JSONObject()
        settings.readingTheme?.let { root.put("theme", it.name) }
        settings.typography?.let { t ->
            root.put(
                "type",
                JSONObject()
                    .put("font", t.font.name)
                    .put("size", t.fontSize.toDouble())
                    .put("lineHeight", t.lineHeight.toDouble())
                    .put("margin", t.margin.toDouble())
                    .put("justify", t.justify)
                    .put("hyphenate", t.hyphenate)
                    .put("paged", t.paged)
                    .put("twoColumns", t.twoColumns)
            )
        }
        return root.toString()
    }

    fun decode(json: String?): BookReadingSettings? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val theme = root.optString("theme").takeIf { it.isNotEmpty() }
                ?.let { name -> ReadingTheme.entries.firstOrNull { it.name == name } }
            val type = root.optJSONObject("type")?.let { t ->
                val d = ReaderTypography()
                ReaderTypography(
                    font = ReaderFont.entries.firstOrNull { it.name == t.optString("font") } ?: d.font,
                    fontSize = t.optDouble("size", d.fontSize.toDouble()).toFloat(),
                    lineHeight = t.optDouble("lineHeight", d.lineHeight.toDouble()).toFloat(),
                    margin = t.optDouble("margin", d.margin.toDouble()).toFloat(),
                    justify = t.optBoolean("justify", d.justify),
                    hyphenate = t.optBoolean("hyphenate", d.hyphenate),
                    paged = t.optBoolean("paged", d.paged),
                    twoColumns = t.optBoolean("twoColumns", d.twoColumns),
                ).sanitized()
            }
            BookReadingSettings(type, theme).takeUnless { it.isEmpty }
        }.getOrNull()
    }
}
