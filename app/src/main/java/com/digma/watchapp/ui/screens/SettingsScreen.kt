package com.digma.watchapp.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.viewmodel.WatchViewModel

@Composable
fun SettingsScreen(viewModel: WatchViewModel) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
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
            text = "Настройки",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // --- Notification Service Access Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Доступ к уведомлениям",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Для пересылки сообщений из мессенджеров на экран часов необходимо разрешить доступ к уведомлениям в системе Android.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть системные настройки уведомлений", color = PrimaryCyan)
                }
            }
        }

        // --- Notifications Filtering Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Уведомления на часах",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                SettingSwitchRow(
                    title = "Включить пересылку уведомлений",
                    subtitle = "Вибрация и вывод текста на часы",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(notificationsEnabled = it)) }
                )

                if (settings.notificationsEnabled) {
                    SettingSwitchRow(
                        title = "Входящие звонки",
                        subtitle = "Оповещение о звонке",
                        checked = settings.forwardCalls,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(forwardCalls = it)) }
                    )

                    SettingSwitchRow(
                        title = "SMS-сообщения",
                        subtitle = "Оповещение о новых SMS",
                        checked = settings.forwardSms,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(forwardSms = it)) }
                    )

                    SettingSwitchRow(
                        title = "Мессенджеры и приложения",
                        subtitle = "Telegram, WhatsApp, VK и др.",
                        checked = settings.forwardApps,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(forwardApps = it)) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            viewModel.sendTestNotification(
                                title = "Digma D3",
                                message = "Тестовое уведомление успешно доставлено!"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = PrimaryCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Отправить тестовое уведомление", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // --- Watch Preferences Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Параметры часов",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                SettingSwitchRow(
                    title = "24-часовой формат времени",
                    subtitle = "13:00 вместо 1:00 PM",
                    checked = settings.is24HourFormat,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(is24HourFormat = it)) }
                )

                SettingSwitchRow(
                    title = "Активация экрана поворотом запястья",
                    subtitle = "Экран загорается при поднятии руки",
                    checked = settings.raiseToWake,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(raiseToWake = it)) }
                )

                SettingSwitchRow(
                    title = "Напоминание о разминке",
                    subtitle = "Вибрация при долгой неподвижности",
                    checked = settings.sedentaryReminder,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(sedentaryReminder = it)) }
                )
            }
        }

        // --- About Section ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Watch, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "О приложении",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DigmaWatchApp v0.0.1\nПоддержка часов Digma Smartline D3 (Telink TLSR8251 SoC).\nОткрытая альтернатива удаленному приложению AdorHealth.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = PrimaryCyan
            )
        )
    }
}
