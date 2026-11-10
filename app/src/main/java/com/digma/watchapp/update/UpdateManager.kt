package com.digma.watchapp.update

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class UpdateManager(private val context: Context) {

    val preferences = UpdatePreferences(context)
    val checker = UpdateChecker(context)
    val downloader = UpdateDownloader(context)
    val installer = UpdateInstaller(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdates(isManual: Boolean = false) {
        if (!isManual && !preferences.autoCheckEnabled) {
            return
        }

        scope.launch {
            _updateState.value = UpdateState.Checking(isManualCheck = isManual)

            when (val result = checker.checkForUpdate(forceCheck = isManual)) {
                is UpdateChecker.CheckResult.NewVersionAvailable -> {
                    _updateState.value = UpdateState.Available(
                        releaseInfo = result.releaseInfo,
                        isManualCheck = isManual
                    )
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    _updateState.value = UpdateState.NotAvailable(
                        currentVersion = result.currentVersion,
                        isManualCheck = isManual
                    )
                }
                is UpdateChecker.CheckResult.Ignored -> {
                    _updateState.value = UpdateState.Idle
                }
                is UpdateChecker.CheckResult.NoReleaseFound -> {
                    if (isManual) {
                        _updateState.value = UpdateState.Error(result.message, isManualCheck = true)
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
                is UpdateChecker.CheckResult.NoApkAsset -> {
                    if (isManual) {
                        _updateState.value = UpdateState.Error(result.message, isManualCheck = true)
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
                is UpdateChecker.CheckResult.Error -> {
                    if (isManual) {
                        _updateState.value = UpdateState.Error(result.message, isManualCheck = true)
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            }
        }
    }

    fun startDownload(releaseInfo: ReleaseInfo) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _updateState.value = UpdateState.Downloading(
                releaseInfo = releaseInfo,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = releaseInfo.apkSize
            )

            when (val result = downloader.downloadApk(releaseInfo) { progress ->
                _updateState.value = UpdateState.Downloading(
                    releaseInfo = releaseInfo,
                    progress = progress.progressFraction,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes
                )
            }) {
                is UpdateDownloader.DownloadResult.Success -> {
                    _updateState.value = UpdateState.ReadyToInstall(
                        apkFile = result.apkFile,
                        releaseInfo = releaseInfo
                    )
                }
                is UpdateDownloader.DownloadResult.Error -> {
                    _updateState.value = UpdateState.Error(
                        message = result.message,
                        isManualCheck = false
                    )
                }
                is UpdateDownloader.DownloadResult.Cancelled -> {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateState.Idle
    }

    fun installUpdate(apkFile: File): UpdateInstaller.InstallResult {
        val result = installer.installApk(apkFile)
        if (result is UpdateInstaller.InstallResult.Error) {
            _updateState.value = UpdateState.Error(result.message, isManualCheck = false)
        }
        return result
    }

    fun requestInstallPermission(activity: Activity, requestCode: Int = 1001) {
        installer.requestInstallPermission(activity, requestCode)
    }

    fun canRequestPackageInstalls(): Boolean {
        return installer.canRequestPackageInstalls()
    }

    fun ignoreVersion(version: String) {
        preferences.ignoredVersion = version
        _updateState.value = UpdateState.Idle
    }

    fun dismissDialog() {
        _updateState.value = UpdateState.Idle
    }
}
