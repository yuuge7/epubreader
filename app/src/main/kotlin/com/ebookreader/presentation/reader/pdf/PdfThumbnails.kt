package com.ebookreader.presentation.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Where a PDF's text sits across the width of its pages, as shares of the width: [left]
 * of 0.1 is a margin of a tenth. Cropping zooms the page to [left]..[right].
 */
data class PdfContentBounds(val left: Float, val right: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
}

/**
 * Small renderings of a PDF's pages, for the grid of thumbnails and for finding the
 * margins. A renderer of its own, apart from the viewer's, since a PdfRenderer draws one
 * page at a time; all drawing goes through one thread.
 */
class PdfThumbnails private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val drawing: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val cache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false

    val pageCount: Int get() = if (closed) 0 else renderer.pageCount

    /** Page [index] [width] pixels wide, on white; null once closed or if it fails. */
    suspend fun page(index: Int, width: Int = THUMB_WIDTH): Bitmap? {
        cache.get(index)?.takeIf { it.width == width }?.let { return it }
        return withContext(drawing) {
            runCatching {
                // Closing waits for a page being drawn: the renderer must not go mid-page.
                synchronized(this@PdfThumbnails) {
                    if (closed || index !in 0 until renderer.pageCount) return@withContext null
                    renderer.openPage(index).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 3)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent where nothing is drawn; paper is white.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                    }
                }
            }.getOrNull()?.also { if (width == THUMB_WIDTH) cache.put(index, it) }
        }
    }

    /**
     * The widest the text runs on a few pages, past the first (often a cover). Taken over
     * several pages so a page with a wide table is not cut; null for a page of no ink.
     */
    suspend fun contentBounds(): PdfContentBounds? {
        val count = pageCount
        if (count == 0) return null
        val sample = if (count == 1) listOf(0) else (1 until count).take(SAMPLE_PAGES)
        var left = 1f
        var right = 0f
        for (index in sample) {
            val bitmap = page(index, MEASURE_WIDTH) ?: continue
            val (l, r) = inkColumns(bitmap) ?: continue
            left = minOf(left, l)
            right = maxOf(right, r)
        }
        if (right <= left) return null
        // A little air either side of the text, so letters are not cut at the edge.
        return PdfContentBounds((left - MARGIN).coerceAtLeast(0f), (right + MARGIN).coerceAtMost(1f))
    }

    /** First and last column with ink, as shares of the width. */
    private fun inkColumns(bitmap: Bitmap): Pair<Float, Float>? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        val ink = BooleanArray(w)
        for (y in 0 until h step 2) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum < INK_LUMINANCE) ink[x] = true
            }
        }
        val first = ink.indexOfFirst { it }
        val last = ink.indexOfLast { it }
        if (first < 0) return null
        return first.toFloat() / w to (last + 1).toFloat() / w
    }

    override fun close() {
        if (closed) return
        synchronized(this) {
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
        cache.evictAll()
    }

    companion object {
        const val THUMB_WIDTH = 240
        private const val MEASURE_WIDTH = 300
        private const val SAMPLE_PAGES = 6
        private const val MARGIN = 0.02f
        /** Paler than this is paper, or a faint background tint. */
        private const val INK_LUMINANCE = 200
        private const val CACHE_BYTES = 24 * 1024 * 1024

        fun open(file: File): PdfThumbnails? = runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfThumbnails(fd, PdfRenderer(fd))
        }.getOrNull()
    }
}
