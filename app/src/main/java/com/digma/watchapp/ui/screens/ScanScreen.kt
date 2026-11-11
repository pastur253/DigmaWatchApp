package com.digma.watchapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.ble.WatchProtocolMode
import com.digma.watchapp.model.WatchDevice
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.theme.StatusConnected
import com.digma.watchapp.ui.theme.StatusConnecting
import com.digma.watchapp.ui.theme.StatusDisconnected
import com.digma.watchapp.ui.viewmodel.WatchViewModel

@Composable
fun ScanScreen(viewModel: WatchViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // --- Header and Scan Toggle ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Поиск устройств",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Убедитесь, что часы D3 включены",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    if (isScanning) viewModel.stopScan() else viewModel.startScan()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) MaterialTheme.colorScheme.error else PrimaryCyan
                )
            ) {
                if (isScanning) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Стоп", color = Color.White)
                } else {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Поиск", color = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Active Connection Banner if connected ---
        if (connectionState is BleConnectionState.Connected) {
            val connectedDevice = connectionState as BleConnectionState.Connected
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(StatusConnected)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = connectedDevice.deviceName,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = connectedDevice.deviceAddress,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Отключить", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Device List (Flexible Area) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isScanning) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PrimaryCyan)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Поиск часов в радиусе Bluetooth...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = StatusDisconnected,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Нажмите «Поиск», чтобы найти часы",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceItemCard(
                            device = device,
                            isConnecting = connectionState is BleConnectionState.Connecting &&
                                    (connectionState as BleConnectionState.Connecting).deviceAddress == device.address,
                            isConnected = connectionState is BleConnectionState.Connected &&
                                    (connectionState as BleConnectionState.Connected).deviceAddress == device.address,
                            onConnectClick = { viewModel.connect(device) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- BLE Diagnostic Console ---
        DiagnosticSection(viewModel = viewModel)
    }
}

@Composable
fun DiagnosticSection(
    viewModel: WatchViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.diagnosticLogs.collectAsState()
    val gattSummary by viewModel.gattSummary.collectAsState()
    val writeUuid by viewModel.activeWriteUuid.collectAsState()
    val notifyUuid by viewModel.activeNotifyUuid.collectAsState()
    val protocolMode by viewModel.protocolMode.collectAsState()
    val detectedProtocol by viewModel.detectedProtocol.collectAsState()
    val deviceFirmware by viewModel.deviceFirmware.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var isExpanded by remember { mutableStateOf(false) }
    var showGattTab by remember { mutableStateOf(false) }

    val activeProtocol = if (protocolMode == WatchProtocolMode.AUTO) detectedProtocol else protocolMode

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header clickable row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = PrimaryCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BLE Диагностика (${logs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (writeUuid != null) {
                        Text(
                            text = "${activeProtocol.label}",
                            fontSize = 11.sp,
                            color = StatusConnected,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

                // Active UUIDs, firmware & SWAP button
                if (writeUuid != null || notifyUuid != null || !deviceFirmware.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TX (Write):  ${writeUuid ?: "не найдена"}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "RX (Notify): ${notifyUuid ?: "не найдена"}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!deviceFirmware.isNullOrBlank()) {
                                    Text(
                                        text = "FW/MAC:      $deviceFirmware",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = StatusConnected
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.swapCharacteristics() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("TX ⇄ RX", fontSize = 10.sp, color = PrimaryCyan)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Protocol mode selector (Авто / SXR 56ff / 0xAB)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Протокол:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WatchProtocolMode.entries.forEach { mode ->
                        val isSelected = protocolMode == mode
                        if (isSelected) {
                            Button(
                                onClick = { viewModel.setProtocolMode(mode) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                            ) {
                                val caption = if (mode == WatchProtocolMode.AUTO && writeUuid != null) {
                                    "Авто (${detectedProtocol.label})"
                                } else {
                                    mode.label
                                }
                                Text(caption, fontSize = 10.sp, color = Color.Black, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setProtocolMode(mode) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    mode.label,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Command buttons row 1: Primary actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { viewModel.syncTime() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Text("Время", fontSize = 11.sp, color = Color.Black)
                    }

                    Button(
                        onClick = { viewModel.queryBattery() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Text("АКБ", fontSize = 11.sp, color = Color.Black)
                    }

                    Button(
                        onClick = { viewModel.requestActivity() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Text("Шаги", fontSize = 11.sp, color = Color.Black)
                    }

                    OutlinedButton(
                        onClick = {
                            val header = buildList {
                                add("=== DIGMA BLE DIAGNOSTICS ===")
                                add("Protocol: mode=${protocolMode.label}, detected=${detectedProtocol.label}, active=${activeProtocol.label}")
                                add("TX=${writeUuid ?: "none"} | RX=${notifyUuid ?: "none"}")
                                if (!deviceFirmware.isNullOrBlank()) add("Firmware/MAC: $deviceFirmware")
                                if (gattSummary.isNotEmpty()) {
                                    add("--- GATT SUMMARY ---")
                                    addAll(gattSummary)
                                }
                                add("--- LOGS (${logs.size}) ---")
                            }
                            clipboardManager.setText(AnnotatedString((header + logs).joinToString("\n")))
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Копировать",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.clearDiagnosticLogs() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Очистить",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Command buttons row 2: Targeted vibration protocol tests
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.testFindWatchSxr() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("SXR 0x04", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondary)
                    }

                    Button(
                        onClick = { viewModel.testFindWatchAdor() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Ador", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondary)
                    }

                    Button(
                        onClick = { viewModel.testFindWatchLefun() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Lefun", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondary)
                    }

                    Button(
                        onClick = { viewModel.testFindWatchPadded() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("20B AB", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Switch between live TX/RX log and pinned GATT service map
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { showGattTab = false },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "Логи (${logs.size})",
                            fontSize = 10.sp,
                            color = if (!showGattTab) PrimaryCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showGattTab = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "GATT карта (${gattSummary.size})",
                            fontSize = 10.sp,
                            color = if (showGattTab) PrimaryCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable log / GATT console
                val visibleLines = if (showGattTab) gattSummary else logs
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F141C))
                        .padding(8.dp)
                ) {
                    if (visibleLines.isEmpty()) {
                        Text(
                            text = if (showGattTab) {
                                "GATT-карта появится после подключения к часам."
                            } else {
                                "Логи пока пусты. Подключитесь к часам."
                            },
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visibleLines) { entry ->
                                val textColor = when {
                                    entry.contains("TX") -> Color(0xFF80DEEA)
                                    entry.contains("RX") -> Color(0xFFA5D6A7)
                                    entry.contains("Сбой") || entry.contains("Ошибка") || entry.contains("ВНИМАНИЕ") -> Color(0xFFEF9A9A)
                                    else -> Color(0xFFCFD8DC)
                                }
                                Text(
                                    text = entry,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = textColor,
                                    lineHeight = 13.sp
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
fun DeviceItemCard(
    device: WatchDevice,
    isConnecting: Boolean,
    isConnected: Boolean,
    onConnectClick: () -> Unit
) {
    val isDigmaTarget = device.name.contains("Smartline", ignoreCase = true) ||
            device.name.contains("Digma", ignoreCase = true) ||
            device.name.contains("D3", ignoreCase = true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDigmaTarget) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        fontWeight = if (isDigmaTarget) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = if (isDigmaTarget) PrimaryCyan else MaterialTheme.colorScheme.onSurface
                    )
                    if (isDigmaTarget) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "D3",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(PrimaryCyan)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${device.address}  •  ${device.rssi} dBm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onConnectClick,
                enabled = !isConnected && !isConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) StatusConnected else PrimaryCyan
                )
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                } else {
                    Text(
                        text = if (isConnected) "Подключено" else "Коннект",
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
