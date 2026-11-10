package com.digma.watchapp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.R
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchSyncService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WatchSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WatchSyncService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification("Подключение к часам...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        observeConnectionAndSync()
        return START_STICKY
    }

    private fun observeConnectionAndSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val bleManager = DigmaApp.instance.bleManager

            launch {
                bleManager.connectionState.collect { state ->
                    val text = when (state) {
                        is BleConnectionState.Connected -> "Подключено: ${state.deviceName}"
                        is BleConnectionState.Connecting -> "Подключение к часам..."
                        is BleConnectionState.Syncing -> "Синхронизация данных..."
                        is BleConnectionState.Disconnected -> "Часы отключены"
                        else -> "Ожидание..."
                    }
                    updateNotification(text)
                }
            }

            // Periodic sync loop every 15 minutes when connected
            while (isActive) {
                delay(15 * 60 * 1000L)
                if (bleManager.connectionState.value is BleConnectionState.Connected) {
                    bleManager.syncTime()
                    bleManager.queryBattery()
                    bleManager.requestActivity()
                }
            }
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, DigmaApp.CHANNEL_SYNC_SERVICE)
            .setContentTitle("Digma Smartline D3")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildForegroundNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        syncJob?.cancel()
        super.onDestroy()
    }
}
