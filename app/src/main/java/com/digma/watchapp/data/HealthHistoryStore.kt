package com.digma.watchapp.data

import android.content.Context
import com.digma.watchapp.model.ActivityPeriodSummary
import com.digma.watchapp.model.DailyActivityPoint
import com.digma.watchapp.model.HeartRateSample
import com.digma.watchapp.model.HourlyActivityPoint
import com.digma.watchapp.model.SummaryPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HealthHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val hrSamples = mutableListOf<HeartRateSample>()
    private val dailyMap = linkedMapOf<String, DailyRecord>()

    private val _heartRateHistory = MutableStateFlow<List<HeartRateSample>>(emptyList())
    val heartRateHistory: StateFlow<List<HeartRateSample>> = _heartRateHistory.asStateFlow()

    private val _hourlyActivity = MutableStateFlow<List<HourlyActivityPoint>>(emptyList())
    val hourlyActivity: StateFlow<List<HourlyActivityPoint>> = _hourlyActivity.asStateFlow()

    private val _dailyActivity = MutableStateFlow<List<DailyActivityPoint>>(emptyList())
    val dailyActivity: StateFlow<List<DailyActivityPoint>> = _dailyActivity.asStateFlow()

    private val _allDailyActivity = MutableStateFlow<List<DailyActivityPoint>>(emptyList())
    val allDailyActivity: StateFlow<List<DailyActivityPoint>> = _allDailyActivity.asStateFlow()

    init {
        loadFromPrefs()
        publishFlowsLocked()
    }

    fun getActivitySummary(
        period: SummaryPeriod,
        stepGoal: Int,
        nowMs: Long = System.currentTimeMillis()
    ): ActivityPeriodSummary {
        return calculateSummary(_allDailyActivity.value, period.days, stepGoal, nowMs)
    }

    fun recordHeartRate(bpm: Int, timestampMs: Long = System.currentTimeMillis()) {
        if (bpm !in 25..250) return
        synchronized(lock) {
            // Avoid flooding the chart with identical consecutive readings within 15 seconds.
            val last = hrSamples.lastOrNull()
            if (last != null &&
                kotlin.math.abs(timestampMs - last.timestampMs) < HR_DEDUP_WINDOW_MS &&
                last.bpm == bpm
            ) {
                return
            }
            val duplicateAtSameTime = hrSamples.any {
                kotlin.math.abs(it.timestampMs - timestampMs) < 5_000L && it.bpm == bpm
            }
            if (duplicateAtSameTime) return

            hrSamples.add(HeartRateSample(timestampMs = timestampMs, bpm = bpm))
            hrSamples.sortBy { it.timestampMs }
            trimHeartRateSamplesLocked()
            saveHeartRateLocked()
            publishFlowsLocked()
        }
    }

    fun recordHeartRateBatch(baseEpochSeconds: Long, bpmValues: List<Int>, nowMs: Long = System.currentTimeMillis()) {
        val valid = bpmValues.filter { it in 25..250 }
        if (valid.isEmpty()) return
        val baseMs = resolveWatchEpochMs(baseEpochSeconds, nowMs)
        synchronized(lock) {
            valid.forEachIndexed { index, bpm ->
                val sampleTs = baseMs + index * 60_000L
                val exists = hrSamples.any {
                    kotlin.math.abs(it.timestampMs - sampleTs) < 30_000L && it.bpm == bpm
                }
                if (!exists) {
                    hrSamples.add(HeartRateSample(timestampMs = sampleTs, bpm = bpm))
                }
            }
            hrSamples.sortBy { it.timestampMs }
            trimHeartRateSamplesLocked()
            saveHeartRateLocked()
            publishFlowsLocked()
        }
    }

    fun recordCurrentActivity(
        steps: Int,
        caloriesKcal: Int,
        distanceMeters: Int,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (steps < 0) return
        synchronized(lock) {
            val dateKey = formatDateKey(timestampMs)
            val hour = hourOfDay(timestampMs)
            val existing = dailyMap[dateKey] ?: DailyRecord(dateKey = dateKey)

            val delta = when {
                steps > existing.lastReportedSteps -> steps - existing.lastReportedSteps
                steps < existing.lastReportedSteps && steps > 0 -> {
                    // Watch counter reset (e.g. new session/reboot) while date stayed the same.
                    0
                }
                else -> 0
            }

            if (delta > 0) {
                existing.deltaHourlySteps[hour] = (existing.deltaHourlySteps[hour] ?: 0) + delta
            }

            existing.lastReportedSteps = maxOf(existing.lastReportedSteps, steps)
            if (steps < existing.lastReportedSteps && existing.deltaHourlySteps.isEmpty() && steps > 0) {
                existing.deltaHourlySteps[hour] = steps
            }
            existing.caloriesKcal = maxOf(existing.caloriesKcal, caloriesKcal.coerceAtLeast(0))
            existing.distanceMeters = maxOf(existing.distanceMeters, distanceMeters.coerceAtLeast(0))

            dailyMap[dateKey] = existing
            trimDailyMapLocked()
            saveDailyLocked()
            publishFlowsLocked()
        }
    }

    fun recordActivityBuckets(baseEpochSeconds: Long, stepSamples: List<Int>, nowMs: Long = System.currentTimeMillis()) {
        if (stepSamples.isEmpty() || baseEpochSeconds <= 0L) return
        val baseMs = resolveWatchEpochMs(baseEpochSeconds, nowMs)
        synchronized(lock) {
            stepSamples.forEachIndexed { minuteOffset, stepCount ->
                if (stepCount <= 0) return@forEachIndexed
                val ts = baseMs + minuteOffset * 60_000L
                val dateKey = formatDateKey(ts)
                val hour = hourOfDay(ts)
                val minute = minuteOfHour(ts)
                val record = dailyMap[dateKey] ?: DailyRecord(dateKey = dateKey)
                val minuteKey = hour * 60 + minute
                val prev = record.bucketMinuteSteps[minuteKey] ?: 0
                if (stepCount > prev) {
                    record.bucketMinuteSteps[minuteKey] = stepCount
                }
                dailyMap[dateKey] = record
            }
            trimDailyMapLocked()
            saveDailyLocked()
            publishFlowsLocked()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            hrSamples.clear()
            dailyMap.clear()
            prefs.edit().clear().apply()
            publishFlowsLocked()
        }
    }

    private fun publishFlowsLocked() {
        val todayKey = formatDateKey(System.currentTimeMillis())
        val todayRecord = dailyMap[todayKey]
        val allRecords = dailyMap.values.toList()
        _heartRateHistory.value = hrSamples.toList()
        _hourlyActivity.value = buildHourlyPoints(todayRecord)
        _dailyActivity.value = buildDailyPoints(allRecords)
        _allDailyActivity.value = buildAllDailyPoints(allRecords)
    }

    private fun trimHeartRateSamplesLocked() {
        val cutoff = System.currentTimeMillis() - MAX_RETENTION_DAYS * DAY_MS
        hrSamples.removeAll { it.timestampMs < cutoff }
        if (hrSamples.size > MAX_HR_SAMPLES) {
            val drop = hrSamples.size - MAX_HR_SAMPLES
            hrSamples.subList(0, drop).clear()
        }
    }

    private fun trimDailyMapLocked() {
        if (dailyMap.size <= MAX_RETENTION_DAYS) return
        val keysToKeep = dailyMap.keys.sorted().takeLast(MAX_RETENTION_DAYS).toSet()
        val iter = dailyMap.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next() !in keysToKeep) iter.remove()
        }
    }

    private fun loadFromPrefs() {
        val hrRaw = prefs.getString(KEY_HR_SAMPLES, null)
        if (!hrRaw.isNullOrBlank()) {
            try {
                val arr = JSONArray(hrRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val ts = obj.optLong("t", 0L)
                    val bpm = obj.optInt("b", 0)
                    if (ts > 0L && bpm in 25..250) {
                        hrSamples.add(HeartRateSample(ts, bpm))
                    }
                }
                hrSamples.sortBy { it.timestampMs }
            } catch (_: Exception) {
                hrSamples.clear()
            }
        }

        val dailyRaw = prefs.getString(KEY_DAILY_RECORDS, null)
        if (!dailyRaw.isNullOrBlank()) {
            try {
                val arr = JSONArray(dailyRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val dateKey = obj.optString("d", "")
                    if (dateKey.isBlank()) continue
                    val record = DailyRecord(
                        dateKey = dateKey,
                        lastReportedSteps = obj.optInt("s", 0),
                        caloriesKcal = obj.optInt("c", 0),
                        distanceMeters = obj.optInt("m", 0)
                    )
                    val hourlyObj = obj.optJSONObject("dh")
                    hourlyObj?.keys()?.forEach { hKey ->
                        val hour = hKey.toIntOrNull()
                        if (hour != null && hour in 0..23) {
                            record.deltaHourlySteps[hour] = hourlyObj.optInt(hKey, 0)
                        }
                    }
                    val bucketObj = obj.optJSONObject("bm")
                    bucketObj?.keys()?.forEach { mKey ->
                        val minIdx = mKey.toIntOrNull()
                        if (minIdx != null && minIdx in 0..1439) {
                            record.bucketMinuteSteps[minIdx] = bucketObj.optInt(mKey, 0)
                        }
                    }
                    dailyMap[dateKey] = record
                }
            } catch (_: Exception) {
                dailyMap.clear()
            }
        }
    }

    private fun saveHeartRateLocked() {
        val arr = JSONArray()
        for (sample in hrSamples) {
            arr.put(JSONObject().put("t", sample.timestampMs).put("b", sample.bpm))
        }
        prefs.edit().putString(KEY_HR_SAMPLES, arr.toString()).apply()
    }

    private fun saveDailyLocked() {
        val arr = JSONArray()
        for (record in dailyMap.values) {
            val dh = JSONObject()
            record.deltaHourlySteps.forEach { (h, v) -> dh.put(h.toString(), v) }
            val bm = JSONObject()
            record.bucketMinuteSteps.forEach { (m, v) -> bm.put(m.toString(), v) }
            arr.put(
                JSONObject()
                    .put("d", record.dateKey)
                    .put("s", record.lastReportedSteps)
                    .put("c", record.caloriesKcal)
                    .put("m", record.distanceMeters)
                    .put("dh", dh)
                    .put("bm", bm)
            )
        }
        prefs.edit().putString(KEY_DAILY_RECORDS, arr.toString()).apply()
    }

    internal data class DailyRecord(
        val dateKey: String,
        var lastReportedSteps: Int = 0,
        var caloriesKcal: Int = 0,
        var distanceMeters: Int = 0,
        val deltaHourlySteps: MutableMap<Int, Int> = mutableMapOf(),
        val bucketMinuteSteps: MutableMap<Int, Int> = mutableMapOf()
    )

    companion object {
        private const val PREFS_NAME = "digma_health_history"
        private const val KEY_HR_SAMPLES = "hr_samples"
        private const val KEY_DAILY_RECORDS = "daily_records"
        private const val MAX_HR_SAMPLES = 500
        private const val MAX_RETENTION_DAYS = 60
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val HR_DEDUP_WINDOW_MS = 15_000L

        internal fun buildHourlyPoints(record: DailyRecord?): List<HourlyActivityPoint> {
            val bucketHourly = IntArray(24)
            record?.bucketMinuteSteps?.forEach { (minIdx, steps) ->
                val hour = (minIdx / 60).coerceIn(0, 23)
                bucketHourly[hour] += steps
            }

            return (0..23).map { hour ->
                val steps = if (bucketHourly[hour] > 0) {
                    bucketHourly[hour]
                } else {
                    record?.deltaHourlySteps?.get(hour) ?: 0
                }
                HourlyActivityPoint(hour = hour, steps = steps)
            }
        }

        internal fun buildDailyPoints(records: List<DailyRecord>): List<DailyActivityPoint> {
            val sorted = records.sortedBy { it.dateKey }.takeLast(7)
            return sorted.map { rec ->
                val bucketTotal = rec.bucketMinuteSteps.values.sum()
                val effectiveSteps = maxOf(rec.lastReportedSteps, bucketTotal)
                val label = if (rec.dateKey.length == 10) {
                    "${rec.dateKey.substring(8, 10)}.${rec.dateKey.substring(5, 7)}"
                } else {
                    rec.dateKey
                }
                DailyActivityPoint(
                    dateKey = rec.dateKey,
                    label = label,
                    steps = effectiveSteps,
                    caloriesKcal = rec.caloriesKcal,
                    distanceMeters = rec.distanceMeters
                )
            }
        }

        internal fun buildAllDailyPoints(records: List<DailyRecord>): List<DailyActivityPoint> {
            val sorted = records.sortedBy { it.dateKey }
            return sorted.map { rec ->
                val bucketTotal = rec.bucketMinuteSteps.values.sum()
                val effectiveSteps = maxOf(rec.lastReportedSteps, bucketTotal)
                val label = if (rec.dateKey.length == 10) {
                    "${rec.dateKey.substring(8, 10)}.${rec.dateKey.substring(5, 7)}"
                } else {
                    rec.dateKey
                }
                DailyActivityPoint(
                    dateKey = rec.dateKey,
                    label = label,
                    steps = effectiveSteps,
                    caloriesKcal = rec.caloriesKcal,
                    distanceMeters = rec.distanceMeters
                )
            }
        }

        fun calculateSummary(
            points: List<DailyActivityPoint>,
            periodDays: Int,
            stepGoal: Int,
            nowMs: Long = System.currentTimeMillis()
        ): ActivityPeriodSummary {
            val cutoffDateKey = formatDateKey(nowMs - (periodDays - 1) * DAY_MS)
            val periodPoints = points.filter { it.dateKey >= cutoffDateKey }

            val totalSteps = periodPoints.sumOf { it.steps }
            val totalCalories = periodPoints.sumOf { it.caloriesKcal }
            val totalDistance = periodPoints.sumOf { it.distanceMeters }
            val activeDays = periodPoints.count { it.steps > 0 }
            val goalReachedDays = periodPoints.count { it.steps >= stepGoal }
            val bestDay = periodPoints.maxByOrNull { it.steps }

            val divisor = if (activeDays > 0) activeDays else 1
            val avgSteps = if (activeDays > 0) totalSteps / divisor else 0
            val avgCalories = if (activeDays > 0) totalCalories / divisor else 0
            val avgDistance = if (activeDays > 0) totalDistance / divisor else 0

            return ActivityPeriodSummary(
                period = if (periodDays <= 7) SummaryPeriod.WEEK else SummaryPeriod.MONTH,
                totalSteps = totalSteps,
                averageStepsPerDay = avgSteps,
                totalCaloriesKcal = totalCalories,
                averageCaloriesPerDay = avgCalories,
                totalDistanceMeters = totalDistance,
                averageDistanceMetersPerDay = avgDistance,
                activeDaysCount = activeDays,
                totalDaysCount = periodDays,
                goalReachedDaysCount = goalReachedDays,
                bestDaySteps = bestDay?.steps ?: 0,
                bestDayDate = if (bestDay != null && bestDay.steps > 0) bestDay.label else ""
            )
        }

        internal fun resolveWatchEpochMs(
            watchEpochSeconds: Long,
            nowMs: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault()
        ): Long {
            if (watchEpochSeconds < 1_500_000_000L) {
                return nowMs
            }
            val rawMs = watchEpochSeconds * 1000L
            val offsetMs = timeZone.getOffset(rawMs).toLong()
            val adjustedMs = rawMs - offsetMs
            return if (adjustedMs <= nowMs + 10 * 60_000L) adjustedMs else rawMs
        }

        private fun formatDateKey(timestampMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))

        private fun hourOfDay(timestampMs: Long): Int =
            Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.HOUR_OF_DAY)

        private fun minuteOfHour(timestampMs: Long): Int =
            Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.MINUTE)
    }
}
