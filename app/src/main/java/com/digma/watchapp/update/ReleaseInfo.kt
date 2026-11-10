package com.digma.watchapp.update

import java.io.File

data class ReleaseInfo(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val downloadUrl: String,
    val apkFileName: String,
    val apkSize: Long,
    val htmlUrl: String
)

sealed interface UpdateState {
    object Idle : UpdateState
    data class Checking(val isManualCheck: Boolean = false) : UpdateState
    data class Available(val releaseInfo: ReleaseInfo, val isManualCheck: Boolean = false) : UpdateState
    data class NotAvailable(val currentVersion: String, val isManualCheck: Boolean = false) : UpdateState
    data class Downloading(
        val releaseInfo: ReleaseInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState
    data class ReadyToInstall(val apkFile: File, val releaseInfo: ReleaseInfo) : UpdateState
    data class Error(val message: String, val isManualCheck: Boolean = false) : UpdateState
}
