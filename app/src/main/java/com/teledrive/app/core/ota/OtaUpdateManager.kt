package com.teledrive.app.core.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.teledrive.app.core.AppLogger
import com.teledrive.app.core.Constants
import com.teledrive.app.data.preferences.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class OtaUpdateManager(
    private val context: Context,
    private val preferences: AppPreferences
) {
    private val TAG = "OtaUpdateManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val updateState: StateFlow<OtaUpdateState> = _updateState.asStateFlow()

    private var activeDownloadJob: Job? = null

    val currentVersionName: String
        get() = try {
            val pInfo = getPackageInfo()
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

    val currentVersionCode: Int
        get() = try {
            val pInfo = getPackageInfo()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }

    private fun getPackageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    /**
     * Checks for updates from the configured OTA endpoint (GitHub Releases or Direct JSON).
     */
    fun checkForUpdates(force: Boolean = false, overrideUrl: String? = null) {
        scope.launch {
            if (_updateState.value is OtaUpdateState.Checking || _updateState.value is OtaUpdateState.Downloading) {
                return@launch
            }

            if (!force) {
                val lastCheck = preferences.lastUpdateCheckTime.first()
                val twelveHoursAgo = System.currentTimeMillis() - (12 * 60 * 60 * 1000L)
                if (lastCheck > twelveHoursAgo && _updateState.value is OtaUpdateState.UpToDate) {
                    AppLogger.d(TAG, "Skipping update check, checked recently.")
                    return@launch
                }
            }

            _updateState.value = OtaUpdateState.Checking
            AppLogger.i(TAG, "Checking for updates (current: v$currentVersionName, code: $currentVersionCode)...")

            try {
                var targetUrl = overrideUrl ?: preferences.otaUpdateUrl.first()
                var request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "TeleDrive-Android/$currentVersionName")
                    .header("Accept", "application/vnd.github.v3+json, application/json, */*")
                    .build()

                var response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (response.code == 404 && targetUrl != Constants.DEFAULT_OTA_UPDATE_URL) {
                        // Fallback to default repo release URL
                        targetUrl = Constants.DEFAULT_OTA_UPDATE_URL
                        preferences.setOtaUpdateUrl(targetUrl)
                        request = Request.Builder()
                            .url(targetUrl)
                            .header("User-Agent", "TeleDrive-Android/$currentVersionName")
                            .header("Accept", "application/vnd.github.v3+json, application/json, */*")
                            .build()
                        response = httpClient.newCall(request).execute()
                    }
                }

                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        // 404 on release endpoint means no new release or draft - treat as up-to-date
                        AppLogger.i(TAG, "No release found at endpoint (404), app is up to date.")
                        _updateState.value = if (force) OtaUpdateState.UpToDate() else OtaUpdateState.Idle
                        return@launch
                    }
                    throw Exception("Server returned code ${response.code}")
                }

                val bodyStr = response.body?.string() ?: throw Exception("Empty response from update server")
                val updateInfo = parseUpdateResponse(bodyStr, targetUrl)

                preferences.setLastUpdateCheckTime(System.currentTimeMillis())

                if (updateInfo != null && isNewerVersion(updateInfo)) {
                    AppLogger.i(TAG, "New update available: v${updateInfo.versionName} (${updateInfo.versionCode})")
                    _updateState.value = OtaUpdateState.UpdateAvailable(updateInfo)
                } else {
                    AppLogger.i(TAG, "App is up to date.")
                    _updateState.value = if (force) OtaUpdateState.UpToDate() else OtaUpdateState.Idle
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update check failed: ${e.message}", e)
                if (force) {
                    _updateState.value = OtaUpdateState.Error(
                        if (e.message?.contains("404") == true) "No updates available at this time."
                        else e.message ?: "Failed to check for updates"
                    )
                } else {
                    _updateState.value = OtaUpdateState.Idle
                }
            }
        }
    }

    /**
     * Parses either a GitHub Release object or a custom TeleDrive update JSON.
     */
    private fun parseUpdateResponse(jsonString: String, sourceUrl: String): UpdateInfo? {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return null

        return if (trimmed.startsWith("[")) {
            // Array of releases (e.g. /releases) -> take first
            val array = JSONArray(trimmed)
            if (array.length() > 0) parseSingleRelease(array.getJSONObject(0)) else null
        } else {
            parseSingleRelease(JSONObject(trimmed))
        }
    }

    private fun parseSingleRelease(obj: JSONObject): UpdateInfo? {
        // 1. Check if Direct JSON format
        if (obj.has("versionCode") || obj.has("downloadUrl")) {
            val vCode = obj.optInt("versionCode", 0)
            val vName = obj.optString("versionName", "").ifBlank { "v$vCode" }
            val downloadUrl = obj.optString("downloadUrl", "")
            val changelog = obj.optString("changelog", "Bug fixes and performance improvements.")
            val fileSize = obj.optLong("fileSize", 0L)
            val isMandatory = obj.optBoolean("forceUpdate", false)

            if (downloadUrl.isNotBlank()) {
                return UpdateInfo(
                    versionCode = vCode,
                    versionName = vName.removePrefix("v"),
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    fileSize = fileSize,
                    isMandatory = isMandatory
                )
            }
        }

        // 2. Check if GitHub Releases API format
        if (obj.has("tag_name")) {
            val tag = obj.getString("tag_name").trim()
            val cleanVersion = tag.removePrefix("v").removePrefix("V")
            val changelog = obj.optString("body", "What's new in $tag:\n• Performance optimizations and stability improvements.")
            val publishedAt = obj.optString("published_at", "")

            // Find APK asset
            var apkUrl: String? = null
            var apkSize: Long = 0L

            val assets = obj.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (!apkUrl.isNullOrBlank()) {
                val parsedCode = extractVersionCode(cleanVersion)
                return UpdateInfo(
                    versionCode = parsedCode,
                    versionName = cleanVersion,
                    changelog = changelog,
                    downloadUrl = apkUrl,
                    fileSize = apkSize,
                    releaseDate = publishedAt
                )
            }
        }

        return null
    }

    private fun extractVersionCode(versionName: String): Int {
        val parts = versionName.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        return when (parts.size) {
            1 -> parts[0] * 10000
            2 -> parts[0] * 10000 + parts[1] * 100
            3 -> parts[0] * 10000 + parts[1] * 100 + parts[2]
            else -> 0
        }
    }

    private fun isNewerVersion(remote: UpdateInfo): Boolean {
        val semCmp = compareSemanticVersions(remote.versionName, currentVersionName)
        if (semCmp > 0) return true
        if (semCmp < 0) return false
        return false
    }

    private fun compareSemanticVersions(v1: String, v2: String): Int {
        val s1 = v1.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val s2 = v2.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val maxLen = maxOf(s1.size, s2.size)

        for (i in 0 until maxLen) {
            val part1 = s1.getOrElse(i) { 0 }
            val part2 = s2.getOrElse(i) { 0 }
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }

    /**
     * Downloads the APK file with real-time progress callbacks.
     */
    fun startDownload(info: UpdateInfo) {
        activeDownloadJob?.cancel()
        activeDownloadJob = scope.launch {
            try {
                val updatesDir = File(context.cacheDir, "ota_updates").apply { mkdirs() }
                val targetApk = File(updatesDir, "TeleDrive-${info.versionName}.apk")

                // Fast check if already downloaded and valid
                if (targetApk.exists() && targetApk.length() > 5 * 1024 * 1024 && isValidApk(targetApk)) {
                    if (info.fileSize <= 0L || targetApk.length() == info.fileSize) {
                        AppLogger.i(TAG, "APK already downloaded and valid: ${targetApk.absolutePath}")
                        _updateState.value = OtaUpdateState.ReadyToInstall(targetApk, info)
                        return@launch
                    }
                }

                _updateState.value = OtaUpdateState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = info.fileSize,
                    speedBps = 0L,
                    info = info
                )

                val request = Request.Builder()
                    .url(info.downloadUrl)
                    .header("User-Agent", "TeleDrive-Android/$currentVersionName")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: Download failed")
                }

                val body = response.body ?: throw Exception("Null response body")
                val contentLength = if (body.contentLength() > 0) body.contentLength() else info.fileSize

                val tempFile = File(updatesDir, "TeleDrive-${info.versionName}.apk.tmp")
                if (tempFile.exists()) tempFile.delete()

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(8192)

                var totalRead = 0L
                var lastProgressUpdate = System.currentTimeMillis()
                var lastBytesRead = 0L
                var currentSpeed = 0L

                try {
                    while (isActive) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 300) {
                            val timeDiffSec = (now - lastProgressUpdate) / 1000.0
                            if (timeDiffSec > 0) {
                                currentSpeed = ((totalRead - lastBytesRead) / timeDiffSec).toLong()
                            }
                            lastBytesRead = totalRead
                            lastProgressUpdate = now

                            val progress = if (contentLength > 0) {
                                (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            _updateState.value = OtaUpdateState.Downloading(
                                progress = progress,
                                downloadedBytes = totalRead,
                                totalBytes = contentLength,
                                speedBps = currentSpeed,
                                info = info
                            )
                        }
                    }
                } finally {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                }

                if (!isActive) {
                    tempFile.delete()
                    return@launch
                }

                // Finalize target APK file
                if (targetApk.exists()) targetApk.delete()
                tempFile.renameTo(targetApk)

                if (!isValidApk(targetApk)) {
                    targetApk.delete()
                    throw Exception("Downloaded file is corrupted or not a valid APK archive.")
                }

                AppLogger.i(TAG, "Download finished successfully: ${targetApk.length()} bytes")
                _updateState.value = OtaUpdateState.ReadyToInstall(targetApk, info)

                // Trigger package install prompt
                promptInstall(targetApk)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    AppLogger.d(TAG, "Download cancelled")
                    _updateState.value = OtaUpdateState.Idle
                } else {
                    AppLogger.e(TAG, "Download error: ${e.message}", e)
                    _updateState.value = OtaUpdateState.Error(e.message ?: "Download failed")
                }
            }
        }
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _updateState.value = OtaUpdateState.Idle
    }

    fun dismiss() {
        _updateState.value = OtaUpdateState.Idle
    }

    /**
     * Prompts the system package installer to install the downloaded APK.
     */
    fun promptInstall(apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "Update APK not found on storage.", Toast.LENGTH_SHORT).show()
                return
            }

            // Android 8.0+ Unknown App Sources Permission Check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Please allow TeleDrive to install updates, then return to install.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch package installer: ${e.message}", e)
            Toast.makeText(context, "Error starting update install: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Verifies APK ZIP magic bytes (PK\x03\x04)
     */
    private fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                val b4 = raf.read()
                b1 == 0x50 && b2 == 0x4B && b3 == 0x03 && b4 == 0x04
            }
        } catch (e: Exception) {
            false
        }
    }
}
