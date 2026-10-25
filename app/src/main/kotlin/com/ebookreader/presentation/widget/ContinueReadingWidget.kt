package com.ebookreader.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.ebookreader.MainActivity
import com.ebookreader.R
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.ReadingCalendar
import com.ebookreader.domain.model.ReadingStatus
import com.ebookreader.domain.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The home-screen widget: the book being read, where it was left, and the reading
 * streak. Tapping it opens the book. Refreshed when the app is left, and hourly.
 */
@AndroidEntryPoint
class ContinueReadingWidget : AppWidgetProvider() {

    @Inject lateinit var bookRepository: BookRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        appScope.launch {
            try {
                val views = render(context.applicationContext)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun render(context: Context): RemoteViews {
        val recent = bookRepository.getRecentBooks().first()
        // The book being read; a finished one only when nothing else is open.
        val book = recent.firstOrNull { it.readingStatus == ReadingStatus.READING } ?: recent.firstOrNull()
        val streak = ReadingCalendar(bookRepository.getAllReadingSessions().first()).currentStreak(LocalDate.now())

        val views = RemoteViews(context.packageName, R.layout.widget_continue)
        views.setTextViewText(
            R.id.widget_streak,
            when (streak) {
                0 -> "Read today to start a streak"
                1 -> "1-day streak"
                else -> "$streak-day streak"
            }
        )
        if (book == null) {
            views.setTextViewText(R.id.widget_eyebrow, "LIBRARY")
            views.setTextViewText(R.id.widget_title, "Nothing open yet")
            views.setTextViewText(R.id.widget_detail, "Tap to choose a book")
            views.setImageViewBitmap(R.id.widget_cover, null)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, null))
            return views
        }
        views.setTextViewText(R.id.widget_eyebrow, context.getString(R.string.widget_continue))
        views.setTextViewText(R.id.widget_title, book.title)
        views.setTextViewText(R.id.widget_detail, detail(book))
        val cover = book.coverPath?.let { path ->
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(File(path).path, options)
            }.getOrNull()
        }
        // Without a cover the plain bookcloth board behind the image shows.
        views.setImageViewBitmap(R.id.widget_cover, cover)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, book.id))
        return views
    }

    private fun detail(book: Book): String {
        val percent = "${(book.readingProgress * 100).roundToInt()}%"
        return listOfNotNull(book.positionLabel?.takeIf { it.isNotBlank() }, percent).joinToString(" · ")
    }

    private fun openApp(context: Context, bookId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        bookId?.let { intent.putExtra(EXTRA_BOOK_ID, it) }
        // One request code per book, so a stale intent is never reused for another one.
        return PendingIntent.getActivity(
            context, bookId?.toInt() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_BOOK_ID = "com.ebookreader.widget.BOOK_ID"

        /** Redraws every placed widget, after reading changed what it shows. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ContinueReadingWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
