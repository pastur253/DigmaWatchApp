package com.digma.watchapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.digma.watchapp.model.WatchDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<WatchDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WatchDevice>> = _discoveredDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: "Неизвестное устройство"
            val address = device.address ?: return
            val rssi = result.rssi

            val currentList = _discoveredDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address.equals(address, ignoreCase = true) }

            val updatedItem = WatchDevice(
                name = name,
                address = address,
                rssi = rssi,
                isConnected = false
            )

            if (existingIndex >= 0) {
                currentList[existingIndex] = updatedItem
            } else {
                currentList.add(updatedItem)
            }

            // Sort with devices matching target keywords first, then by RSSI
            _discoveredDevices.value = currentList.sortedWith(
                compareByDescending<WatchDevice> { isTargetDevice(it.name) }
                    .thenByDescending { it.rssi }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (_isScanning.value) return

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (!_isScanning.value) return

        _isScanning.value = false
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Permission or adapter state issue
        }
    }

    private fun isTargetDevice(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("smartline") || lower.contains("digma") ||
                lower.contains("d3") || lower.contains("ador") || lower.contains("watch")
    }
}
