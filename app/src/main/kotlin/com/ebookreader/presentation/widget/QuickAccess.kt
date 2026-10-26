package com.ebookreader.presentation.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ebookreader.MainActivity
import com.ebookreader.R
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The intent that opens book [bookId] in the app, from a shortcut or the tile. */
internal fun openBookIntent(context: Context, bookId: Long?): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_BOOK)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { bookId?.let { putExtra(ContinueReadingWidget.EXTRA_BOOK_ID, it) } }

internal const val ACTION_OPEN_BOOK = "com.ebookreader.action.OPEN_BOOK"

/**
 * The launcher's shortcuts: a long press on the app icon lists the last three books read,
 * each opening straight into its reader.
 */
object BookShortcuts {
    private const val MAX = 3

    suspend fun refresh(context: Context, bookRepository: BookRepository) {
        val books = bookRepository.getRecentlyRead(MAX)
        val shortcuts = books.mapIndexed { rank, book ->
            ShortcutInfoCompat.Builder(context, "book_${book.id}")
                .setShortLabel(book.title.take(24))
                .setLongLabel(book.title.take(40))
                .setIcon(iconFor(book))
                .setIntent(openBookIntent(context, book.id))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** The cover, cut square around its top: the part of a cover a title sits in. */
    private fun iconFor(book: Book): IconCompat {
        val cover = book.coverPath?.let { path ->
            runCatching {
                BitmapFactory.decodeFile(File(path).path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull()
        } ?: return plainIcon(book)
        val side = minOf(cover.width, cover.height)
        val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, 0, side, side)
        return IconCompat.createWithBitmap(Bitmap.createScaledBitmap(square, 192, 192, true))
    }

    /** A coverless book: its initial on its cloth. */
    private fun plainIcon(book: Book): IconCompat {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cloth = com.ebookreader.presentation.common.clothFor(book.title)
        canvas.drawColor(android.graphics.Color.argb(255, (cloth.red * 255).toInt(), (cloth.green * 255).toInt(), (cloth.blue * 255).toInt()))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xF1, 0xEA, 0xDB)
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.SERIF
        }
        val initial = book.title.trim().firstOrNull()?.uppercase() ?: "?"
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawText(initial, bounds.centerX(), bounds.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}

/**
 * A Quick Settings tile that opens the book being read, from anywhere. It shows the
 * book's title; tapped, it opens the reader on top of the library.
 */
@AndroidEntryPoint
class ContinueReadingTileService : TileService() {

    @Inject lateinit var bookRepository: BookRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var bookId: Long? = null

    override fun onStartListening() {
        super.onStartListening()
        appScope.launch {
            val book = current()
            bookId = book?.id
            val tile = qsTile ?: return@launch
            tile.label = book?.title?.take(30) ?: getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (book == null) getString(R.string.tile_empty) else getString(R.string.tile_label)
            }
            tile.icon = Icon.createWithResource(this@ContinueReadingTileService, R.drawable.ic_tile_book)
            tile.state = if (book == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = openBookIntent(this, bookId)
        val open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }

    /** The book being read; a finished one only when nothing else is open. */
    private suspend fun current(): Book? {
        val recent = bookRepository.getRecentlyRead(5)
        return recent.firstOrNull { it.readingStatus == ReadingStatus.READING } ?: recent.firstOrNull()
    }
}
