package com.digma.watchapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.digma.watchapp.ble.BleManager
import com.digma.watchapp.ble.BleScanner
import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DigmaApp : Application() {

    companion object {
        const val CHANNEL_SYNC_SERVICE = "digma_watch_sync"
        lateinit var instance: DigmaApp
            private set
    }

    lateinit var bleManager: BleManager
        private set

    lateinit var bleScanner: BleScanner
        private set

    lateinit var updateManager: UpdateManager
        private set

    private val _settings = MutableStateFlow(WatchSettings())
    val settings = _settings.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        bleManager = BleManager(this)
        bleScanner = BleScanner(this)
        updateManager = UpdateManager(this)

        createNotificationChannels()
    }

    fun updateSettings(newSettings: WatchSettings) {
        _settings.value = newSettings
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Синхронизация с часами"
            val descriptionText = "Фоновая поддержка соединения с Digma Smartline D3"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_SYNC_SERVICE, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
