package com.teledrive.app.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val isComplete: Boolean,
    val localPath: String?
)

class FileRepository(
    private val tdLibManager: TdLibManager
) {
    suspend fun uploadFile(
        localPath: String,
        virtualPath: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        chatId: Long
    ): Result<TdMessageInfo> {
        val targetChat = if (chatId != 0L) chatId else com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
        if (tdLibManager.authState.value is TdLibAuthState.Ready) {
            try {
                val caption = MetadataParser.generateCaption(virtualPath, fileName, fileSize, mimeType)
                val messageInfo = tdLibManager.sendFile(targetChat, localPath, caption)
                return Result.success(messageInfo)
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("FileRepository", "TDLib sendFile failed (${e.message}), falling back to Bot API")
            }
        }
        return try {
            val botToken = com.teledrive.app.TeleDriveApplication.instance.preferences.getCachedBotToken().ifBlank {
                com.teledrive.app.core.Constants.DEFAULT_BOT_TOKEN
            }
            val caption = MetadataParser.generateCaption(virtualPath, fileName, fileSize, mimeType)
            val botRes = TelegramBotApiEngine.uploadDocument(
                botToken = botToken,
                chatId = targetChat,
                file = java.io.File(localPath),
                caption = caption,
                fileName = fileName,
                mimeType = mimeType
            )
            if (botRes.isSuccess) {
                val res = botRes.getOrThrow()
                Result.success(
                    TdMessageInfo(
                        messageId = res.messageId,
                        chatId = targetChat,
                        date = (res.timestamp / 1000L).toInt(),
                        caption = caption,
                        documentFileName = res.fileName,
                        documentMimeType = res.mimeType,
                        documentSize = res.fileSize,
                        documentFileId = (res.messageId % 100000).toInt(),
                        thumbnailFileId = null
                    )
                )
            } else {
                Result.failure(botRes.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(fileId: Int): Flow<DownloadProgress> = flow {
        // Fast path: already completed locally.
        try {
            val existing = tdLibManager.getFile(fileId)
            if (existing.local.isDownloadingCompleted && existing.local.path.isNotEmpty() && java.io.File(existing.local.path).exists()) {
                emit(
                    DownloadProgress(
                        bytesDownloaded = existing.size,
                        totalBytes = existing.size,
                        isComplete = true,
                        localPath = existing.local.path
                    )
                )
                return@flow
            }
        } catch (_: Exception) {}

        try {
            tdLibManager.startDownload(fileId, 1)
        } catch (e: Exception) {
            return@flow
        }

        // Single collector: the old code collected fileUpdates twice in sequence
        // (takeWhile then a second filter for completion). If completion arrived
        // during the first collect's cancellation gap, the second collect hung
        // forever and the flow never completed. This flow stays active until the
        // collector cancels (e.g. after isComplete); no early return needed.
        tdLibManager.fileUpdates
            .filter { it.fileId == fileId }
            .collect { update ->
                val total = update.expectedSize.takeIf { it > 0 } ?: update.size
                val done = update.isDownloadingCompleted && update.localPath.isNotEmpty()
                emit(
                    DownloadProgress(
                        bytesDownloaded = update.downloadedSize,
                        totalBytes = total,
                        isComplete = done,
                        localPath = update.localPath.ifEmpty { null }
                    )
                )
            }
    }

    suspend fun deleteFile(chatId: Long, messageId: Long): Result<Unit> {
        return try {
            tdLibManager.deleteMessages(chatId, longArrayOf(messageId))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFile(
        chatId: Long,
        messageId: Long,
        currentCaption: String,
        newName: String,
        newPath: String
    ): Result<Unit> {
        return try {
            val currentMetadata = MetadataParser.parseCaption(currentCaption)
                ?: throw IllegalArgumentException("Invalid file metadata caption")

            val newCaption = MetadataParser.generateCaption(
                virtualPath = newPath,
                fileName = newName,
                fileSize = currentMetadata.size,
                mimeType = currentMetadata.mime
            )

            tdLibManager.editMessageCaption(chatId, messageId, newCaption)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFileUpdates(): SharedFlow<TdFileUpdate> {
        return tdLibManager.fileUpdates
    }
}
