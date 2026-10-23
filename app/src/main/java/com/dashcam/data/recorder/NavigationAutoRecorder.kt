package com.dashcam.data.recorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dashcam.R
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.repository.RecorderController
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts recording when navigation starts and, optionally, stops it a while after navigation
 * ends. Android doesn't let a background app turn on the camera, so recording starts by bringing
 * DashCam to the front (allowed with "Display over other apps"), which then moves into
 * picture-in-picture or the background. Without that permission, a notification asks for a tap.
 */
class NavigationAutoRecorder(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val recorder: RecorderController,
    private val scope: CoroutineScope,
) {
    private var navigating = false
    /** A start was requested for navigation; becomes [startedByNavigation] once recording starts. */
    private var startPending = false
    private var startedByNavigation = false
    private var stopJob: Job? = null

    init {
        scope.launch {
            recorder.state.collect { state ->
                when (state) {
                    is RecorderState.Recording -> if (startPending) {
                        startPending = false
                        startedByNavigation = true
                    }
                    RecorderState.Idle -> startedByNavigation = false
                }
            }
        }
    }

    /** [navigationApp] is the package that is navigating, or null when nothing is. */
    fun onNavigationChanged(navigationApp: String?) {
        val active = navigationApp != null
        if (active == navigating) return
        navigating = active
        scope.launch { if (navigationApp != null) onNavigationStarted(navigationApp) else onNavigationEnded() }
    }

    private suspend fun onNavigationStarted(navigationApp: String) {
        stopJob?.cancel()
        val settings = settingsRepository.settings.first()
        if (!settings.autoRecordOnNavigation || recorder.state.value is RecorderState.Recording) return
        startPending = true
        val intent = MainActivity.autoRecordIntent(context, returnTo = navigationApp)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't open DashCam from the background", e)
            }
        }
        showTapToRecordNotification(intent)
    }

    private suspend fun onNavigationEnded() {
        startPending = false
        notificationManager.cancel(NOTIFICATION_ID)
        val settings = settingsRepository.settings.first()
        val stopDelay = settings.navigationStopDelay.delayMillis ?: return
        if (!startedByNavigation) return
        // A delay lets a short break in navigation (a stop along the way) keep recording.
        stopJob = scope.launch {
            delay(stopDelay)
            if (startedByNavigation && recorder.state.value is RecorderState.Recording) recorder.stop()
        }
    }

    private fun showTapToRecordNotification(intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notification_channel_navigation),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(context.getString(R.string.notification_navigation_title))
            .setContentText(context.getString(R.string.notification_navigation_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val TAG = "NavigationAutoRecorder"
        const val CHANNEL_ID = "navigation"
        const val NOTIFICATION_ID = 2
    }
}
