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

    @Test
    fun `buildAllDailyPoints keeps full sorted history`() {
        val records = (1..15).map { day ->
            val dayStr = if (day < 10) "0$day" else "$day"
            HealthHistoryStore.DailyRecord(
                dateKey = "2026-11-$dayStr",
                lastReportedSteps = day * 1000,
                caloriesKcal = day * 40,
                distanceMeters = day * 700
            )
        }

        val allDaily = HealthHistoryStore.buildAllDailyPoints(records)
        assertEquals(15, allDaily.size)
        assertEquals("01.11", allDaily.first().label)
        assertEquals("15.11", allDaily.last().label)
    }

    @Test
    fun `calculateSummary computes correct metrics for week and month`() {
        // Create 10 days of data ending at 2026-11-14
        val points = (1..10).map { dayIdx ->
            val day = 4 + dayIdx // 5 to 14
            val dayStr = if (day < 10) "0$day" else "$day"
            com.digma.watchapp.model.DailyActivityPoint(
                dateKey = "2026-11-$dayStr",
                label = "$dayStr.11",
                steps = dayIdx * 1000, // 1000, 2000, ..., 10000
                caloriesKcal = dayIdx * 50,
                distanceMeters = dayIdx * 800
            )
        }

        // Fixed "today" = 2026-11-14 12:00:00 UTC
        val calendarNow = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 14, 12, 0, 0)
        }
        val nowMs = calendarNow.timeInMillis

        // 7 days summary (from 2026-11-08 to 2026-11-14, which corresponds to dayIdx 4..10)
        // Steps for dayIdx 4..10: 4000, 5000, 6000, 7000, 8000, 9000, 10000 -> Sum = 49000
        val weekSummary = HealthHistoryStore.calculateSummary(
            points = points,
            periodDays = 7,
            stepGoal = 8000,
            nowMs = nowMs
        )

        assertEquals(com.digma.watchapp.model.SummaryPeriod.WEEK, weekSummary.period)
        assertEquals(49000, weekSummary.totalSteps)
        assertEquals(7000, weekSummary.averageStepsPerDay)
        assertEquals(7, weekSummary.activeDaysCount)
        assertEquals(7, weekSummary.totalDaysCount)
        assertEquals(3, weekSummary.goalReachedDaysCount) // 8000, 9000, 10000
        assertEquals(10000, weekSummary.bestDaySteps)
        assertEquals("14.11", weekSummary.bestDayDate)

        // 30 days summary (all 10 days fall within 30 days window)
        // Total steps = sum(1..10)*1000 = 55000
        val monthSummary = HealthHistoryStore.calculateSummary(
            points = points,
            periodDays = 30,
            stepGoal = 8000,
            nowMs = nowMs
        )

        assertEquals(com.digma.watchapp.model.SummaryPeriod.MONTH, monthSummary.period)
        assertEquals(55000, monthSummary.totalSteps)
        assertEquals(5500, monthSummary.averageStepsPerDay)
        assertEquals(10, monthSummary.activeDaysCount)
        assertEquals(30, monthSummary.totalDaysCount)
        assertEquals(3, monthSummary.goalReachedDaysCount)
    }
}
