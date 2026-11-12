package com.digma.watchapp.service

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationCollectorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifCollector"

        private val _isListenerConnected = MutableStateFlow(false)
        val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

        fun requestRebindIfEnabled(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, NotificationCollectorService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to request NotificationListenerService rebind", e)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isListenerConnected.value = true
        val bleManager = DigmaApp.instance.bleManager
        bleManager.logDiagnostic("Сервис уведомлений Android подключен")
        bleManager.connectSavedDeviceIfNeeded()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isListenerConnected.value = false
        DigmaApp.instance.bleManager.logDiagnostic("Сервис уведомлений Android отключен системой")
        Log.w(TAG, "NotificationListenerService disconnected")
    }

    override fun onDestroy() {
        _isListenerConnected.value = false
        super.onDestroy()
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
        val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lastBundle.getParcelable("sender_person", Person::class.java)?.name
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            (lastBundle.getParcelable("sender_person") as? Person)?.name
        } else {
            null
        }
        val sender = lastBundle.getCharSequence("sender")
            ?: personName
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
