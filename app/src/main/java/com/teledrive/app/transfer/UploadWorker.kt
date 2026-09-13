package com.teledrive.app.transfer

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.getMimeType
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import java.io.File

class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val transferId = inputData.getLong("transfer_id", -1L)
        if (transferId == -1L) return Result.failure()

        val app = context.applicationContext as TeleDriveApplication
        val transferDao = app.database.transferDao()
        val fileDao = app.database.fileDao()
        val folderDao = app.database.folderDao()
        val tdLibManager = app.tdLibManager
        val fileRepository = app.fileRepository
        val localRepository = app.localRepository

        val transferEntity = transferDao.getById(transferId) ?: return Result.failure()
        if (transferEntity.status == "COMPLETED") return Result.success()

        transferDao.updateStatus(transferId, "IN_PROGRESS", null, System.currentTimeMillis())
        val notificationManager = TransferNotificationManager()

        try {
            val notification = notificationManager.createNotification(
                context = context,
                fileName = transferEntity.fileName,
                progress = 0,
                isUpload = true
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setForeground(ForegroundInfo(transferId.toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                } else {
                    setForeground(ForegroundInfo(transferId.toInt(), notification))
                }
            } catch (ignored: Exception) {}

            val mimeType = transferEntity.fileName.getMimeType()
            val notifId = transferId.hashCode()
            var lastDbWrite = 0L
            var lastNotif = 0L

            val targetChatId = if (transferEntity.telegramChatId != 0L) {
                transferEntity.telegramChatId
            } else {
                app.preferences.getCachedStorageChatId().takeIf { it != 0L } ?: com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
            }

            var finalMessageId = 0L
            var finalDocumentFileId = 0
            var finalThumbnailFileId: Int? = null
            var finalFileSize = transferEntity.fileSize

            val isTdLibReady = tdLibManager.authState.value is com.teledrive.app.telegram.TdLibAuthState.Ready

            if (isTdLibReady) {
                val uploadResult = fileRepository.uploadFile(
                    localPath = transferEntity.localFilePath,
                    virtualPath = transferEntity.virtualPath,
                    fileName = transferEntity.fileName,
                    fileSize = transferEntity.fileSize,
                    mimeType = mimeType,
                    chatId = targetChatId
                )

                if (uploadResult.isSuccess) {
                    val msgInfo = uploadResult.getOrThrow()
                    finalMessageId = msgInfo.messageId
                    finalDocumentFileId = msgInfo.documentFileId
                    finalThumbnailFileId = msgInfo.thumbnailFileId
                    finalFileSize = if (transferEntity.fileSize > 0) transferEntity.fileSize else msgInfo.documentSize

                    // Check if TDLib already completed uploading the file
                    var isFinished = false
                    try {
                        val initialFile = tdLibManager.getFile(msgInfo.documentFileId)
                        if (initialFile.remote.isUploadingCompleted) {
                            isFinished = true
                        }
                    } catch (ignored: Exception) {}

                    if (!isFinished) {
                        try {
                            withTimeout(180_000L) {
                                tdLibManager.fileUpdates
                                    .filter { it.fileId == msgInfo.documentFileId }
                                    .collect { update ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastDbWrite >= 400L || update.isUploadingCompleted) {
                                            lastDbWrite = now
                                            try {
                                                transferDao.updateProgress(transferId, update.uploadedSize, now)
                                            } catch (_: Exception) {}
                                        }
                                        if (now - lastNotif >= 600L || update.isUploadingCompleted) {
                                            lastNotif = now
                                            val progress = if (update.expectedSize > 0) {
                                                ((update.uploadedSize.toFloat() / update.expectedSize) * 100).toInt()
                                            } else if (transferEntity.fileSize > 0) {
                                                ((update.uploadedSize.toFloat() / transferEntity.fileSize) * 100).toInt()
                                            } else 0
                                            val progressNotification = notificationManager.createNotification(
                                                context = context,
                                                fileName = transferEntity.fileName,
                                                progress = progress.coerceIn(0, 100),
                                                isUpload = true
                                            )
                                            try {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                    setForeground(ForegroundInfo(notifId, progressNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                                                } else {
                                                    setForeground(ForegroundInfo(notifId, progressNotification))
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        if (update.isUploadingCompleted) {
                                            isFinished = true
                                            throw kotlinx.coroutines.CancellationException("upload-complete")
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            if (!isFinished) {
                                com.teledrive.app.core.AppLogger.w("UploadWorker", "TDLib progress tracking ended: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Fallback or primary via Bot API if TDLib didn't upload
            if (finalMessageId == 0L) {
                com.teledrive.app.core.AppLogger.i("UploadWorker", "Routing upload via Telegram Bot API for ${transferEntity.fileName}")
                val botToken = app.preferences.getCachedBotToken().ifBlank { com.teledrive.app.core.Constants.DEFAULT_BOT_TOKEN }
                val caption = com.teledrive.app.telegram.MetadataParser.generateCaption(
                    transferEntity.virtualPath,
                    transferEntity.fileName,
                    transferEntity.fileSize,
                    mimeType
                )
                val botResult = com.teledrive.app.telegram.TelegramBotApiEngine.uploadDocument(
                    botToken = botToken,
                    chatId = targetChatId,
                    file = File(transferEntity.localFilePath),
                    caption = caption,
                    fileName = transferEntity.fileName,
                    mimeType = mimeType,
                    onProgress = { uploaded, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastDbWrite >= 400L || uploaded >= total) {
                            lastDbWrite = now
                            try {
                                transferDao.updateProgress(transferId, uploaded, now)
                            } catch (_: Exception) {}
                        }
                        if (now - lastNotif >= 600L || uploaded >= total) {
                            lastNotif = now
                            val progress = if (total > 0L) ((uploaded.toFloat() / total) * 100).toInt() else 0
                            val progressNotification = notificationManager.createNotification(
                                context = context,
                                fileName = transferEntity.fileName,
                                progress = progress.coerceIn(0, 100),
                                isUpload = true
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

                if (botResult.isFailure) {
                    val errorMsg = botResult.exceptionOrNull()?.message ?: "Upload failed"
                    com.teledrive.app.core.AppLogger.e("UploadWorker", "Bot API upload failed: $errorMsg")
                    transferDao.updateStatus(transferId, "FAILED", errorMsg, System.currentTimeMillis())
                    return Result.failure()
                }

                val res = botResult.getOrThrow()
                finalMessageId = res.messageId
                finalDocumentFileId = (res.messageId % 100000).toInt()
                finalThumbnailFileId = null
                finalFileSize = if (transferEntity.fileSize > 0) transferEntity.fileSize else res.fileSize
            }

            transferDao.updateProgress(transferId, finalFileSize, System.currentTimeMillis())
            transferDao.updateStatus(transferId, "COMPLETED", null, System.currentTimeMillis())

            val parentPath = if (transferEntity.virtualPath.contains('/')) {
                val parent = transferEntity.virtualPath.substringBeforeLast('/')
                if (parent.isEmpty()) "/" else parent
            } else {
                "/"
            }

            localRepository.ensureFolderExists(parentPath, targetChatId)
            val parentFolder = if (parentPath.isNotEmpty() && parentPath != "/") {
                folderDao.getByPathAndChat(parentPath, targetChatId)
            } else null

            val existingFile = fileDao.getByMessageId(targetChatId, finalMessageId)
                ?: (if (transferEntity.fileSize > 0) fileDao.getByChatNameAndSize(targetChatId, transferEntity.fileName, transferEntity.fileSize) else null)

            val fileEntity = FileEntity(
                fileId = existingFile?.fileId ?: 0,
                telegramMessageId = finalMessageId,
                telegramChatId = targetChatId,
                virtualPath = transferEntity.virtualPath,
                fileName = transferEntity.fileName,
                fileSize = finalFileSize,
                mimeType = mimeType,
                telegramFileId = finalDocumentFileId,
                thumbnailFileId = finalThumbnailFileId,
                uploadTimestamp = System.currentTimeMillis(),
                parentFolderId = parentFolder?.folderId,
                isSynced = true
            )
            fileDao.upsertByMessageId(fileEntity)
            fileDao.deleteDuplicates()

            // Cache thumbnail locally so it is instantly available in the gallery
            try {
                val srcLocalFile = File(transferEntity.localFilePath)
                if (srcLocalFile.exists() && srcLocalFile.length() > 0) {
                    val cachedThumb = com.teledrive.app.core.FastThumbnailCacheManager.getThumbnailFile(context, finalMessageId.toString())
                    srcLocalFile.copyTo(cachedThumb, overwrite = true)
                    com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache(finalMessageId.toString(), cachedThumb.absolutePath)
                    val inserted = fileDao.getByMessageId(targetChatId, finalMessageId)
                    if (inserted != null) {
                        com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache("cloud_${inserted.fileId}", cachedThumb.absolutePath)
                    }
                }
            } catch (_: Exception) {}

            // Record in BackupDao so future backup passes know this file is backed up
            try {
                val srcFile = File(transferEntity.localFilePath)
                val hash = if (srcFile.exists()) com.teledrive.app.backup.DecideBackupActionUseCase.computeSha256(srcFile) else null
                val inserted = fileDao.getByMessageId(targetChatId, finalMessageId)
                val backupRecord = com.teledrive.app.data.db.entity.BackupRecordEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sourcePath = transferEntity.localFilePath,
                    fileId = inserted?.fileId,
                    sizeBytes = finalFileSize,
                    modifiedAt = if (srcFile.exists()) srcFile.lastModified() else System.currentTimeMillis(),
                    contentHash = hash,
                    backedUpAt = System.currentTimeMillis()
                )
                com.teledrive.app.TeleDriveApplication.instance.database.backupDao().upsertRecord(backupRecord)
            } catch (_: Exception) {}

            // Delete temporary local file only if inside cache/temp directory (NEVER delete user media)
            try {
                val f = File(transferEntity.localFilePath)
                val tempDir = context.cacheDir.absolutePath
                val filesDir = context.filesDir.absolutePath
                if (f.absolutePath.startsWith(tempDir) || f.absolutePath.contains("/teledrive_temp/") || f.name.startsWith("temp_upload_")) {
                    f.delete()
                }
            } catch (ignored: Exception) {}

            com.teledrive.app.core.AppLogger.i("UploadWorker", "Upload successfully completed for ${transferEntity.fileName} (msgId=$finalMessageId)")
            return Result.success()
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.e("UploadWorker", "UploadWorker exception: ${e.message}", e)
            transferDao.updateStatus(transferId, "FAILED", e.message, System.currentTimeMillis())
            return Result.failure()
        }
    }
}
