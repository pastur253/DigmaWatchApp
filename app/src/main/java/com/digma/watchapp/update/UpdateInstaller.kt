package com.digma.watchapp.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class UpdateInstaller(private val context: Context) {

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, requestCode)
        }
    }

    fun installApk(file: File): InstallResult {
        if (!file.exists()) {
            return InstallResult.Error("Файл установки не найден: ${file.name}")
        }

        if (!canRequestPackageInstalls()) {
            return InstallResult.PermissionRequired
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(installIntent)
            InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent", e)
            InstallResult.Error("Не удалось запустить установку: ${e.localizedMessage}")
        }
    }

    sealed interface InstallResult {
        object Success : InstallResult
        object PermissionRequired : InstallResult
        data class Error(val message: String) : InstallResult
    }

    companion object {
        private const val TAG = "UpdateInstaller"
    }
}
