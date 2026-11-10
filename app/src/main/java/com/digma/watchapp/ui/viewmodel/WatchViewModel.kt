package com.digma.watchapp.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.model.HealthMetrics
import com.digma.watchapp.model.WatchDevice
import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.protocol.DigmaProtocolCodec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WatchViewModel : ViewModel() {

    private val app = DigmaApp.instance
    private val bleManager = app.bleManager
    private val bleScanner = app.bleScanner

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val healthMetrics: StateFlow<HealthMetrics> = bleManager.healthMetrics
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning
    val discoveredDevices: StateFlow<List<WatchDevice>> = bleScanner.discoveredDevices
    val settings: StateFlow<WatchSettings> = app.settings

    fun startScan() {
        bleScanner.startScan()
    }

    fun stopScan() {
        bleScanner.stopScan()
    }

    fun connect(device: WatchDevice) {
        bleScanner.stopScan()
        bleManager.connect(device.address)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun syncTime() {
        bleManager.syncTime()
    }

    fun queryBattery() {
        bleManager.queryBattery()
    }

    fun requestActivity() {
        bleManager.requestActivity()
    }

    fun findWatch() {
        bleManager.findWatch()
    }

    fun toggleHeartRate() {
        val currentlyMeasuring = healthMetrics.value.isMeasuringHeartRate
        bleManager.toggleHeartRateMeasurement(!currentlyMeasuring)
    }

    fun updateSettings(newSettings: WatchSettings) {
        app.updateSettings(newSettings)
    }

    fun sendTestNotification(title: String, message: String) {
        bleManager.sendNotification(
            DigmaProtocolCodec.NotificationType.APP_MESSAGE,
            title,
            message
        )
    }
}
