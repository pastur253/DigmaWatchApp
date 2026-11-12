package com.digma.watchapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.ui.components.HeartRateSparkline
import com.digma.watchapp.ui.theme.HeartRateRed
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StatusConnected
import com.digma.watchapp.ui.theme.StatusConnecting
import com.digma.watchapp.ui.theme.StatusDisconnected
import com.digma.watchapp.ui.theme.StepsOrange
import com.digma.watchapp.ui.viewmodel.WatchViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: WatchViewModel,
    onNavigateToScan: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val metrics by viewModel.healthMetrics.collectAsState()
    val heartRateHistory by viewModel.heartRateHistory.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    val isConnected = connectionState is BleConnectionState.Connected
    val scrollState = rememberScrollState()

    PullToRefreshBox(
        isRefreshing = isSyncing,
        onRefresh = { viewModel.syncAll() },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // --- Status Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionState) {
                                        is BleConnectionState.Connected -> StatusConnected
                                        is BleConnectionState.Connecting -> StatusConnecting
                                        else -> StatusDisconnected
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (connectionState) {
                                is BleConnectionState.Connected -> (connectionState as BleConnectionState.Connected).deviceName
                                is BleConnectionState.Connecting -> "Подключение..."
                                else -> "Часы не подключены"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (isConnected && metrics.batteryPercent > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BatteryFull,
                                contentDescription = "Battery",
                                tint = StatusConnected,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${metrics.batteryPercent}%",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (!isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToScan,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Icon(Icons.Default.Watch, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Найти и подключить часы", color = Color.Black)
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    val lastSyncStr = if (metrics.lastSyncTimestamp > 0) {
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        "Последняя синхронизация: ${sdf.format(Date(metrics.lastSyncTimestamp))}"
                    } else "Ожидание первой синхронизации"
                    Text(
                        text = lastSyncStr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Activity Progress Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Активность сегодня",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                val progress = (metrics.steps.toFloat() / metrics.stepGoal.toFloat()).coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(targetValue = progress, label = "stepProgress")

                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 12.dp
                    )
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = StepsOrange,
                        strokeWidth = 12.dp
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.DirectionsWalk,
                            contentDescription = null,
                            tint = StepsOrange,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "${metrics.steps}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "из ${metrics.stepGoal} шагов",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = StepsOrange, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "${metrics.caloriesKcal} ккал", fontWeight = FontWeight.Bold)
                        }
                        Text(text = "Калории", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val distanceKm = String.format(Locale.US, "%.2f", metrics.distanceMeters / 1000.0)
                        Text(text = "$distanceKm км", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (metrics.distanceMeters in 1..999) {
                                "Дистанция (${metrics.distanceMeters} м)"
                            } else {
                                "Дистанция"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- Heart Rate & SpO2 Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = HeartRateRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Пульс и Кислород (SpO2)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (isConnected) {
                        Button(
                            onClick = { viewModel.toggleHeartRate() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (metrics.isMeasuringHeartRate) HeartRateRed else PrimaryCyan
                            )
                        ) {
                            Text(
                                text = if (metrics.isMeasuringHeartRate) "Стоп" else "Замер",
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (metrics.heartRateBpm > 0) "${metrics.heartRateBpm}" else "--",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = HeartRateRed
                        )
                        Text(text = "Пульс (уд/мин)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (metrics.spO2Percent > 0) "${metrics.spO2Percent}%" else "--",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryCyan
                        )
                        Text(text = "Кислород SpO2", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (heartRateHistory.size >= 2) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HeartRateSparkline(samples = heartRateHistory)
                }
            }
        }

        // --- Quick Actions Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Быстрые действия",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Default.Sync,
                        title = "Время",
                        subtitle = "Синхр.",
                        enabled = isConnected,
                        onClick = { viewModel.syncTime() },
                        modifier = Modifier.weight(1f)
                    )

                    QuickActionTile(
                        icon = Icons.Default.Vibration,
                        title = "Поиск D3",
                        subtitle = "Вибро",
                        enabled = isConnected,
                        onClick = { viewModel.findWatch() },
                        modifier = Modifier.weight(1f)
                    )

                    QuickActionTile(
                        icon = Icons.Default.Refresh,
                        title = "Обновить",
                        subtitle = "Все данные",
                        enabled = isConnected,
                        onClick = { viewModel.syncAll() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
}

@Composable
fun QuickActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = if (enabled) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) PrimaryCyan.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (enabled) PrimaryCyan else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
