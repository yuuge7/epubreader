package com.ebookreader.presentation.reader.epub

import android.annotation.SuppressLint
import android.content.res.Resources
import android.webkit.WebResourceResponse
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.webkit.WebViewAssetLoader
import com.ebookreader.R
import com.ebookreader.domain.model.ReaderFont

/** One font file as the page asks for it: an @font-face rule and the file behind it. */
internal data class FontFile(
    @param:FontRes val res: Int,
    /** Served at [ReaderOrigin.FONTS_PATH] + this name. */
    val fileName: String,
    /** CSS font-weight: one weight, or "400 700" for a variable font. */
    val weight: String,
    val italic: Boolean,
) {
    val mimeType: String get() = if (fileName.endsWith(".otf")) "font/otf" else "font/ttf"
}

/** A face the reader can set a book in. All of them ship in the APK; nothing is fetched. */
internal class ReaderFace(
    val label: String,
    /** What the page's @font-face rules call it. Distinct from any name a book might use. */
    val cssFamily: String,
    /** Generic family for glyphs the face lacks. */
    val fallback: String,
    val files: List<FontFile>,
    /** The face in Compose, for the picker. */
    val preview: FontFamily,
)

private fun variable(@FontRes res: Int, file: String, italic: Boolean) =
    FontFile(res, file, weight = "400 700", italic = italic)

private fun static(@FontRes res: Int, file: String, weight: Int, italic: Boolean) =
    FontFile(res, file, weight = weight.toString(), italic = italic)

/** The regular weight, explicitly, so a variable font does not preview at its default axis. */
private fun previewOf(@FontRes res: Int) = FontFamily(
    Font(
        res,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    )
)

private val Literata = ReaderFace(
    label = "Literata",
    cssFamily = "Reader Literata",
    fallback = "serif",
    files = listOf(
        variable(R.font.literata, "literata.ttf", italic = false),
        variable(R.font.literata_italic, "literata_italic.ttf", italic = true),
    ),
    preview = previewOf(R.font.literata),
)

private val Bitter = ReaderFace(
    label = "Bitter",
    cssFamily = "Reader Bitter",
    fallback = "serif",
    files = listOf(
        variable(R.font.bitter, "bitter.ttf", italic = false),
        variable(R.font.bitter_italic, "bitter_italic.ttf", italic = true),
    ),
    preview = previewOf(R.font.bitter),
)

private val Atkinson = ReaderFace(
    label = "Atkinson",
    cssFamily = "Reader Atkinson Hyperlegible",
    fallback = "sans-serif",
    files = listOf(
        static(R.font.atkinson_hyperlegible, "atkinson_hyperlegible.ttf", 400, italic = false),
        static(R.font.atkinson_hyperlegible_italic, "atkinson_hyperlegible_italic.ttf", 400, italic = true),
        static(R.font.atkinson_hyperlegible_bold, "atkinson_hyperlegible_bold.ttf", 700, italic = false),
        static(R.font.atkinson_hyperlegible_bold_italic, "atkinson_hyperlegible_bold_italic.ttf", 700, italic = true),
    ),
    preview = previewOf(R.font.atkinson_hyperlegible),
)

private val OpenDyslexic = ReaderFace(
    label = "OpenDyslexic",
    cssFamily = "Reader OpenDyslexic",
    fallback = "sans-serif",
    files = listOf(
        static(R.font.opendyslexic, "opendyslexic.otf", 400, italic = false),
        static(R.font.opendyslexic_italic, "opendyslexic_italic.otf", 400, italic = true),
        static(R.font.opendyslexic_bold, "opendyslexic_bold.otf", 700, italic = false),
        static(R.font.opendyslexic_bold_italic, "opendyslexic_bold_italic.otf", 700, italic = true),
    ),
    preview = previewOf(R.font.opendyslexic),
)

private val Inter = ReaderFace(
    label = "Inter",
    cssFamily = "Reader Inter Tight",
    fallback = "sans-serif",
    files = listOf(
        variable(R.font.inter_tight, "inter_tight.ttf", italic = false),
        variable(R.font.inter_tight_italic, "inter_tight_italic.ttf", italic = true),
    ),
    preview = previewOf(R.font.inter_tight),
)

/** Null for [ReaderFont.PUBLISHER], which leaves the book's own fonts in place. */
internal val ReaderFont.face: ReaderFace?
    get() = when (this) {
        ReaderFont.PUBLISHER -> null
        ReaderFont.LITERATA -> Literata
        ReaderFont.BITTER -> Bitter
        ReaderFont.ATKINSON -> Atkinson
        ReaderFont.OPENDYSLEXIC -> OpenDyslexic
        ReaderFont.INTER -> Inter
    }

internal val ReaderFont.label: String get() = face?.label ?: "Original"

/** Serves the bundled reading faces to the page, by file name, from the font resources. */
internal class ReaderFontPathHandler(private val resources: Resources) : WebViewAssetLoader.PathHandler {

    private val files: Map<String, FontFile> = ReaderFont.entries
        .mapNotNull { it.face }
        .flatMap { it.files }
        .associateBy { it.fileName }

    // A font resource is a raw file underneath, which is what the page needs.
    @SuppressLint("ResourceType")
    override fun handle(path: String): WebResourceResponse? {
        val file = files[path] ?: return null
        return WebResourceResponse(
            file.mimeType, null, 200, "OK", ForeverCached, resources.openRawResource(file.res)
        )
    }

    private companion object {
        /** Each page is a new WebView; the files only change with the app. */
        val ForeverCached = mapOf("Cache-Control" to "max-age=31536000, immutable")
    }
}
