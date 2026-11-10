package com.digma.watchapp.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.model.HealthMetrics
import com.digma.watchapp.model.WatchDevice
import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.protocol.DigmaProtocolCodec
import com.digma.watchapp.update.ReleaseInfo
import com.digma.watchapp.update.UpdateInstaller
import com.digma.watchapp.update.UpdatePreferences
import com.digma.watchapp.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class WatchViewModel : ViewModel() {

    private val app = DigmaApp.instance
    private val bleManager = app.bleManager
    private val bleScanner = app.bleScanner
    private val updateManager = app.updateManager

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val healthMetrics: StateFlow<HealthMetrics> = bleManager.healthMetrics
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning
    val discoveredDevices: StateFlow<List<WatchDevice>> = bleScanner.discoveredDevices
    val settings: StateFlow<WatchSettings> = app.settings
    val diagnosticLogs: StateFlow<List<String>> = bleManager.diagnosticLogs
    val activeWriteUuid: String? get() = bleManager.writeCharacteristic?.uuid?.toString()
    val activeNotifyUuid: String? get() = bleManager.notifyCharacteristic?.uuid?.toString()

    val updateState: StateFlow<UpdateState> = updateManager.updateState
    val updatePreferences: UpdatePreferences = updateManager.preferences

    fun clearDiagnosticLogs() {
        bleManager.clearDiagnosticLogs()
    }

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

    // --- Update methods ---
    fun checkForUpdates(isManual: Boolean) {
        updateManager.checkForUpdates(isManual = isManual)
    }

    fun startDownload(releaseInfo: ReleaseInfo) {
        updateManager.startDownload(releaseInfo)
    }

    fun cancelDownload() {
        updateManager.cancelDownload()
    }

    fun installUpdate(apkFile: File): UpdateInstaller.InstallResult {
        return updateManager.installUpdate(apkFile)
    }

    fun canRequestPackageInstalls(): Boolean {
        return updateManager.canRequestPackageInstalls()
    }

    fun dismissUpdateDialog(ignore: Boolean, version: String? = null) {
        if (ignore && version != null) {
            updateManager.ignoreVersion(version)
        } else {
            updateManager.dismissDialog()
        }
    }

    fun getCurrentVersionName(): String {
        return updateManager.checker.getCurrentVersionName()
    }
}
