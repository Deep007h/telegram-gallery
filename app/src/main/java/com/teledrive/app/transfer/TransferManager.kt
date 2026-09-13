package com.teledrive.app.transfer

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.teledrive.app.core.FileUtils
import com.teledrive.app.data.db.dao.TransferDao
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.db.entity.TransferEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TransferManager(
    private val context: Context,
    private val transferDao: TransferDao
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Dedupe rapid double-taps for downloads (uploads already dedupe via DB).
    private val recentDownloadKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun enqueueUpload(fileUri: Uri, virtualPath: String, chatId: Long): Long {
        return enqueueUpload(context, fileUri, virtualPath, chatId)
    }

    fun enqueueUpload(context: Context, fileUri: Uri, virtualPath: String, chatId: Long): Long {
        val (fileName, fileSize, _) = FileUtils.getFileInfo(context, fileUri)
        val fullVirtualPath = if (virtualPath.endsWith("/")) "$virtualPath$fileName" else "$virtualPath/$fileName"

        scope.launch {
            try {
                // Prevent duplicate active uploads for the same file name and size
                val activeTransfer = transferDao.getActiveUpload(fileName, fileSize)
                if (activeTransfer != null) {
                    return@launch
                }

                val tempPath = FileUtils.copyToTemp(context, fileUri, fileName)

                val effectiveChatId = if (chatId != 0L) chatId else {
                    val cached = com.teledrive.app.TeleDriveApplication.instance.preferences.getCachedStorageChatId()
                    if (cached != 0L) cached else com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
                }

                val entity = TransferEntity(
                    type = "UPLOAD",
                    status = "PENDING",
                    localFilePath = tempPath,
                    virtualPath = fullVirtualPath,
                    fileName = fileName,
                    fileSize = fileSize,
                    transferredBytes = 0L,
                    telegramChatId = effectiveChatId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                val generatedId = transferDao.insert(entity)
                scheduleUploadWork(generatedId)
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
        return 0L
    }

    fun enqueueBackupUpload(
        file: java.io.File,
        virtualPath: String,
        chatId: Long = 0L,
        sessionId: String? = null
    ): Long {
        if (!file.exists() || file.length() == 0L) return 0L
        val fileName = file.name
        val fileSize = file.length()
        val fullVirtualPath = if (virtualPath.endsWith("/")) "$virtualPath$fileName" else "$virtualPath/$fileName"

        scope.launch {
            try {
                val activeTransfer = transferDao.getActiveUpload(fileName, fileSize)
                if (activeTransfer != null) {
                    return@launch
                }

                val effectiveChatId = if (chatId != 0L) chatId else {
                    val cached = com.teledrive.app.TeleDriveApplication.instance.preferences.getCachedStorageChatId()
                    if (cached != 0L) cached else com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
                }

                val entity = TransferEntity(
                    type = "UPLOAD",
                    status = "PENDING",
                    localFilePath = file.absolutePath,
                    virtualPath = fullVirtualPath,
                    fileName = fileName,
                    fileSize = fileSize,
                    transferredBytes = 0L,
                    telegramChatId = effectiveChatId,
                    backupSessionId = sessionId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                val generatedId = transferDao.insert(entity)
                scheduleUploadWork(generatedId)
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
        return 0L
    }

    fun enqueueDownload(virtualPath: String, fileName: String, fileSize: Long, chatId: Long = 0L, messageId: Long = 0L): Long {
        // Coalesce double-taps within 2s for the same message/file.
        val key = if (messageId != 0L) "msg_${chatId}_${messageId}" else "path_${virtualPath}_${fileSize}"
        val now = System.currentTimeMillis()
        // Prune stale entries older than 10s to prevent unbounded memory growth
        if (recentDownloadKeys.size > 50) {
            recentDownloadKeys.entries.removeIf { now - it.value > 10_000L }
        }
        // Atomic check-and-insert to prevent race condition on double-taps
        val prev = recentDownloadKeys.putIfAbsent(key, now)
        if (prev != null && now - prev < 2000L) return 0L
        recentDownloadKeys[key] = now

        val downloadDir = FileUtils.getDownloadDir(context)
        val destFilePath = java.io.File(downloadDir, fileName).absolutePath

        val entity = TransferEntity(
            type = "DOWNLOAD",
            status = "PENDING",
            localFilePath = destFilePath,
            virtualPath = virtualPath,
            fileName = fileName,
            fileSize = fileSize,
            transferredBytes = 0L,
            telegramChatId = chatId,
            telegramMessageId = messageId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        scope.launch {
            val generatedId = transferDao.insert(entity)
            scheduleDownloadWork(generatedId)
        }
        return 0L
    }

    fun enqueueDownload(context: Context, fileEntity: FileEntity): Long {
        return enqueueDownload(
            virtualPath = fileEntity.virtualPath,
            fileName = fileEntity.fileName,
            fileSize = fileEntity.fileSize,
            chatId = fileEntity.telegramChatId,
            messageId = fileEntity.telegramMessageId
        )
    }

    fun cancelTransfer(transferId: Long) {
        scope.launch {
            transferDao.updateStatus(transferId, "CANCELLED", null, System.currentTimeMillis())
            workManager.cancelUniqueWork(getWorkName(transferId))
        }
    }

    fun retryTransfer(transferId: Long) {
        scope.launch {
            val transfer = transferDao.getById(transferId)
            if (transfer != null) {
                transferDao.updateStatus(transferId, "PENDING", null, System.currentTimeMillis())
                if (transfer.type == "UPLOAD") {
                    scheduleUploadWork(transferId)
                } else {
                    scheduleDownloadWork(transferId)
                }
            }
        }
    }

    fun getActiveTransfers(): Flow<List<TransferEntity>> {
        return transferDao.getAll().map { list ->
            list.filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }
        }
    }

    fun getAllTransfers(): Flow<List<TransferEntity>> {
        return transferDao.getAll()
    }

    fun clearCompleted() {
        scope.launch {
            transferDao.deleteCompleted()
        }
    }

    private fun scheduleUploadWork(transferId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putLong("transfer_id", transferId)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(transferId),
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleDownloadWork(transferId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putLong("transfer_id", transferId)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(transferId),
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun getWorkName(transferId: Long): String {
        return "transfer_$transferId"
    }
}
