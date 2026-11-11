package com.digma.watchapp.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState

class NotificationCollectorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifCollector"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DigmaApp.instance.bleManager.logDiagnostic("Сервис уведомлений Android подключен")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val app = DigmaApp.instance
        val settings = app.settings.value
        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        val decision = NotificationRelayPolicy.evaluateDecision(
            packageName = packageName,
            ownPackageName = applicationContext.packageName,
            category = notification.category,
            isOngoing = sbn.isOngoing,
            isGroupSummary = isGroupSummary,
            settings = settings
        )

        val forward = when (decision) {
            is NotificationRelayPolicy.Decision.Forward -> decision
            is NotificationRelayPolicy.Decision.Skip -> {
                if (decision.logToDiagnostics) {
                    app.bleManager.logDiagnostic("Уведомление [$packageName] пропущено: ${decision.reason}")
                }
                return
            }
        }

        if (app.bleManager.connectionState.value !is BleConnectionState.Connected) {
            app.bleManager.logDiagnostic("Уведомление [$packageName] не отправлено: часы не подключены")
            return
        }

        val extras = notification.extras ?: Bundle.EMPTY
        val (msgSender, msgText) = extractLatestMessagingEntry(extras)
        val appLabel = resolveAppLabel(packageName)

        val content = NotificationRelayPolicy.extractContent(
            title = extras.getCharSequence(Notification.EXTRA_TITLE),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            messagingSender = msgSender,
            messagingText = msgText,
            fallbackTitle = appLabel
        ) ?: return

        Log.d(TAG, "Relaying notification to watch: [$packageName] ${content.title}: ${content.text}")
        app.bleManager.logDiagnostic("Пересылка [$packageName] -> ${forward.type}: ${content.title}")
        app.bleManager.sendNotification(forward.type, content.title, content.text)
    }

    @Suppress("DEPRECATION")
    private fun extractLatestMessagingEntry(extras: Bundle): Pair<CharSequence?, CharSequence?> {
        val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return null to null

        val lastBundle = rawMessages.lastOrNull() as? Bundle ?: return null to null
        val text = lastBundle.getCharSequence("text")
        val sender = lastBundle.getCharSequence("sender")
            ?: lastBundle.getBundle("sender_person")?.getCharSequence("name")
        return sender to text
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info)?.toString().orEmpty()
        } catch (_: Exception) {
            packageName
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Ignored
    }
}
