package com.teledrive.app.data.repository

import com.teledrive.app.data.db.dao.FileDao
import com.teledrive.app.data.db.dao.FolderDao
import com.teledrive.app.data.db.dao.TransferDao
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.db.entity.FolderEntity
import com.teledrive.app.telegram.MetadataParser
import com.teledrive.app.telegram.TdLibManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageStats(
    val totalFiles: Int,
    val totalSize: Long,
    val imageCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val documentCount: Int,
    val otherCount: Int
)

class LocalRepository(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val transferDao: TransferDao,
    private val tdLibManager: TdLibManager,
    private val metadataParser: MetadataParser,
    private val preferences: com.teledrive.app.data.preferences.AppPreferences
) : AutoCloseable {
    private val job = SupervisorJob()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + job)

    init {
        repositoryScope.launch {
            tdLibManager.newMessages.collect { msg ->
                try {
                    val activeStorageChatId = preferences.getCachedStorageChatId()
                    val botChatId = preferences.getCachedBotChatId().toLongOrNull() ?: 0L
                    val savedId = tdLibManager.cachedSavedMessagesChatId
                    val allowedChatId = when {
                        activeStorageChatId != 0L -> activeStorageChatId
                        botChatId != 0L -> botChatId
                        else -> savedId
                    }
                    if (allowedChatId == 0L || msg.chatId != allowedChatId) {
                        return@collect
                    }
                    val metadata = metadataParser.parseCaption(msg.caption)
                    val rawName = msg.documentFileName ?: "file_${msg.messageId}"
                    val virtualPath = metadata?.path ?: "/Uncategorized/$rawName"
                    val fileName = metadata?.name ?: rawName
                    val fileSize = if (metadata != null && metadata.size > 0) metadata.size else msg.documentSize
                    val mimeType = metadata?.mime ?: (msg.documentMimeType ?: "application/octet-stream")

                    val parentPath = if (virtualPath.contains('/')) {
                        val parent = virtualPath.substringBeforeLast('/')
                        if (parent.isEmpty()) "/" else parent
                    } else {
                        "/"
                    }

                    ensureFolderExists(parentPath, msg.chatId)
                    val parentFolder = if (parentPath.isNotEmpty() && parentPath != "/") {
                        folderDao.getByPathAndChat(parentPath, msg.chatId)
                    } else null

                    val existingFile = fileDao.getByMessageId(msg.chatId, msg.messageId)

                    val fileEntity = FileEntity(
                        fileId = existingFile?.fileId ?: 0,
                        telegramMessageId = msg.messageId,
                        telegramChatId = msg.chatId,
                        virtualPath = virtualPath,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        telegramFileId = msg.documentFileId,
                        thumbnailFileId = msg.thumbnailFileId,
                        uploadTimestamp = if (metadata != null && metadata.ts > 0) {
                            if (metadata.ts > 1_000_000_000_000L) metadata.ts else metadata.ts * 1000L
                        } else {
                            msg.date.toLong() * 1000L
                        },
                        parentFolderId = parentFolder?.folderId,
                        isSynced = true
                    )

                    fileDao.upsertByMessageId(fileEntity)
                    com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.preloadThumbnails(listOf(fileEntity))
                    com.teledrive.app.core.AppLogger.logSync("LiveMessageUpsert", "Upserted live message: ${fileEntity.fileName} (${fileEntity.fileSize} bytes)")
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.e("SyncEngine", "Failed to upsert live message: ${e.message}", e)
                }
            }
        }

        repositoryScope.launch {
            tdLibManager.deletedMessages.collect { (chatId, messageIds) ->
                try {
                    for (msgId in messageIds) {
                        fileDao.deleteByMessageId(chatId, msgId)
                        com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.removeThumbnailByMessageId(chatId, msgId)
                    }
                    com.teledrive.app.core.AppLogger.logSync("LiveDelete", "Deleted ${messageIds.size} messages from Room for chatId=$chatId")
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.e("SyncEngine", "Failed to process live deleted messages: ${e.message}", e)
                }
            }
        }
    }

    override fun close() {
        job.cancel()
    }

    suspend fun syncFromTelegram(chatId: Long = 0L) = withContext(Dispatchers.IO) {
        val activeStorage = preferences.getCachedStorageChatId()
        val botChat = preferences.getCachedBotChatId().toLongOrNull() ?: 0L
        var targetChatId = when {
            chatId != 0L -> chatId
            activeStorage != 0L -> activeStorage
            botChat != 0L -> botChat
            tdLibManager.cachedSavedMessagesChatId != 0L -> tdLibManager.cachedSavedMessagesChatId
            else -> try { tdLibManager.getSavedMessagesChatId() } catch (e: Exception) { 0L }
        }
        if (targetChatId == 0L) {
            targetChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
        }

        try {
            com.teledrive.app.core.AppLogger.logSync("Start", "Starting sync for target chatId=$targetChatId")

            val botToken = preferences.getCachedBotToken().ifBlank { com.teledrive.app.core.Constants.DEFAULT_BOT_TOKEN }
            val isTdLibReady = tdLibManager.authState.value is com.teledrive.app.telegram.TdLibAuthState.Ready

            // Always sync via Bot API if bot token is present
            if (botToken.isNotBlank()) {
                try {
                    syncFromBotApi(botToken, targetChatId)
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("SyncEngine", "syncFromBotApi error: ${e.message}")
                }
            }

            if (!isTdLibReady) {
                fileDao.deleteDuplicates()
                com.teledrive.app.core.AppLogger.logSync("Complete", "TDLib not authorized, Bot API sync finished cleanly for chatId=$targetChatId")
                return@withContext
            }

            // Warm up / ensure TDLib loads chat metadata and lastMessage
            try {
                tdLibManager.sendRequest(org.drinkless.tdlib.TdApi.GetChat(targetChatId))
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("SyncEngine", "GetChat failed for chatId=$targetChatId: ${e.message}")
            }

            // Open chat stream in TDLib to ensure active synchronization
            tdLibManager.openChat(targetChatId)

            val seenMessageIds = HashSet<Long>()
            val activeTelegramMessageIds = HashSet<Long>()
            var fromMessageId = 0L
            var hasMore = true
            var totalSyncedItems = 0
            var retryCount = 0
            var completedCleanly = false

            while (hasMore) {
                val batch = try {
                    tdLibManager.getChatHistoryFull(targetChatId, fromMessageId, 100)
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("SyncEngine", "getChatHistoryFull not available or failed for chatId=$targetChatId: ${e.message}")
                    break
                }
                if (batch.totalRawCount == 0 || batch.lastRawMessageId == 0L) {
                    if (fromMessageId == 0L && retryCount < 4) {
                        retryCount++
                        com.teledrive.app.core.AppLogger.logSync("Retry", "Waiting for TDLib history warmup for chatId=$targetChatId (attempt $retryCount/4)")
                        kotlinx.coroutines.delay(1000L * retryCount)
                        continue
                    }
                    if (fromMessageId != 0L) {
                        completedCleanly = true
                    }
                    break
                }

                com.teledrive.app.core.AppLogger.logSync(
                    "Batch",
                    "Fetched ${batch.totalRawCount} raw messages, ${batch.parsedItems.size} parsed file/media items (fromMessageId=$fromMessageId, lastRawId=${batch.lastRawMessageId})"
                )

                val batchEntities = mutableListOf<FileEntity>()
                val batchParents = HashSet<String>()
                // Collect (virtualPath -> parentPath) to resolve folder ids in bulk.
                val entityParents = mutableListOf<Pair<String, String>>()
                for (msg in batch.parsedItems) {
                    activeTelegramMessageIds.add(msg.messageId)

                    if (!seenMessageIds.add(msg.messageId)) {
                        continue
                    }

                    val metadata = metadataParser.parseCaption(msg.caption)
                    val rawName = msg.documentFileName ?: "file_${msg.messageId}"
                    val virtualPath = metadata?.path ?: "/Uncategorized/$rawName"
                    val fileName = metadata?.name ?: rawName
                    val fileSize = if (metadata != null && metadata.size > 0) metadata.size else msg.documentSize
                    val mimeType = metadata?.mime ?: (msg.documentMimeType ?: "application/octet-stream")

                    val parentPath = if (virtualPath.contains('/')) {
                        val parent = virtualPath.substringBeforeLast('/')
                        if (parent.isEmpty()) "/" else parent
                    } else {
                        "/"
                    }
                    batchParents.add(parentPath)
                    entityParents.add(virtualPath to parentPath)

                    val existingFile = fileDao.getByMessageId(targetChatId, msg.messageId)

                    val fileEntity = FileEntity(
                        fileId = existingFile?.fileId ?: 0,
                        telegramMessageId = msg.messageId,
                        telegramChatId = targetChatId,
                        virtualPath = virtualPath,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        telegramFileId = msg.documentFileId,
                        thumbnailFileId = msg.thumbnailFileId,
                        uploadTimestamp = if (metadata != null && metadata.ts > 0) {
                            if (metadata.ts > 1_000_000_000_000L) metadata.ts else metadata.ts * 1000L
                        } else {
                            msg.date.toLong() * 1000L
                        },
                        parentFolderId = existingFile?.parentFolderId,
                        isSynced = true
                    )

                    batchEntities.add(fileEntity)
                }

                if (batchEntities.isNotEmpty()) {
                    // One folder pass per batch instead of per file.
                    for (p in batchParents) {
                        ensureFolderExists(p, targetChatId)
                    }
                    // Resolve folder ids in bulk (single map, no per-file query storm
                    // beyond the cached getByPathAndChat hits).
                    val folderIdByPath = HashMap<String, Long?>()
                    for (p in batchParents) {
                        folderIdByPath[p] = if (p.isNotEmpty() && p != "/") {
                            folderDao.getByPathAndChat(p, targetChatId)?.folderId
                        } else null
                    }
                    val withFolders = batchEntities.mapIndexed { idx, e ->
                        val parent = entityParents.getOrNull(idx)?.second ?: "/"
                        e.copy(parentFolderId = folderIdByPath[parent] ?: e.parentFolderId)
                    }
                    fileDao.upsertAll(withFolders)
                    com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.preloadThumbnails(withFolders)
                    totalSyncedItems += withFolders.size
                }

                if (batch.lastRawMessageId == 0L || batch.lastRawMessageId == fromMessageId) {
                    hasMore = false
                    completedCleanly = true
                } else {
                    fromMessageId = batch.lastRawMessageId
                }
            }

            // Prune files ONLY if the entire chat history was successfully traversed
            // and we have verified active message IDs, preventing accidental data wipes
            if (completedCleanly && activeTelegramMessageIds.isNotEmpty()) {
                val localFiles = fileDao.getAllFilesList().filter { it.telegramChatId == targetChatId && it.telegramMessageId != 0L }
                val toDelete = localFiles.filter { !activeTelegramMessageIds.contains(it.telegramMessageId) }
                if (toDelete.isNotEmpty()) {
                    toDelete.forEach { localFile ->
                        fileDao.delete(localFile)
                        com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.removeThumbnail(localFile)
                    }
                    com.teledrive.app.core.AppLogger.logSync("PruneDeleted", "Pruned ${toDelete.size} deleted messages from Room for chatId=$targetChatId")
                }
            }

            fileDao.deleteDuplicates()
            com.teledrive.app.core.AppLogger.logSync("Complete", "Sync completed for chatId=$targetChatId. Synced $totalSyncedItems items.")
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.e("SyncEngine", "Error during sync for chatId=$targetChatId: ${e.message}", e)
        }
    }

    suspend fun refreshFileIds(file: FileEntity): FileEntity {
        val chatId = if (file.telegramChatId != 0L) file.telegramChatId else tdLibManager.getSavedMessagesChatId()
        if (chatId != 0L && file.telegramMessageId != 0L) {
            val freshInfo = tdLibManager.getMessageInfo(chatId, file.telegramMessageId)
            if (freshInfo != null) {
                fileDao.updateFileIds(file.fileId, freshInfo.documentFileId, freshInfo.thumbnailFileId)
                return file.copy(
                    telegramFileId = freshInfo.documentFileId,
                    thumbnailFileId = freshInfo.thumbnailFileId
                )
            }
        }
        return file
    }

    fun getAllFiles(chatId: Long): Flow<List<FileEntity>> {
        val targetChatId = when {
            chatId != 0L -> chatId
            preferences.getCachedStorageChatId() != 0L -> preferences.getCachedStorageChatId()
            else -> tdLibManager.cachedSavedMessagesChatId
        }
        return fileDao.getAll().map { list ->
            list.filter { file ->
                if (targetChatId != 0L) {
                    file.telegramChatId == targetChatId || file.telegramChatId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
                } else true
            }.distinctBy { file ->
                if (file.telegramMessageId != 0L) "msg_${file.telegramMessageId}"
                else "file_${file.fileId}"
            }.sortedByDescending { it.uploadTimestamp }
        }
    }

    fun getAllMedia(chatId: Long): Flow<List<FileEntity>> {
        val targetChatId = when {
            chatId != 0L -> chatId
            preferences.getCachedStorageChatId() != 0L -> preferences.getCachedStorageChatId()
            else -> tdLibManager.cachedSavedMessagesChatId
        }
        return fileDao.getAll().map { list ->
            list.filter { file ->
                (if (targetChatId != 0L) (file.telegramChatId == targetChatId || file.telegramChatId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID) else true) &&
                (file.mimeType.startsWith("image/") ||
                 file.mimeType.startsWith("video/") ||
                 file.fileName.endsWith(".jpg", true) ||
                 file.fileName.endsWith(".jpeg", true) ||
                 file.fileName.endsWith(".png", true) ||
                 file.fileName.endsWith(".webp", true) ||
                 file.fileName.endsWith(".gif", true) ||
                 file.fileName.endsWith(".mp4", true) ||
                 file.fileName.endsWith(".mov", true) ||
                 file.fileName.endsWith(".mkv", true) ||
                 file.fileName.endsWith(".heic", true) ||
                 file.fileName.endsWith(".heif", true) ||
                 file.fileName.endsWith(".dng", true) ||
                 file.fileName.endsWith(".webm", true) ||
                 file.fileName.endsWith(".3gp", true))
            }.distinctBy { file ->
                if (file.telegramMessageId != 0L) "msg_${file.telegramMessageId}"
                else "file_${file.fileId}"
            }.sortedByDescending { it.uploadTimestamp }
        }
    }

    fun getFilesInFolder(folderPath: String, chatId: Long): Flow<List<FileEntity>> {
        val normalizedPath = if (folderPath.isEmpty() || folderPath == "/") "/" else folderPath.trimEnd('/')
        val targetChatId = when {
            chatId != 0L -> chatId
            preferences.getCachedStorageChatId() != 0L -> preferences.getCachedStorageChatId()
            else -> tdLibManager.cachedSavedMessagesChatId
        }
        return fileDao.getAll().map { list ->
            list.filter { file ->
                val parentOfFile = if (file.virtualPath.contains('/')) {
                    val parent = file.virtualPath.substringBeforeLast('/')
                    if (parent.isEmpty()) "/" else parent
                } else {
                    "/"
                }
                parentOfFile == normalizedPath && (if (targetChatId != 0L) file.telegramChatId == targetChatId else true)
            }.distinctBy { file ->
                if (file.telegramMessageId != 0L) "msg_${file.telegramMessageId}"
                else "file_${file.fileId}"
            }.sortedByDescending { it.uploadTimestamp }
        }
    }

    fun getFolders(parentPath: String, chatId: Long): Flow<List<FolderEntity>> {
        val normalizedParent = if (parentPath.isEmpty() || parentPath == "/") "/" else parentPath.trimEnd('/')
        val targetChatId = when {
            chatId != 0L -> chatId
            preferences.getCachedStorageChatId() != 0L -> preferences.getCachedStorageChatId()
            else -> tdLibManager.cachedSavedMessagesChatId
        }
        return folderDao.getAllFolders().map { list ->
            list.filter { folder ->
                val parentOfFolder = if (folder.virtualPath.contains('/')) {
                    val parent = folder.virtualPath.substringBeforeLast('/')
                    if (parent.isEmpty()) "/" else parent
                } else {
                    "/"
                }
                parentOfFolder == normalizedParent && (if (targetChatId != 0L) folder.telegramChatId == targetChatId else true)
            }
        }
    }

    suspend fun createFolder(name: String, parentPath: String, chatId: Long): Long {
        val normalizedParent = if (parentPath.isEmpty() || parentPath == "/") "" else parentPath.trimEnd('/')
        val newPath = "$normalizedParent/$name"
        val parentFolder = if (normalizedParent.isNotEmpty()) folderDao.getByPathAndChat(normalizedParent, chatId) else null

        val folder = FolderEntity(
            virtualPath = newPath,
            folderName = name,
            parentFolderId = parentFolder?.folderId,
            telegramChatId = chatId
        )
        return folderDao.insert(folder)
    }

    suspend fun deleteFile(file: FileEntity) {
        try {
            val targetChatId = if (file.telegramChatId != 0L) {
                file.telegramChatId
            } else {
                try { tdLibManager.getSavedMessagesChatId() } catch (e: Exception) { 0L }
            }
            com.teledrive.app.core.AppLogger.i("LocalRepository", "Deleting message from Telegram: chatId=$targetChatId, msgId=${file.telegramMessageId}")
            if (targetChatId != 0L && file.telegramMessageId != 0L) {
                tdLibManager.deleteMessages(targetChatId, longArrayOf(file.telegramMessageId))
            }
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.e("LocalRepository", "Failed to delete message from Telegram: ${e.message}", e)
        }
        fileDao.delete(file)
        com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.removeThumbnail(file)
        com.teledrive.app.core.AppLogger.i("LocalRepository", "Deleted file from database: ${file.fileName}")
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        val files = fileDao.getAllFilesList().filter { it.virtualPath.startsWith(folder.virtualPath) && it.telegramChatId == folder.telegramChatId }
        val msgIds = files.map { it.telegramMessageId }.toLongArray()
        if (msgIds.isNotEmpty()) {
            try {
                tdLibManager.deleteMessages(folder.telegramChatId, msgIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        for (f in files) {
            fileDao.delete(f)
        }
        folderDao.delete(folder)
        folderDao.deleteByPathAndChat(folder.virtualPath, folder.telegramChatId)
    }

    suspend fun moveFile(fileId: Long, newPath: String) {
        val file = fileDao.getAllFilesList().firstOrNull { it.fileId == fileId } ?: return
        val newCaption = metadataParser.generateCaption(
            virtualPath = newPath,
            fileName = file.fileName,
            fileSize = file.fileSize,
            mimeType = file.mimeType
        )
        try {
            tdLibManager.editMessageCaption(file.telegramChatId, file.telegramMessageId, newCaption)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val updated = file.copy(virtualPath = newPath)
        fileDao.update(updated)
    }

    suspend fun renameFile(fileId: Long, newName: String) {
        val file = fileDao.getAllFilesList().firstOrNull { it.fileId == fileId } ?: return
        val parentPath = if (file.virtualPath.contains('/')) file.virtualPath.substringBeforeLast('/') else ""
        val newVirtualPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
        val newCaption = metadataParser.generateCaption(
            virtualPath = newVirtualPath,
            fileName = newName,
            fileSize = file.fileSize,
            mimeType = file.mimeType
        )
        try {
            tdLibManager.editMessageCaption(file.telegramChatId, file.telegramMessageId, newCaption)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val updated = file.copy(fileName = newName, virtualPath = newVirtualPath)
        fileDao.update(updated)
    }

    fun searchFiles(query: String): Flow<List<FileEntity>> {
        return fileDao.searchByName(query)
    }

    fun getStorageStats(): Flow<StorageStats> {
        return fileDao.getAll().map { files ->
            var totalSize = 0L
            var imgCount = 0
            var vidCount = 0
            var audioCount = 0
            var docCount = 0
            var otherCount = 0

            for (f in files) {
                totalSize += f.fileSize
                when {
                    f.mimeType.startsWith("image/") -> imgCount++
                    f.mimeType.startsWith("video/") -> vidCount++
                    f.mimeType.startsWith("audio/") -> audioCount++
                    f.mimeType.startsWith("application/") || f.mimeType.startsWith("text/") -> docCount++
                    else -> otherCount++
                }
            }

            StorageStats(
                totalFiles = files.size,
                totalSize = totalSize,
                imageCount = imgCount,
                videoCount = vidCount,
                audioCount = audioCount,
                documentCount = docCount,
                otherCount = otherCount
            )
        }
    }

    suspend fun insertFile(entity: FileEntity): Long {
        return fileDao.insert(entity)
    }

    suspend fun ensureFolderExists(virtualPath: String, chatId: Long) {
        if (virtualPath.isEmpty() || virtualPath == "/") return
        if (folderDao.exists(virtualPath, chatId)) return

        val segments = virtualPath.trim('/').split('/')
        var currentPath = ""
        var parentId: Long? = null

        for (segment in segments) {
            currentPath += "/$segment"
            val existing = folderDao.getByPathAndChat(currentPath, chatId)
            if (existing == null) {
                val newFolder = FolderEntity(
                    virtualPath = currentPath,
                    folderName = segment,
                    parentFolderId = parentId,
                    telegramChatId = chatId
                )
                parentId = folderDao.insert(newFolder)
            } else {
                parentId = existing.folderId
            }
        }
    }

    private suspend fun syncFromBotApi(botToken: String, targetChatId: Long) = withContext(Dispatchers.IO) {
        try {
            // Fetch user profile and avatar from Bot API
            val userChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
            try {
                val chatInfoResult = com.teledrive.app.telegram.TelegramBotApiEngine.fetchChatInfo(botToken, userChatId)
                if (chatInfoResult.isSuccess) {
                    val info = chatInfoResult.getOrThrow()
                    if (info.title.isNotBlank() && !info.title.startsWith("Personal Chat")) {
                        preferences.setUserDisplayName(info.title)
                    }
                    if (!info.photoFileId.isNullOrBlank()) {
                        val avatarFile = java.io.File(com.teledrive.app.TeleDriveApplication.instance.filesDir, "telegram_avatar_actual.jpg")
                        val photoRes = com.teledrive.app.telegram.TelegramBotApiEngine.downloadProfilePhoto(botToken, info.photoFileId, avatarFile)
                        if (photoRes.isSuccess && avatarFile.exists() && avatarFile.length() > 0) {
                            preferences.setTelegramProfilePhotoPath(avatarFile.absolutePath)
                            com.teledrive.app.core.AppLogger.i("SyncEngine", "Downloaded actual Telegram profile photo: ${avatarFile.absolutePath}")
                        }
                    }
                }
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("SyncEngine", "Failed to fetch chat info/photo: ${e.message}")
            }

            val updatesRes = com.teledrive.app.telegram.TelegramBotApiEngine.getRecentUpdates(botToken)
            if (updatesRes.isSuccess) {
                val mediaItems = updatesRes.getOrThrow()
                val syncedEntities = mutableListOf<FileEntity>()

                for (item in mediaItems) {
                    if (item.chatId != targetChatId && targetChatId != 0L && item.chatId != com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID) {
                        continue
                    }

                    val metadata = metadataParser.parseCaption(item.caption)
                    val rawFileName = item.fileName
                    val virtualPath = metadata?.path ?: "/Uncategorized/$rawFileName"
                    val fileName = metadata?.name ?: rawFileName
                    val parentPath = if (virtualPath.contains('/')) {
                        val parent = virtualPath.substringBeforeLast('/')
                        if (parent.isEmpty()) "/" else parent
                    } else "/"

                    ensureFolderExists(parentPath, targetChatId)
                    val parentFolder = if (parentPath != "/") folderDao.getByPathAndChat(parentPath, targetChatId) else null
                    val existingFile = fileDao.getByMessageId(targetChatId, item.messageId)

                    val entity = FileEntity(
                        fileId = existingFile?.fileId ?: 0,
                        telegramMessageId = item.messageId,
                        telegramChatId = targetChatId,
                        virtualPath = virtualPath,
                        fileName = fileName,
                        fileSize = if (metadata != null && metadata.size > 0) metadata.size else item.fileSize,
                        mimeType = metadata?.mime ?: item.mimeType,
                        telegramFileId = (item.messageId % 100000).toInt(),
                        thumbnailFileId = null,
                        uploadTimestamp = item.date,
                        parentFolderId = parentFolder?.folderId,
                        isSynced = true
                    )
                    fileDao.upsertByMessageId(entity)
                    val insertedEntity = fileDao.getByMessageId(targetChatId, item.messageId) ?: entity
                    syncedEntities.add(insertedEntity)

                    // Download thumbnail for instant gallery preview
                    val isImage = entity.mimeType.startsWith("image/") || entity.fileName.endsWith(".jpg", true) || entity.fileName.endsWith(".jpeg", true) || entity.fileName.endsWith(".png", true) || entity.fileName.endsWith(".webp", true)
                    val thumbId = item.thumbFileId ?: if (isImage) item.fileId else null
                    if (!thumbId.isNullOrBlank()) {
                        try {
                            val appCtx = com.teledrive.app.TeleDriveApplication.instance
                            val thumbFile = com.teledrive.app.core.FastThumbnailCacheManager.getThumbnailFile(appCtx, item.messageId.toString())
                            if (!thumbFile.exists() || thumbFile.length() == 0L) {
                                val dlRes = com.teledrive.app.telegram.TelegramBotApiEngine.downloadFile(botToken, thumbId, thumbFile)
                                if (dlRes.isSuccess && thumbFile.exists() && thumbFile.length() > 0L) {
                                    com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache(item.messageId.toString(), thumbFile.absolutePath)
                                    com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache("cloud_${insertedEntity.fileId}", thumbFile.absolutePath)
                                }
                            } else {
                                com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache(item.messageId.toString(), thumbFile.absolutePath)
                                com.teledrive.app.core.FastThumbnailCacheManager.putWarmCache("cloud_${insertedEntity.fileId}", thumbFile.absolutePath)
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (syncedEntities.isNotEmpty()) {
                    com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.preloadThumbnails(syncedEntities)
                    com.teledrive.app.core.AppLogger.logSync("BotApiSync", "Synced ${syncedEntities.size} files from Telegram Bot API")
                }
            }
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("SyncEngine", "syncFromBotApi failed: ${e.message}")
        }
    }
}
