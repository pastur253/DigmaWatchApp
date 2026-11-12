package com.digma.watchapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class HealthHistoryAggregationTest {

    @Test
    fun `buildHourlyPoints prefers 0x10 bucket sums over cumulative deltas without double counting`() {
        val record = HealthHistoryStore.DailyRecord(
            dateKey = "2026-11-12",
            lastReportedSteps = 850,
            caloriesKcal = 40,
            distanceMeters = 600,
            deltaHourlySteps = mutableMapOf(
                9 to 300,
                14 to 550
            ),
            bucketMinuteSteps = mutableMapOf(
                14 * 60 + 0 to 120,
                14 * 60 + 1 to 180
            )
        )

        val hourly = HealthHistoryStore.buildHourlyPoints(record)
        assertEquals(24, hourly.size)
        assertEquals(300, hourly[9].steps)
        assertEquals(300, hourly[14].steps)
        assertEquals(0, hourly[12].steps)
    }

    @Test
    fun `buildDailyPoints keeps last 7 sorted days and formats dd MM label`() {
        val records = (1..9).map { day ->
            HealthHistoryStore.DailyRecord(
                dateKey = "2026-11-0$day",
                lastReportedSteps = day * 1000,
                caloriesKcal = day * 40,
                distanceMeters = day * 700
            )
        }

        val daily = HealthHistoryStore.buildDailyPoints(records)
        assertEquals(7, daily.size)
        assertEquals("03.11", daily.first().label)
        assertEquals(3000, daily.first().steps)
        assertEquals("09.11", daily.last().label)
        assertEquals(9000, daily.last().steps)
    }

    @Test
    fun `resolveWatchEpochMs converts local wall-clock epoch back to UTC millis`() {
        val tz = TimeZone.getTimeZone("GMT+03:00")
        val utcSeconds = 1_794_480_000L
        val watchLocalSeconds = utcSeconds + 3 * 3600L
        val resolved = HealthHistoryStore.resolveWatchEpochMs(
            watchEpochSeconds = watchLocalSeconds,
            nowMs = (utcSeconds + 60L) * 1000L,
            timeZone = tz
        )
        assertEquals(utcSeconds * 1000L, resolved)
    }
}
