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
    val forwardAllApps: Boolean = true,
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES
) {
    companion object {
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
            "com.exteragram.messenger",
            "org.telegram.messenger",
            "org.telegram.plus",
            "org.thunderdog.challegram",
            "com.whatsapp",
            "com.viber.voip",
            "com.vkontakte.android",
            "com.vk.im"
        )
    }
}

data class ActivityHistoryItem(
    val timestamp: Long,
    val steps: Int,
    val heartRate: Int,
    val calories: Int
)

data class HeartRateSample(
    val timestampMs: Long,
    val bpm: Int
)

data class HourlyActivityPoint(
    val hour: Int, // 0..23
    val steps: Int
)

data class DailyActivityPoint(
    val dateKey: String, // yyyy-MM-dd
    val label: String,   // dd.MM
    val steps: Int,
    val caloriesKcal: Int,
    val distanceMeters: Int
)
