package com.digma.watchapp.model

import android.graphics.drawable.Drawable

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
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = 22,
    val quietHoursStartMinute: Int = 0,
    val quietHoursEndHour: Int = 7,
    val quietHoursEndMinute: Int = 0
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

enum class SummaryPeriod(val title: String, val days: Int) {
    WEEK("За неделю", 7),
    MONTH("За месяц", 30)
}

data class ActivityPeriodSummary(
    val period: SummaryPeriod,
    val totalSteps: Int = 0,
    val averageStepsPerDay: Int = 0,
    val totalCaloriesKcal: Int = 0,
    val averageCaloriesPerDay: Int = 0,
    val totalDistanceMeters: Int = 0,
    val averageDistanceMetersPerDay: Int = 0,
    val activeDaysCount: Int = 0,
    val totalDaysCount: Int = 0,
    val goalReachedDaysCount: Int = 0,
    val bestDaySteps: Int = 0,
    val bestDayDate: String = "" // "dd.MM"
)

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

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
