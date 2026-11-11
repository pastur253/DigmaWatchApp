package com.digma.watchapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.digma.watchapp.ble.BleManager
import com.digma.watchapp.ble.BleScanner
import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DigmaApp : Application() {

    companion object {
        const val CHANNEL_SYNC_SERVICE = "digma_watch_sync"
        private const val PREFS_SETTINGS = "digma_watch_settings"
        private const val KEY_24H = "is_24h"
        private const val KEY_RAISE_TO_WAKE = "raise_to_wake"
        private const val KEY_SEDENTARY = "sedentary_reminder"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_CALLS = "forward_calls"
        private const val KEY_SMS = "forward_sms"
        private const val KEY_APPS = "forward_apps"
        private const val KEY_ALL_APPS = "forward_all_apps"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
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
        _settings.value = loadSettings()
        bleManager = BleManager(this)
        bleScanner = BleScanner(this)
        updateManager = UpdateManager(this)

        createNotificationChannels()
    }

    fun updateSettings(newSettings: WatchSettings) {
        _settings.value = newSettings
        saveSettings(newSettings)
    }

    fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(':').any { entry ->
            entry.contains(packageName, ignoreCase = false)
        }
    }

    private fun loadSettings(): WatchSettings {
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val defaults = WatchSettings()
        val savedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, null)
            ?.toSet()
            ?.let { existing ->
                // Preserve user list if explicitly saved, while upgrading defaults with exteraGram if missing.
                if (prefs.contains(KEY_ALL_APPS)) existing else existing + "com.exteragram.messenger"
            }
            ?: defaults.allowedPackages
        return WatchSettings(
            is24HourFormat = prefs.getBoolean(KEY_24H, defaults.is24HourFormat),
            raiseToWake = prefs.getBoolean(KEY_RAISE_TO_WAKE, defaults.raiseToWake),
            sedentaryReminder = prefs.getBoolean(KEY_SEDENTARY, defaults.sedentaryReminder),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, defaults.vibrationEnabled),
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, defaults.notificationsEnabled),
            forwardCalls = prefs.getBoolean(KEY_CALLS, defaults.forwardCalls),
            forwardSms = prefs.getBoolean(KEY_SMS, defaults.forwardSms),
            forwardApps = prefs.getBoolean(KEY_APPS, defaults.forwardApps),
            forwardAllApps = prefs.getBoolean(KEY_ALL_APPS, defaults.forwardAllApps),
            allowedPackages = savedPackages
        )
    }

    private fun saveSettings(settings: WatchSettings) {
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_24H, settings.is24HourFormat)
            .putBoolean(KEY_RAISE_TO_WAKE, settings.raiseToWake)
            .putBoolean(KEY_SEDENTARY, settings.sedentaryReminder)
            .putBoolean(KEY_VIBRATION, settings.vibrationEnabled)
            .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
            .putBoolean(KEY_CALLS, settings.forwardCalls)
            .putBoolean(KEY_SMS, settings.forwardSms)
            .putBoolean(KEY_APPS, settings.forwardApps)
            .putBoolean(KEY_ALL_APPS, settings.forwardAllApps)
            .putStringSet(KEY_ALLOWED_PACKAGES, settings.allowedPackages.toSet())
            .apply()
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
