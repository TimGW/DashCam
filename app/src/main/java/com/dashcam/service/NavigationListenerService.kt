package com.dashcam.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.dashcam.DashCamApplication

/**
 * Watches other apps' notifications to tell when navigation is running. Google Maps (and other
 * apps that use the navigation category) post a navigation notification only while navigating;
 * Waze only has a generic "Waze is running" notification, so for Waze running counts.
 */
class NavigationListenerService : NotificationListenerService() {

    private val autoRecorder get() = (application as DashCamApplication).container.navigationAutoRecorder

    override fun onListenerConnected() = update()

    override fun onNotificationPosted(sbn: StatusBarNotification) = update()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = update()

    private fun update() {
        // Throws if the listener was disconnected in the meantime (access revoked).
        val notifications = runCatching { activeNotifications }.getOrNull() ?: return
        autoRecorder.onNavigationChanged(notifications.firstOrNull { it.isNavigation() }?.packageName)
    }

    private fun StatusBarNotification.isNavigation(): Boolean =
        notification.category == Notification.CATEGORY_NAVIGATION || (packageName == WAZE && isOngoing)

    companion object {
        private const val WAZE = "com.waze"

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun componentName(context: Context) = ComponentName(context, NavigationListenerService::class.java)
    }
}
