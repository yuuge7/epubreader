package com.ebookreader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes and keeps the cover images shown in the library, in the app's own files: the
 * extraction cache they often start in can be cleared by the system at any time.
 */
@Singleton
class CoverMaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val coversDir: File get() = File(context.filesDir, "covers").also { it.mkdirs() }

    /** A copy of [sourcePath], named after the book file it belongs to. */
    fun keepCover(sourcePath: String, bookFileName: String): String? = runCatching {
        val source = File(sourcePath)
        if (!source.isFile) return null
        val ext = source.extension.ifBlank { "jpg" }
        val dest = File(coversDir, "${bookFileName.substringBeforeLast(".")}_cover.$ext")
        source.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()

    /**
     * Page one of a PDF, rendered once at import to stand in for the cover a PDF does not
     * have. Every PDF used to sit in the library as the same placeholder tile.
     */
    fun pdfCover(pdf: File): String? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val width = COVER_WIDTH
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 3)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent where nothing is drawn; paper is white.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    save(bitmap, pdf.name)
                }
            }
        }
    }.getOrNull()

    /** An image, scaled down, as a cover: a comic's first page, or a converted book's own. */
    fun imageCover(image: ByteArray, bookFileName: String): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        save(bitmap, bookFileName)
    }.getOrNull()

    private fun save(bitmap: Bitmap, bookFileName: String): String {
        val dest = File(coversDir, "${bookFileName.substringBeforeLast(".")}_cover.jpg")
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return dest.absolutePath
    }

    private companion object {
        /** Wide enough for the largest place a cover is shown, the continue-reading card. */
        const val COVER_WIDTH = 600
    }
}
