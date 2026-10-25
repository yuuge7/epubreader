package com.ebookreader.data.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.ebookreader.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps reading aloud going with the screen off, and gives it a media notification and a
 * media session: play and pause from the lock screen, a headset button or a watch. Lives
 * exactly as long as [ReadAloudController] is active.
 */
@AndroidEntryPoint
class ReadAloudService : Service() {

    @Inject lateinit var readAloud: ReadAloudController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for the book being read aloud"
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "ReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = readAloud.play()
                override fun onPause() = readAloud.pause()
                override fun onStop() = readAloud.stop()
                override fun onSkipToNext() = readAloud.skip(+1)
                override fun onSkipToPrevious() = readAloud.skip(-1)
            })
            isActive = true
        }
        scope.launch {
            // The notification changes with play/pause and the chapter, not every sentence.
            readAloud.state
                .map { it.copy(sentence = null) }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> readAloud.toggle()
            ACTION_NEXT -> readAloud.skip(+1)
            ACTION_PREVIOUS -> readAloud.skip(-1)
            ACTION_STOP -> readAloud.stop()
        }
        // Started with startForegroundService: the notification must go up at once.
        render(readAloud.state.value)
        return START_NOT_STICKY
    }

    private fun render(state: ReadAloudState) {
        // A service started with startForegroundService must go to the foreground even
        // when reading was stopped in the moment it took to start.
        val notification = notification(state)
        if (!inForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        }
        if (!state.isActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            session.isActive = false
            stopSelf()
            return
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.author)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.chapterTitle)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.rate,
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun notification(state: ReadAloudState): Notification {
        fun action(icon: Int, title: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, action.hashCode(), Intent(this, ReadAloudService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle(state.title)
            .setContentText(state.chapterTitle)
            .setContentIntent(open)
            .setOngoing(state.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous sentence", ACTION_PREVIOUS))
            .addAction(
                if (state.isPlaying) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next sentence", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "read_aloud"
        const val NOTIFICATION_ID = 7
        const val ACTION_TOGGLE = "com.ebookreader.readaloud.TOGGLE"
        const val ACTION_NEXT = "com.ebookreader.readaloud.NEXT"
        const val ACTION_PREVIOUS = "com.ebookreader.readaloud.PREVIOUS"
        const val ACTION_STOP = "com.ebookreader.readaloud.STOP"
    }
}
