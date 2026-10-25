package com.ebookreader.data.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ebookreader.MainActivity
import com.ebookreader.R
import com.ebookreader.data.local.dao.ReadingSessionDao
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.ReadingGoals
import com.ebookreader.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The evening nudge for the daily goal. It is not exact, so it needs no exact-alarm
 * permission, but it is held to a short window: a daily inexact repeating alarm may be
 * put off by most of a day, and an evening reminder at three in the morning is worse
 * than none. Each firing arms the next day's. It stays quiet on days the goal is met.
 */
@Singleton
class ReadingReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: ReadingSessionDao,
) {
    fun schedule(goals: ReadingGoals) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent()
        alarms.cancel(pending)
        if (!goals.reminder) return
        val time = LocalTime.of(goals.reminderAt / 60, goals.reminderAt % 60)
        var next = LocalDateTime.of(LocalDate.now(), time)
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
    }

    /** Posts the nudge if today's reading has not reached the goal. */
    suspend fun remindIfDue(goals: ReadingGoals) {
        if (!goals.reminder) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val minutes = (sessionDao.secondsSince(startOfDay) / 60).toInt()
        val goal = goals.minutesPerDay
        val text = when {
            goal > 0 && minutes >= goal -> return
            goal > 0 && minutes > 0 -> "${goal - minutes} more minutes and today's reading is done."
            goal > 0 -> "$goal minutes of reading today? Your book is where you left it."
            minutes > 0 -> return
            else -> "A few pages before the day ends? Your book is where you left it."
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The evening reminder for your daily reading goal"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Time to read")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_REMIND = "com.ebookreader.action.READING_REMINDER"
        private const val CHANNEL = "reading_reminders"
        private const val NOTIFICATION_ID = 0x7EAD
        /** How late the reminder may come. */
        private const val WINDOW_MS = 15 * 60 * 1000L
    }
}

/** The alarm going off, and the device starting up, which clears every alarm. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReadingReminders
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val goals = settings.getSettings().first().goals
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> reminders.schedule(goals)
                    ReadingReminders.ACTION_REMIND -> {
                        reminders.remindIfDue(goals)
                        reminders.schedule(goals)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
