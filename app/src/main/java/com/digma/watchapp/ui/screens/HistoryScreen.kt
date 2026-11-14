package com.digma.watchapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.data.HealthHistoryStore
import com.digma.watchapp.model.ActivityPeriodSummary
import com.digma.watchapp.model.SummaryPeriod
import com.digma.watchapp.ui.components.ActivityChartCard
import com.digma.watchapp.ui.components.HeartRateChartCard
import com.digma.watchapp.ui.theme.HeartRateRed
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StepsOrange
import com.digma.watchapp.ui.viewmodel.WatchViewModel
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: WatchViewModel) {
    val metrics by viewModel.healthMetrics.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val heartRateHistory by viewModel.heartRateHistory.collectAsState()
    val hourlyActivity by viewModel.hourlyActivity.collectAsState()
    val dailyActivity by viewModel.dailyActivity.collectAsState()
    val allDailyActivity by viewModel.allDailyActivity.collectAsState()
    val scrollState = rememberScrollState()
    val isConnected = connectionState is BleConnectionState.Connected

    var selectedPeriod by remember { mutableStateOf(SummaryPeriod.WEEK) }
    val activitySummary = remember(allDailyActivity, selectedPeriod, metrics.stepGoal) {
        HealthHistoryStore.calculateSummary(
            points = allDailyActivity,
            periodDays = selectedPeriod.days,
            stepGoal = metrics.stepGoal
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "История и графики",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConnected) {
                    IconButton(onClick = { viewModel.syncAll() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Обновить данные",
                            tint = PrimaryCyan
                        )
                    }
                }
                if (heartRateHistory.isNotEmpty() || allDailyActivity.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearHealthHistory() }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Очистить историю",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- Period Activity Summary (Week / Month) ---
        ActivitySummaryCard(
            summary = activitySummary,
            selectedPeriod = selectedPeriod,
            onSelectPeriod = { selectedPeriod = it },
            stepGoal = metrics.stepGoal
        )

        ActivityChartCard(
            hourlyPoints = hourlyActivity,
            dailyPoints = dailyActivity,
            stepGoal = metrics.stepGoal
        )

        HeartRateChartCard(
            samples = heartRateHistory
        )

        // Current Session Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Сводка за текущую сессию",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HistoryMetricRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    tint = StepsOrange,
                    title = "Пройдено шагов",
                    value = "${metrics.steps} шагов"
                )

                HistoryMetricRow(
                    icon = Icons.Default.LocalFireDepartment,
                    tint = StepsOrange,
                    title = "Сожжено калорий",
                    value = "${metrics.caloriesKcal} ккал"
                )

                HistoryMetricRow(
                    icon = Icons.Default.Favorite,
                    tint = HeartRateRed,
                    title = "Последний замер пульса",
                    value = if (metrics.heartRateBpm > 0) "${metrics.heartRateBpm} уд/мин" else "Нет данных"
                )

                HistoryMetricRow(
                    icon = Icons.Default.Bedtime,
                    tint = PrimaryCyan,
                    title = "Мониторинг сна",
                    value = "Фазы: глубокий / легкий сон"
                )
            }
        }

        // Information Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "💡 Совет по синхронизации",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = PrimaryCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Часы Digma Smartline D3 сохраняют суточную активность во внутренней памяти TLSR8251. При каждом подключении или нажатии «Время/Активность» приложение запрашивает свежие счетчики и обновляет статистику.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ActivitySummaryCard(
    summary: ActivityPeriodSummary,
    selectedPeriod: SummaryPeriod,
    onSelectPeriod: (SummaryPeriod) -> Unit,
    stepGoal: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сводка активности",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryPeriod.entries.forEach { period ->
                        val isSelected = period == selectedPeriod
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectPeriod(period) },
                            label = { Text(period.title, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryCyan.copy(alpha = 0.2f),
                                selectedLabelColor = PrimaryCyan
                            )
                        )
                    }
                }
            }

            if (summary.totalSteps == 0 && summary.activeDaysCount == 0) {
                val periodName = if (selectedPeriod == SummaryPeriod.WEEK) "7 дней" else "30 дней"
                Text(
                    text = "Нет данных об активности за выбранный период ($periodName). Статистика сформируется автоматически при синхронизации с часов.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val distKm = summary.totalDistanceMeters / 1000.0
                val avgDistKm = summary.averageDistanceMetersPerDay / 1000.0
                val goalPercent = if (summary.totalDaysCount > 0) {
                    ((summary.goalReachedDaysCount.toFloat() / summary.totalDaysCount) * 100f).toInt()
                } else 0

                // 2x2 Grid of Main Metrics with equal-height rows
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        iconTint = StepsOrange,
                        label = "Всего шагов",
                        mainValue = "%,d ш.".format(Locale.US, summary.totalSteps).replace(',', ' '),
                        subValue = "В ср: %,d/день".format(Locale.US, summary.averageStepsPerDay).replace(',', ' ')
                    )
                    SummaryStatTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Default.LocalFireDepartment,
                        iconTint = StepsOrange,
                        label = "Калории",
                        mainValue = "${summary.totalCaloriesKcal} ккал",
                        subValue = "В ср: ${summary.averageCaloriesPerDay} ккал/день"
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        iconTint = PrimaryCyan,
                        label = "Дистанция",
                        mainValue = "%.1f км".format(Locale.US, distKm),
                        subValue = "В ср: %.1f км/день".format(Locale.US, avgDistKm)
                    )
                    SummaryStatTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        iconTint = PrimaryCyan,
                        label = "Лучший день",
                        mainValue = if (summary.bestDaySteps > 0) "%,d ш.".format(Locale.US, summary.bestDaySteps).replace(',', ' ') else "—",
                        subValue = if (summary.bestDayDate.isNotEmpty()) "Дата: ${summary.bestDayDate}" else "Нет данных"
                    )
                }

                // Goal Achievement Progress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = StepsOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Достижение цели ($stepGoal ш.)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${summary.goalReachedDaysCount} из ${summary.totalDaysCount} дн. ($goalPercent%)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryCyan
                        )
                    }

                    LinearProgressIndicator(
                        progress = { (goalPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PrimaryCyan,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        strokeCap = StrokeCap.Round
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Дней с активностью: ${summary.activeDaysCount} из ${summary.totalDaysCount}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryStatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    mainValue: String,
    subValue: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mainValue,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subValue,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun HistoryMetricRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
