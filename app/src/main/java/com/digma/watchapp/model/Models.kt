package com.digma.watchapp.model

data class WatchDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val isConnected: Boolean = false
)

data class HealthMetrics(
    val steps: Int = 0,
    val stepGoal: Int = 10000,
    val caloriesKcal: Int = 0,
    val distanceMeters: Int = 0,
    val heartRateBpm: Int = 0,
    val spO2Percent: Int = 0,
    val batteryPercent: Int = 0,
    val isMeasuringHeartRate: Boolean = false,
    val lastSyncTimestamp: Long = 0L
)

data class WatchSettings(
    val is24HourFormat: Boolean = true,
    val raiseToWake: Boolean = true,
    val sedentaryReminder: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val forwardCalls: Boolean = true,
    val forwardSms: Boolean = true,
    val forwardApps: Boolean = true,
    val allowedPackages: Set<String> = setOf(
        "org.telegram.messenger",
        "com.whatsapp",
        "com.viber.voip",
        "com.vkontakte.android"
    )
)

data class ActivityHistoryItem(
    val timestamp: Long,
    val steps: Int,
    val heartRate: Int,
    val calories: Int
)
