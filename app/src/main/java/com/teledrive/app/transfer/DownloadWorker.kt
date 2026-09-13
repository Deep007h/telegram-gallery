package com.teledrive.app.transfer

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.AppLogger
import com.teledrive.app.core.FileUtils
import com.teledrive.app.telegram.TelegramBotApiEngine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import java.io.File

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"
    }

    override suspend fun doWork(): Result {
        val transferId = inputData.getLong("transfer_id", -1L)
        if (transferId == -1L) return Result.failure()

        val app = context.applicationContext as TeleDriveApplication
        val transferDao = app.database.transferDao()
        val fileDao = app.database.fileDao()
        val tdLibManager = app.tdLibManager

        val transferEntity = transferDao.getById(transferId) ?: return Result.failure()

        transferDao.updateStatus(transferId, "IN_PROGRESS", null, System.currentTimeMillis())
        val notificationManager = TransferNotificationManager()
        val fileName = transferEntity.fileName
        val downloadDir = FileUtils.getDownloadDir(context)
        val destFile = File(downloadDir, fileName)

        try {
            // Check if destination file already exists and is complete
            if (destFile.exists() && destFile.length() > 0L &&
                (transferEntity.fileSize <= 0L || destFile.length() == transferEntity.fileSize)
            ) {
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                transferDao.updateProgress(transferId, destFile.length(), System.currentTimeMillis())
                transferDao.updateStatus(transferId, "COMPLETED", null, System.currentTimeMillis())
                val completedNotification = notificationManager.createCompletedNotification(context, fileName, false)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(transferId.hashCode(), completedNotification)
                return Result.success()
            }

            val notification = notificationManager.createNotification(
                context = context,
                fileName = fileName,
                progress = 0,
                isUpload = false
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setForeground(ForegroundInfo(transferId.toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                } else {
                    setForeground(ForegroundInfo(transferId.toInt(), notification))
                }
            } catch (ignored: Exception) {}

            val msgId = transferEntity.telegramMessageId ?: 0L
            val chatId = when {
                transferEntity.telegramChatId != 0L -> transferEntity.telegramChatId
                app.preferences.getCachedStorageChatId() != 0L -> app.preferences.getCachedStorageChatId()
                else -> tdLibManager.cachedSavedMessagesChatId
            }

            var tdFileId = 0
            val fileEntity = if (chatId != 0L && msgId != 0L) {
                fileDao.getByMessageId(chatId, msgId)
            } else {
                fileDao.getByPath(transferEntity.virtualPath)
            }

            if (fileEntity != null && fileEntity.telegramFileId != 0) {
                tdFileId = fileEntity.telegramFileId
            }

            // Rehydrate fresh TDLib file ID if missing, 0, or invalid
            if (tdFileId == 0 && chatId != 0L && msgId != 0L) {
                try {
                    val freshInfo = tdLibManager.getMessageInfo(chatId, msgId)
                    if (freshInfo != null && freshInfo.documentFileId != 0) {
                        tdFileId = freshInfo.documentFileId
                        if (fileEntity != null) {
                            fileDao.updateFileIds(fileEntity.fileId, freshInfo.documentFileId, freshInfo.thumbnailFileId)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Rehydrate file ID failed for msgId=$msgId: ${e.message}")
                }
            }

            var completedLocalPath: String? = null

            // Layer 1: Check TDLib cache directly
            if (tdFileId != 0) {
                try {
                    val tdFile = tdLibManager.getFile(tdFileId)
                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                        completedLocalPath = tdFile.local.path
                    }
                } catch (e: Exception) {
                    // Stale file ID across sessions: attempt one more live rehydrate
                    if (chatId != 0L && msgId != 0L) {
                        try {
                            val fresh = tdLibManager.getMessageInfo(chatId, msgId)
                            if (fresh != null && fresh.documentFileId != 0 && fresh.documentFileId != tdFileId) {
                                tdFileId = fresh.documentFileId
                                val rechecked = tdLibManager.getFile(tdFileId)
                                if (rechecked.local.isDownloadingCompleted && rechecked.local.path.isNotEmpty() && File(rechecked.local.path).exists()) {
                                    completedLocalPath = rechecked.local.path
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // Layer 2: Download via TDLib progressive engine
            if (completedLocalPath == null && tdFileId != 0) {
                try {
                    tdLibManager.startDownload(tdFileId, 32)
                } catch (ignored: Exception) {}

                var lastDbWrite = 0L
                var lastNotif = 0L
                val notifId = transferId.hashCode()

                try {
                    withTimeout(600_000L) { // 10-minute timeout for large media files
                        tdLibManager.fileUpdates
                            .filter { it.fileId == tdFileId }
                            .collect { update ->
                                val now = System.currentTimeMillis()
                                if (now - lastDbWrite >= 400L || update.isDownloadingCompleted) {
                                    lastDbWrite = now
                                    try {
                                        transferDao.updateProgress(transferId, update.downloadedSize, now)
                                    } catch (_: Exception) {}
                                }
                                if (now - lastNotif >= 600L || update.isDownloadingCompleted) {
                                    lastNotif = now
                                    val progress = if (update.expectedSize > 0) {
                                        ((update.downloadedSize.toFloat() / update.expectedSize) * 100).toInt()
                                    } else if (transferEntity.fileSize > 0) {
                                        ((update.downloadedSize.toFloat() / transferEntity.fileSize) * 100).toInt()
                                    } else 0
                                    val progressNotification = notificationManager.createNotification(
                                        context = context,
                                        fileName = fileName,
                                        progress = progress.coerceIn(0, 100),
                                        isUpload = false
                                    )
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            setForeground(ForegroundInfo(notifId, progressNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                                        } else {
                                            setForeground(ForegroundInfo(notifId, progressNotification))
                                        }
                                    } catch (_: Exception) {}
                                }
                                if (update.isDownloadingCompleted && update.localPath.isNotEmpty() && File(update.localPath).exists()) {
                                    completedLocalPath = update.localPath
                                    throw kotlinx.coroutines.CancellationException("download-complete")
                                }
                            }
                    }
                } catch (_: Exception) {
                    // Check if download completed right before timeout
                    try {
                        val f = tdLibManager.getFile(tdFileId)
                        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty() && File(f.local.path).exists()) {
                            completedLocalPath = f.local.path
                        }
                    } catch (_: Exception) {}
                }
            }

            // Layer 3: Telegram Bot API Fallback
            if (completedLocalPath == null && msgId != 0L) {
                val botToken = app.preferences.getCachedBotToken().ifBlank { com.teledrive.app.core.Constants.DEFAULT_BOT_TOKEN }
                val botFileId = TelegramBotApiEngine.getPersistedBotFileId(context, msgId)
                if (botToken.isNotBlank() && !botFileId.isNullOrBlank()) {
                    AppLogger.i(TAG, "Attempting Bot API fallback download for $fileName (fileId=$botFileId)")
                    var lastDbWrite = 0L
                    var lastNotif = 0L
                    val notifId = transferId.hashCode()

                    val botResult = TelegramBotApiEngine.downloadFile(
                        botToken = botToken,
                        fileId = botFileId,
                        destFile = destFile,
                        onProgress = { downloaded, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastDbWrite >= 400L || downloaded >= total) {
                                lastDbWrite = now
                                try {
                                    transferDao.updateProgress(transferId, downloaded, now)
                                } catch (_: Exception) {}
                            }
                            if (now - lastNotif >= 600L || downloaded >= total) {
                                lastNotif = now
                                val progress = if (total > 0) ((downloaded.toFloat() / total) * 100).toInt() else 0
                                val progressNotification = notificationManager.createNotification(
                                    context = context,
                                    fileName = fileName,
                                    progress = progress.coerceIn(0, 100),
                                    isUpload = false
                                )
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        setForeground(ForegroundInfo(notifId, progressNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                                    } else {
                                        setForeground(ForegroundInfo(notifId, progressNotification))
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    )

                    if (botResult.isSuccess && destFile.exists() && destFile.length() > 0L) {
                        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                        transferDao.updateProgress(transferId, destFile.length(), System.currentTimeMillis())
                        transferDao.updateStatus(transferId, "COMPLETED", null, System.currentTimeMillis())

                        val completedNotification = notificationManager.createCompletedNotification(context, fileName, false)
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(transferId.hashCode(), completedNotification)
                        return Result.success()
                    }
                }
            }

            // Layer 4: Thumbnail fallback if image and no high-res was reachable
            if (completedLocalPath == null && msgId != 0L) {
                val cachedFile = com.teledrive.app.core.FastThumbnailCacheManager.getThumbnailFile(context, msgId.toString())
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    completedLocalPath = cachedFile.absolutePath
                }
            }

            // Finalize copy from local cache to Download directory
            val finalLocalPath = completedLocalPath
            if (!finalLocalPath.isNullOrEmpty()) {
                val sourceFile = File(finalLocalPath)
                if (sourceFile.exists() && sourceFile.length() > 0L) {
                    sourceFile.copyTo(destFile, overwrite = true)
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                    transferDao.updateProgress(transferId, destFile.length(), System.currentTimeMillis())
                    transferDao.updateStatus(transferId, "COMPLETED", null, System.currentTimeMillis())

                    val completedNotification = notificationManager.createCompletedNotification(context, fileName, false)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(transferId.hashCode(), completedNotification)

                    return Result.success()
                }
            }

            val errorMsg = "Could not locate telegram file data for $fileName"
            AppLogger.e(TAG, errorMsg)
            transferDao.updateStatus(transferId, "FAILED", errorMsg, System.currentTimeMillis())
            return Result.failure()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download worker failed with exception: ${e.message}", e)
            transferDao.updateStatus(transferId, "FAILED", e.message, System.currentTimeMillis())
            return if (e is java.io.IOException) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
