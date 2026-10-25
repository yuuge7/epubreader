package com.ebookreader.presentation.common

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.ebookreader.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A year's reading, for the shareable summary card. */
data class YearInReading(
    val year: Int,
    val totalSeconds: Long,
    val booksFinished: Int,
    val daysRead: Int,
    val longestStreak: Int,
    val topBook: String?,
)

/**
 * Images made to be shared: a highlighted passage set as a quote card, and a year of
 * reading. Drawn on the page's own paper in Literata, with the fore-edge down the side,
 * so what leaves the app looks like it came from this one.
 */
object ShareCards {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 104f

    suspend fun shareQuote(context: Context, text: String, title: String, author: String, colors: BinderyColors) {
        val card = render("quote", context) { page(colors) { canvas ->
            val serif = font(context, R.font.literata, 400)
            val serifBold = font(context, R.font.literata, 600)
            val sans = font(context, R.font.inter_tight, 500)
            val width = (WIDTH - MARGIN * 2).toInt()

            val footerTitle = layout("— $title", serifBold, 38f, colors.ink, width)
            val footerAuthor = layout(author, sans, 30f, colors.graphite, width)
            val footerHeight = footerTitle.height + 14 + footerAuthor.height
            val available = HEIGHT - MARGIN * 2 - footerHeight - 72

            // The largest size at which the whole passage fits; long passages get trimmed.
            var size = 66f
            var body = layout("“${text.trim()}”", serif, size, colors.ink, width, spacing = 1.32f)
            while (body.height > available && size > 30f) {
                size -= 3f
                body = layout("“${text.trim()}”", serif, size, colors.ink, width, spacing = 1.32f)
            }
            if (body.height > available) {
                val cut = text.take((text.length * available / body.height).toInt().coerceAtLeast(40))
                body = layout("“${cut.trimEnd()}…”", serif, size, colors.ink, width, spacing = 1.32f)
            }

            val top = MARGIN + (available - body.height).coerceAtLeast(0f) / 2
            canvas.draw(body, MARGIN, top)
            val footerTop = HEIGHT - MARGIN - footerHeight
            canvas.drawRect(MARGIN, footerTop - 36f, MARGIN + 64f, footerTop - 32f, fill(colors.bookcloth))
            canvas.draw(footerTitle, MARGIN, footerTop)
            canvas.draw(footerAuthor, MARGIN, footerTop + footerTitle.height + 14)
        } }
        send(context, card, "“${text.trim()}” — $title, $author")
    }

    suspend fun shareYear(context: Context, year: YearInReading, colors: BinderyColors) {
        val card = render("year-${year.year}", context) { page(colors) { canvas ->
            val serifBold = font(context, R.font.literata, 600)
            val sans = font(context, R.font.inter_tight, 500)
            val mono = ResourcesCompat.getFont(context, R.font.plex_mono_medium) ?: Typeface.MONOSPACE
            val width = (WIDTH - MARGIN * 2).toInt()
            var y = MARGIN

            fun line(text: String, face: Typeface, size: Float, color: Color, gap: Float) {
                val l = layout(text, face, size, color, width)
                canvas.draw(l, MARGIN, y)
                y += l.height + gap
            }

            line("A YEAR IN READING", sans, 30f, colors.bookcloth, 28f)
            line(year.year.toString(), serifBold, 150f, colors.ink, 64f)
            line(formatTotalReadingTime(year.totalSeconds).ifBlank { "0m" }, mono, 92f, colors.ink, 8f)
            line("spent reading", sans, 34f, colors.graphite, 52f)
            line(year.booksFinished.toString(), mono, 92f, colors.ink, 8f)
            line(if (year.booksFinished == 1) "book finished" else "books finished", sans, 34f, colors.graphite, 52f)
            line("${year.daysRead} days with a book · longest streak ${year.longestStreak}", sans, 32f, colors.graphite, 40f)
            year.topBook?.let {
                line("MOST READ", sans, 26f, colors.bookcloth, 12f)
                line(it, serifBold, 48f, colors.ink, 0f)
            }
        } }
        send(context, card, "My year in reading, ${year.year}")
    }

    /** The paper, its fore-edge down the right, and whatever [draw] puts on it. */
    private fun page(colors: BinderyColors, draw: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.paper.toArgb())
        val edge = 22f
        canvas.drawRect(WIDTH - edge, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), fill(colors.bookcloth))
        val leaf = fill(colors.paper.copy(alpha = 0.35f))
        var y = 0f
        while (y < HEIGHT) {
            canvas.drawRect(WIDTH - edge, y, WIDTH.toFloat(), y + 1.5f, leaf)
            y += 6f
        }
        draw(canvas)
        return bitmap
    }

    /**
     * Draws a card and saves it as [name].png, away from the main thread: laying the text
     * out, filling a 1080×1350 bitmap and compressing it takes long enough to stall a frame.
     */
    private suspend fun render(name: String, context: Context, draw: () -> Bitmap): File =
        withContext(Dispatchers.Default) {
            val bitmap = draw()
            val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }

    private fun send(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun font(context: Context, res: Int, weight: Int): Typeface {
        val base = ResourcesCompat.getFont(context, res) ?: Typeface.SERIF
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(
        text: String,
        face: Typeface,
        size: Float,
        color: Color,
        width: Int,
        spacing: Float = 1.15f,
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color.toArgb()
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()
    }

    private fun Canvas.draw(layout: StaticLayout, x: Float, y: Float) {
        save()
        translate(x, y)
        layout.draw(this)
        restore()
    }

    private fun fill(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
}
