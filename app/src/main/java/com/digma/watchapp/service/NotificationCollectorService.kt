package com.digma.watchapp.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.protocol.DigmaProtocolCodec

class NotificationCollectorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifCollector"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val app = DigmaApp.instance
        val settings = app.settings.value

        if (!settings.notificationsEnabled) return
        if (app.bleManager.connectionState.value !is BleConnectionState.Connected) return

        val packageName = sbn.packageName ?: return

        // Skip our own notifications or ongoing foreground service notifications
        if (packageName == applicationContext.packageName) return
        if (sbn.isOngoing) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val type = when {
            packageName.contains("dialer") || packageName.contains("telecom") ->
                DigmaProtocolCodec.NotificationType.CALL
            packageName.contains("mms") || packageName.contains("messaging") ->
                DigmaProtocolCodec.NotificationType.SMS
            else ->
                DigmaProtocolCodec.NotificationType.APP_MESSAGE
        }

        val isAllowed = when (type) {
            DigmaProtocolCodec.NotificationType.CALL -> settings.forwardCalls
            DigmaProtocolCodec.NotificationType.SMS -> settings.forwardSms
            DigmaProtocolCodec.NotificationType.APP_MESSAGE -> {
                settings.forwardApps && (settings.allowedPackages.isEmpty() || settings.allowedPackages.contains(packageName))
            }
            else -> false
        }

        if (isAllowed) {
            Log.d(TAG, "Relaying notification to watch: [$packageName] $title: $text")
            app.bleManager.sendNotification(type, title, text)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Ignored
    }
}
