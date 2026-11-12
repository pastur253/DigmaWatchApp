package com.digma.watchapp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.digma.watchapp.service.NotificationRelayPolicy
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StatusConnected
import com.digma.watchapp.ui.theme.StatusConnecting
import com.digma.watchapp.ui.viewmodel.WatchViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(viewModel: WatchViewModel) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scrollState = rememberScrollState()

    val updatePrefs = viewModel.updatePreferences
    var autoCheckUpdates by remember { mutableStateOf(updatePrefs.autoCheckEnabled) }
    var notificationAccessGranted by remember { mutableStateOf(viewModel.isNotificationListenerEnabled()) }
    val notificationListenerConnected by viewModel.isNotificationListenerConnected.collectAsState()
    var customPackageInput by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted = viewModel.isNotificationListenerEnabled()
                if (notificationAccessGranted && !viewModel.isNotificationListenerConnected.value) {
                    viewModel.requestNotificationListenerRebind()
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

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
                val serviceReady = notificationAccessGranted && notificationListenerConnected
                val statusColor = when {
                    serviceReady -> StatusConnected
                    notificationAccessGranted -> StatusConnecting
                    else -> StatusConnecting
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            serviceReady -> "Служба уведомлений активна"
                            notificationAccessGranted -> "Доступ выдан, но служба не привязана"
                            else -> "Требуется доступ к уведомлениям"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        serviceReady ->
                            "Android разрешил чтение уведомлений и подключил службу. Сообщения будут автоматически пересылаться на часы."
                        notificationAccessGranted ->
                            "Галочка в настройках включена, но Android ещё не подключил службу (часто бывает после обновления APK). Нажмите «Переподключить службу» или выключите и снова включите доступ в настройках."
                        else ->
                            "Для пересылки сообщений из exteraGram, Telegram и других мессенджеров разрешите доступ к уведомлениям в системе Android. На Android 13+ может потребоваться разрешить «Ограниченные настройки» в «О приложении»."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (notificationAccessGranted && !notificationListenerConnected) {
                    Button(
                        onClick = { viewModel.requestNotificationListenerRebind() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Переподключить службу", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
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
                        subtitle = "Пересылка сообщений из приложений",
                        checked = settings.forwardApps,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(forwardApps = it)) }
                    )

                    if (settings.forwardApps) {
                        SettingSwitchRow(
                            title = "Все приложения и мессенджеры",
                            subtitle = "exteraGram, Telegram, WhatsApp и любые другие",
                            checked = settings.forwardAllApps,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(forwardAllApps = it)) }
                        )

                        if (!settings.forwardAllApps) {
                            Text(
                                text = "Белый список приложений:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            NotificationRelayPolicy.POPULAR_MESSENGERS.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    pair.forEach { preset ->
                                        val selected = settings.allowedPackages.contains(preset.packageName)
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                val updated = if (selected) {
                                                    settings.allowedPackages - preset.packageName
                                                } else {
                                                    settings.allowedPackages + preset.packageName
                                                }
                                                viewModel.updateSettings(settings.copy(allowedPackages = updated))
                                            },
                                            label = {
                                                Text(
                                                    text = preset.displayName,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            leadingIcon = if (selected) {
                                                {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = PrimaryCyan.copy(alpha = 0.2f),
                                                selectedLabelColor = PrimaryCyan,
                                                selectedLeadingIconColor = PrimaryCyan
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            val presetPackages = NotificationRelayPolicy.POPULAR_MESSENGERS
                                .map { it.packageName }
                                .toSet()
                            val customPackages = settings.allowedPackages
                                .filter { it !in presetPackages }
                                .sorted()

                            customPackages.forEach { pkg ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = pkg,
                                        fontSize = 12.sp,
                                        color = PrimaryCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            viewModel.updateSettings(
                                                settings.copy(allowedPackages = settings.allowedPackages - pkg)
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Удалить $pkg",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customPackageInput,
                                    onValueChange = { customPackageInput = it },
                                    placeholder = { Text("com.example.messenger", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val pkg = customPackageInput.trim()
                                        if (pkg.contains(".")) {
                                            viewModel.updateSettings(
                                                settings.copy(allowedPackages = settings.allowedPackages + pkg)
                                            )
                                            customPackageInput = ""
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Добавить пакет", tint = PrimaryCyan)
                                }
                            }
                        }
                    }

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

        // --- In-App Updates Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Обновления приложения",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                SettingSwitchRow(
                    title = "Автопроверка обновлений",
                    subtitle = "Проверять новые версии при запуске",
                    checked = autoCheckUpdates,
                    onCheckedChange = {
                        autoCheckUpdates = it
                        updatePrefs.autoCheckEnabled = it
                    }
                )

                val lastCheckText = if (updatePrefs.lastCheckTime > 0) {
                    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    "Последняя проверка: ${sdf.format(Date(updatePrefs.lastCheckTime))}"
                } else {
                    "Проверка еще не выполнялась"
                }

                Text(
                    text = lastCheckText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.checkForUpdates(isManual = true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Проверить", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val url = "https://github.com/${updatePrefs.repositoryOwner}/${updatePrefs.repositoryName}/releases"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = PrimaryCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GitHub", color = PrimaryCyan)
                    }
                }
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
                    text = "DigmaWatchApp v${viewModel.getCurrentVersionName()}\nПоддержка часов Digma Smartline D3 (Telink TLSR8251 SoC).\nОткрытая альтернатива удаленному приложению AdorHealth.",
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
