package com.digma.watchapp.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.digma.watchapp.DigmaApp
import com.digma.watchapp.ble.BleConnectionState
import com.digma.watchapp.ble.WatchProtocolMode
import com.digma.watchapp.model.DailyActivityPoint
import com.digma.watchapp.model.HealthMetrics
import com.digma.watchapp.model.HeartRateSample
import com.digma.watchapp.model.HourlyActivityPoint
import com.digma.watchapp.model.WatchDevice
import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.protocol.DigmaProtocolCodec
import com.digma.watchapp.service.NotificationCollectorService
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
    val isSyncing: StateFlow<Boolean> = bleManager.isSyncing
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning
    val discoveredDevices: StateFlow<List<WatchDevice>> = bleScanner.discoveredDevices
    val settings: StateFlow<WatchSettings> = app.settings
    val diagnosticLogs: StateFlow<List<String>> = bleManager.diagnosticLogs
    val gattSummary: StateFlow<List<String>> = bleManager.gattSummary
    val activeWriteUuid: StateFlow<String?> = bleManager.activeWriteUuid
    val activeNotifyUuid: StateFlow<String?> = bleManager.activeNotifyUuid
    val protocolMode: StateFlow<WatchProtocolMode> = bleManager.protocolMode
    val detectedProtocol: StateFlow<WatchProtocolMode> = bleManager.detectedProtocol
    val deviceFirmware: StateFlow<String?> = bleManager.deviceFirmware
    val heartRateHistory: StateFlow<List<HeartRateSample>> = bleManager.heartRateHistory
    val hourlyActivity: StateFlow<List<HourlyActivityPoint>> = bleManager.hourlyActivity
    val dailyActivity: StateFlow<List<DailyActivityPoint>> = bleManager.dailyActivity
    val savedDevice: StateFlow<WatchDevice?> = bleManager.savedDevice
    val isAutoReconnecting: StateFlow<Boolean> = bleManager.isAutoReconnecting

    val updateState: StateFlow<UpdateState> = updateManager.updateState
    val updatePreferences: UpdatePreferences = updateManager.preferences
    val isNotificationListenerConnected: StateFlow<Boolean> = NotificationCollectorService.isListenerConnected

    fun setProtocolMode(mode: WatchProtocolMode) {
        bleManager.setProtocolMode(mode)
    }

    fun swapCharacteristics(): Boolean {
        return bleManager.swapCharacteristics()
    }

    fun testFindWatchSxr(): Boolean {
        return bleManager.testFindWatchSxr()
    }

    fun testFindWatchAdor(): Boolean {
        return bleManager.testFindWatchAdor()
    }

    fun testFindWatchLefun(): Boolean {
        return bleManager.testFindWatchLefun()
    }

    fun testFindWatchPadded(): Boolean {
        return bleManager.testFindWatchPadded()
    }

    fun testHeartRateSxrMode(): Boolean {
        return bleManager.testHeartRateSxrMode()
    }

    fun testCombinedPpgSxr(mode: Int = 2): Boolean {
        return bleManager.testCombinedPpgSxr(mode)
    }

    fun stopHeartRate(): Boolean {
        return bleManager.toggleHeartRateMeasurement(false)
    }

    fun clearDiagnosticLogs() {
        bleManager.clearDiagnosticLogs()
    }

    fun clearHealthHistory() {
        bleManager.clearHealthHistory()
    }

    fun startScan() {
        bleScanner.startScan()
    }

    fun stopScan() {
        bleScanner.stopScan()
    }

    fun connect(device: WatchDevice) {
        bleScanner.stopScan()
        bleManager.connect(device.address, device.name)
    }

    fun connectSavedDevice() {
        bleScanner.stopScan()
        bleManager.connectSavedDevice()
    }

    fun forgetSavedDevice() {
        bleManager.forgetSavedDevice()
    }

    fun queryHeartRateHistory(): Boolean {
        return bleManager.queryHeartRateHistory()
    }

    fun disconnect() {
        bleManager.disconnect(byUser = true)
    }

    fun syncTime() {
        bleManager.syncTime()
    }

    fun syncAll() {
        bleManager.syncAll()
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

    fun isNotificationListenerEnabled(): Boolean = app.isNotificationListenerEnabled()

    fun requestNotificationListenerRebind() {
        if (app.isNotificationListenerEnabled()) {
            NotificationCollectorService.requestRebindIfEnabled(app)
        }
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
