package com.digma.watchapp.update

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloader(private val context: Context) {

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float
    )

    suspend fun downloadApk(
        releaseInfo: ReleaseInfo,
        onProgress: (Progress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "updates").apply { mkdirs() }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val safeFileName = releaseInfo.apkFileName.ifBlank {
            "DigmaWatchApp-${releaseInfo.versionName}.apk"
        }
        val targetFile = File(targetDir, safeFileName)
        val tempFile = File(targetDir, "$safeFileName.download")

        if (targetFile.exists() && releaseInfo.apkSize > 0 && targetFile.length() == releaseInfo.apkSize) {
            Log.d(TAG, "File already downloaded: ${targetFile.absolutePath}")
            onProgress(Progress(releaseInfo.apkSize, releaseInfo.apkSize, 1.0f))
            return@withContext DownloadResult.Success(targetFile)
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = releaseInfo.downloadUrl
            var redirectsCount = 0
            val maxRedirects = 10

            while (redirectsCount < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "DigmaWatchApp-Android")
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    currentUrl = newUrl
                    redirectsCount++
                } else if (status == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    return@withContext DownloadResult.Error("HTTP ошибка при загрузке: $status")
                }
            }

            val totalBytes = connection?.contentLengthLong ?: releaseInfo.apkSize
            inputStream = connection?.inputStream ?: return@withContext DownloadResult.Error("Пустой ответ от сервера")

            if (tempFile.exists()) {
                tempFile.delete()
            }

            outputStream = FileOutputStream(tempFile)
            val buffer = ByteArray(8192)
            var downloadedBytes = 0L
            var lastReportTime = 0L

            while (isActive) {
                val read = inputStream.read(buffer)
                if (read == -1) break

                outputStream.write(buffer, 0, read)
                downloadedBytes += read

                val now = System.currentTimeMillis()
                if (now - lastReportTime > 100 || downloadedBytes == totalBytes) {
                    val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                    onProgress(Progress(downloadedBytes, totalBytes, fraction.coerceIn(0f, 1f)))
                    lastReportTime = now
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null
            inputStream.close()
            inputStream = null

            if (!isActive) {
                tempFile.delete()
                return@withContext DownloadResult.Cancelled
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (!tempFile.renameTo(targetFile)) {
                return@withContext DownloadResult.Error("Не удалось сохранить APK-файл")
            }

            DownloadResult.Success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            tempFile.delete()
            DownloadResult.Error(e.localizedMessage ?: "Ошибка при загрузке обновления")
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    sealed interface DownloadResult {
        data class Success(val apkFile: File) : DownloadResult
        data class Error(val message: String) : DownloadResult
        object Cancelled : DownloadResult
    }

    companion object {
        private const val TAG = "UpdateDownloader"
    }
}
