package com.digma.watchapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ui.theme.HeartRateRed
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StepsOrange
import com.digma.watchapp.ui.viewmodel.WatchViewModel

@Composable
fun HistoryScreen(viewModel: WatchViewModel) {
    val metrics by viewModel.healthMetrics.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "История и статистика",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Summary Card
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
                    icon = Icons.Default.DirectionsWalk,
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
                    text = "Часы Digma Smartline D3 сохраняют суточную активность во внутренней памяти TLSR8251. При каждом подключении или нажатии «Время/Активность» приложение запрашивает свежие счетчики.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
