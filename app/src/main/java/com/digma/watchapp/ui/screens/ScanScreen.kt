package com.digma.watchapp.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digma.watchapp.ble.BleConnectionState
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

        // --- Device List ---
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
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
