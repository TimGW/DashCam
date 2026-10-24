package com.dashcam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.first
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dashcam.DashCamApplication
import com.dashcam.R
import com.dashcam.domain.model.RecorderState
import com.dashcam.presentation.MainActivity
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the camera recording while the app is in the background.
 * If Android kills the process mid-recording, the sticky restart can't reopen the camera from the
 * background, so it tells the user instead of silently leaving them without a recording.
 */
class RecorderService : LifecycleService() {

    private val container get() = (application as DashCamApplication).container
    private val session get() = container.recordingSession
    private val sessionMarker get() = getSharedPreferences(MARKER_PREFS, MODE_PRIVATE)
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lifecycleScope.launch {
            session.state.collect { state ->
                if (running && state is RecorderState.Recording) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                    // Not reference counted: re-acquiring extends the timeout for long drives.
                    wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            null -> onRestartedAfterKill()
            ACTION_STOP -> session.end()
            ACTION_LOCK -> session.toggleLock()
            else -> start()
        }
        return START_STICKY
    }

    /** A sticky restart (null intent): the process died while a session was marked active. */
    private fun onRestartedAfterKill() {
        if (!running && sessionMarker.getBoolean(KEY_ACTIVE, false)) {
            sessionMarker.edit().putBoolean(KEY_ACTIVE, false).commit()
            container.eventLog.log("Recording was interrupted: the app was closed by Android")
            notifyInterrupted()
        }
        if (!running) stopSelf()
    }

    private fun notifyInterrupted() {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        val resume = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_START_RECORDING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(getString(R.string.notification_interrupted_title))
            .setContentText(getString(R.string.notification_interrupted_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(resume)
            .build()
        manager.notify(INTERRUPTED_NOTIFICATION_ID, notification)
    }

    private fun start() {
        if (running) return
        if (!goForeground()) {
            stopSelf()
            return
        }
        running = true
        sessionMarker.edit().putBoolean(KEY_ACTIVE, true).commit()
        acquireWakeLock()
        lifecycleScope.launch {
            if (container.settingsRepository.settings.first().remoteButtonMarksEvent) startRemoteButton()
        }
        lifecycleScope.launch {
            session.begin().join()
            stopRemoteButton()
            sessionMarker.edit().putBoolean(KEY_ACTIVE, false).commit()
            running = false
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Claims media buttons (Bluetooth headsets, steering-wheel play/pause) while recording, so a
     * press marks an event. Android routes buttons to the last active media session, so an app
     * that is playing music at the time may keep receiving them instead.
     */
    private fun startRemoteButton() {
        mediaSession = MediaSession(this, "DashCam").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        ?: return false
                    if (event.keyCode !in REMOTE_KEYS) return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        container.eventLog.log("Remote button pressed")
                        session.markEvent()
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    private fun stopRemoteButton() {
        mediaSession?.release()
        mediaSession = null
    }

    private fun goForeground(): Boolean {
        if (!granted(Manifest.permission.CAMERA)) return false
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), types)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground service", e)
            false
        }
    }

    private fun buildNotification(state: RecorderState.Recording?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val lockLabel = if (state?.isLocked == true) R.string.action_unlock else R.string.action_lock
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(
                getString(
                    when {
                        state?.isInterrupted == true -> R.string.notification_recording_interrupted
                        state?.isLocked == true -> R.string.notification_recording_locked
                        else -> R.string.notification_recording_text
                    },
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .apply {
                if (state != null) {
                    setUsesChronometer(true)
                    setWhen(state.startedAtMillis)
                    setShowWhen(true)
                }
            }
            .addAction(0, getString(lockLabel), lockPendingIntent(this))
            .addAction(0, getString(R.string.action_stop), stopPendingIntent(this))
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashCam:recording")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        stopRemoteButton()
        session.end()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val INTERRUPTED_NOTIFICATION_ID = 4
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val MARKER_PREFS = "recorder_session"
        private const val KEY_ACTIVE = "active"
        private val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val ACTION_START = "com.dashcam.action.START"
        private const val ACTION_STOP = "com.dashcam.action.STOP"
        private const val ACTION_LOCK = "com.dashcam.action.LOCK"

        fun startIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_START)

        /** Toggles the lock on the current clip; used by the notification and the PiP window. */
        fun lockPendingIntent(context: Context): PendingIntent = servicePendingIntent(context, ACTION_LOCK, 1)

        fun stopPendingIntent(context: Context): PendingIntent = servicePendingIntent(context, ACTION_STOP, 2)

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int) =
            PendingIntent.getService(
                context, requestCode, Intent(context, RecorderService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
