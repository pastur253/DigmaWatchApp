package com.digma.watchapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.model.DailyActivityPoint
import com.digma.watchapp.model.HeartRateSample
import com.digma.watchapp.model.HourlyActivityPoint
import com.digma.watchapp.ui.theme.HeartRateRed
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StepsOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class ActivityChartRange(val title: String) {
    HOURLY("По часам"),
    DAILY("По дням")
}

@Composable
fun ActivityChartCard(
    hourlyPoints: List<HourlyActivityPoint>,
    dailyPoints: List<DailyActivityPoint>,
    stepGoal: Int,
    modifier: Modifier = Modifier
) {
    var range by remember { mutableStateOf(ActivityChartRange.HOURLY) }
    var selectedHourlyIndex by remember { mutableIntStateOf(-1) }
    var selectedDailyIndex by remember { mutableIntStateOf(-1) }

    val hasHourlyData = hourlyPoints.any { it.steps > 0 }
    val hasDailyData = dailyPoints.any { it.steps > 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "График активности",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActivityChartRange.entries.forEach { option ->
                        FilterChip(
                            selected = range == option,
                            onClick = { range = option },
                            label = { Text(option.title, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StepsOrange.copy(alpha = 0.2f),
                                selectedLabelColor = StepsOrange
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (range) {
                ActivityChartRange.HOURLY -> {
                    if (!hasHourlyData) {
                        EmptyChartPlaceholder("Нет почасовых данных активности. Подключите часы и нажмите «Обновить».")
                    } else {
                        val effectiveSelected = selectedHourlyIndex.takeIf { it in hourlyPoints.indices }
                            ?: hourlyPoints.indexOfLast { it.steps > 0 }
                        val selectedPoint = hourlyPoints.getOrNull(effectiveSelected)
                        if (selectedPoint != null) {
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "%02d:00–%02d:59  •  %d шагов",
                                    selectedPoint.hour,
                                    selectedPoint.hour,
                                    selectedPoint.steps
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = StepsOrange
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val values = hourlyPoints.map { it.steps }
                        val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(100)
                        BarChartCanvas(
                            values = values,
                            maxValue = maxValue,
                            selectedIndex = effectiveSelected,
                            barColor = StepsOrange,
                            goalValue = null,
                            onSelectIndex = { selectedHourlyIndex = it }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("00", "06", "12", "18", "23").forEach { label ->
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                ActivityChartRange.DAILY -> {
                    if (!hasDailyData) {
                        EmptyChartPlaceholder("История по дням появится после синхронизации шагов с часов.")
                    } else {
                        val effectiveSelected = selectedDailyIndex.takeIf { it in dailyPoints.indices }
                            ?: dailyPoints.lastIndex
                        val selectedPoint = dailyPoints.getOrNull(effectiveSelected)
                        if (selectedPoint != null) {
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "%s  •  %d шагов  •  %d ккал  •  %.2f км",
                                    selectedPoint.label,
                                    selectedPoint.steps,
                                    selectedPoint.caloriesKcal,
                                    selectedPoint.distanceMeters / 1000f
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = StepsOrange
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val values = dailyPoints.map { it.steps }
                        val maxValue = maxOf(values.maxOrNull() ?: 0, stepGoal).coerceAtLeast(1000)
                        BarChartCanvas(
                            values = values,
                            maxValue = maxValue,
                            selectedIndex = effectiveSelected,
                            barColor = StepsOrange,
                            goalValue = stepGoal,
                            onSelectIndex = { selectedDailyIndex = it }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            dailyPoints.forEach { day ->
                                Text(
                                    text = day.label,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarChartCanvas(
    values: List<Int>,
    maxValue: Int,
    selectedIndex: Int,
    barColor: Color,
    goalValue: Int?,
    onSelectIndex: (Int) -> Unit
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "$maxValue", fontSize = 10.sp, color = labelColor)
            if (goalValue != null) {
                Text(text = "Цель: $goalValue", fontSize = 10.sp, color = PrimaryCyan)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.isNotEmpty() && size.width > 0) {
                            val idx = ((offset.x / size.width) * values.size)
                                .toInt()
                                .coerceIn(0, values.lastIndex)
                            onSelectIndex(idx)
                        }
                    }
                }
        ) {
            if (values.isEmpty()) return@Canvas
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            for (step in listOf(0f, 0.5f, 1f)) {
                val y = size.height * step
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (step < 1f) dashEffect else null
                )
            }

            if (goalValue != null && goalValue > 0 && maxValue > 0) {
                val goalRatio = (goalValue.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                val goalY = size.height * (1f - goalRatio)
                drawLine(
                    color = PrimaryCyan.copy(alpha = 0.65f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = dashEffect
                )
            }

            val slotWidth = size.width / values.size.toFloat()
            val barWidth = (slotWidth * 0.62f).coerceIn(4.dp.toPx(), 26.dp.toPx())
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            values.forEachIndexed { index, value ->
                val left = index * slotWidth + (slotWidth - barWidth) / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = corner
                )
                if (value > 0) {
                    val ratio = (value.toFloat() / maxValue.toFloat()).coerceIn(0.03f, 1f)
                    val barHeight = size.height * ratio
                    val top = size.height - barHeight
                    val isSelected = index == selectedIndex
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = if (isSelected) {
                                listOf(barColor, barColor.copy(alpha = 0.75f))
                            } else {
                                listOf(barColor.copy(alpha = 0.85f), barColor.copy(alpha = 0.4f))
                            },
                            startY = top,
                            endY = size.height
                        ),
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = corner
                    )
                }
            }
        }
    }
}

@Composable
fun HeartRateChartCard(
    samples: List<HeartRateSample>,
    modifier: Modifier = Modifier
) {
    val recentSamples = remember(samples) { samples.takeLast(48) }
    var selectedIndex by remember(recentSamples) { mutableIntStateOf(recentSamples.lastIndex) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "График пульса",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (recentSamples.isEmpty()) {
                EmptyChartPlaceholder("Замеров пульса пока нет. Запустите «Замер» на главной или обновите данные с часов.")
            } else {
                val minBpm = recentSamples.minOf { it.bpm }
                val maxBpm = recentSamples.maxOf { it.bpm }
                val avgBpm = recentSamples.map { it.bpm }.average().roundToInt()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HrStatBadge(label = "Мин", value = "$minBpm", modifier = Modifier.weight(1f))
                    HrStatBadge(label = "Средний", value = "$avgBpm", modifier = Modifier.weight(1f))
                    HrStatBadge(label = "Макс", value = "$maxBpm", modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(10.dp))

                val safeIdx = selectedIndex.coerceIn(0, recentSamples.lastIndex)
                val selectedSample = recentSamples[safeIdx]
                val timeFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
                Text(
                    text = "${timeFormat.format(Date(selectedSample.timestampMs))}  •  ${selectedSample.bpm} уд/мин",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = HeartRateRed
                )

                Spacer(modifier = Modifier.height(8.dp))

                val yMin = ((minBpm - 10) / 10 * 10).coerceAtLeast(30)
                val yMax = (((maxBpm + 19) / 10) * 10).coerceAtLeast(yMin + 20)
                val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "$yMax уд/мин", fontSize = 10.sp, color = labelColor)
                    Text(text = "Замеров: ${recentSamples.size}", fontSize = 10.sp, color = labelColor)
                }
                Spacer(modifier = Modifier.height(4.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .pointerInput(recentSamples) {
                            detectTapGestures { offset ->
                                if (recentSamples.size == 1) {
                                    selectedIndex = 0
                                } else if (recentSamples.size > 1 && size.width > 0) {
                                    val idx = ((offset.x / size.width) * (recentSamples.size - 1))
                                        .roundToInt()
                                        .coerceIn(0, recentSamples.lastIndex)
                                    selectedIndex = idx
                                }
                            }
                        }
                ) {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    for (step in listOf(0f, 0.5f, 1f)) {
                        val y = size.height * step
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = if (step < 1f) dashEffect else null
                        )
                    }

                    val rangeBpm = (yMax - yMin).coerceAtLeast(1).toFloat()
                    val points = recentSamples.mapIndexed { idx, sample ->
                        val x = if (recentSamples.size == 1) {
                            size.width / 2f
                        } else {
                            (idx.toFloat() / (recentSamples.size - 1).toFloat()) * size.width
                        }
                        val normY = ((sample.bpm - yMin).toFloat() / rangeBpm).coerceIn(0f, 1f)
                        val y = size.height * (1f - normY)
                        Offset(x, y)
                    }

                    if (points.size >= 2) {
                        val linePath = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                        val fillPath = Path().apply {
                            addPath(linePath)
                            lineTo(points.last().x, size.height)
                            lineTo(points.first().x, size.height)
                            close()
                        }
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    HeartRateRed.copy(alpha = 0.32f),
                                    HeartRateRed.copy(alpha = 0.02f)
                                )
                            )
                        )
                        drawPath(
                            path = linePath,
                            color = HeartRateRed,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    points.forEachIndexed { idx, pt ->
                        val isSelected = idx == safeIdx
                        drawCircle(
                            color = if (isSelected) Color.White else HeartRateRed,
                            radius = if (isSelected) 5.5.dp.toPx() else 3.dp.toPx(),
                            center = pt
                        )
                        if (isSelected) {
                            drawCircle(
                                color = HeartRateRed,
                                radius = 5.5.dp.toPx(),
                                center = pt,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val shortFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                    Text(
                        text = shortFmt.format(Date(recentSamples.first().timestampMs)),
                        fontSize = 10.sp,
                        color = labelColor
                    )
                    Text(text = "$yMin уд/мин", fontSize = 10.sp, color = labelColor)
                    Text(
                        text = shortFmt.format(Date(recentSamples.last().timestampMs)),
                        fontSize = 10.sp,
                        color = labelColor
                    )
                }
            }
        }
    }
}

@Composable
fun HeartRateSparkline(
    samples: List<HeartRateSample>,
    modifier: Modifier = Modifier
) {
    val recent = remember(samples) { samples.takeLast(20) }
    if (recent.size < 2) return

    val minBpm = recent.minOf { it.bpm }
    val maxBpm = recent.maxOf { it.bpm }
    val range = (maxBpm - minBpm).coerceAtLeast(10).toFloat()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val points = recent.mapIndexed { idx, sample ->
            val x = (idx.toFloat() / (recent.size - 1).toFloat()) * size.width
            val norm = ((sample.bpm - minBpm).toFloat() / range).coerceIn(0.1f, 0.9f)
            Offset(x, size.height * (1f - norm))
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
        drawPath(
            path = path,
            color = HeartRateRed.copy(alpha = 0.85f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(color = HeartRateRed, radius = 3.5.dp.toPx(), center = points.last())
    }
}

@Composable
private fun HrStatBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$value уд/мин",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = HeartRateRed
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyChartPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
