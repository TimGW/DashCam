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
import android.os.PowerManager
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

/** Foreground service that keeps the camera recording while the app is in the background. */
class RecorderService : LifecycleService() {

    private val session get() = (application as DashCamApplication).container.recordingSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lifecycleScope.launch {
            session.state.collect { state ->
                if (running && state is RecorderState.Recording) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> session.end()
            ACTION_LOCK -> session.toggleLock()
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (running) return
        if (!goForeground()) {
            stopSelf()
            return
        }
        running = true
        acquireWakeLock()
        lifecycleScope.launch {
            session.begin().join()
            running = false
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
                getString(if (state?.isLocked == true) R.string.notification_recording_locked else R.string.notification_recording_text),
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
            .addAction(0, getString(lockLabel), servicePendingIntent(ACTION_LOCK, 1))
            .addAction(0, getString(R.string.action_stop), servicePendingIntent(ACTION_STOP, 2))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, RecorderService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

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
        session.end()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val ACTION_START = "com.dashcam.action.START"
        private const val ACTION_STOP = "com.dashcam.action.STOP"
        private const val ACTION_LOCK = "com.dashcam.action.LOCK"

        fun startIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_START)
    }
}
