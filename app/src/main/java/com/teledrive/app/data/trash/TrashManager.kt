package com.teledrive.app.data.trash

import android.content.Context
import android.net.Uri
import com.teledrive.app.core.AppLogger
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.LocalRepository
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.telegram.TdLibManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TrashedRecord(
    val id: String,
    val displayName: String,
    val dateModified: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val mimeType: String,
    val fileSize: Long,
    val localUriString: String? = null,
    val localPath: String? = null,
    val cloudFileId: Long? = null,
    val cloudTelegramMessageId: Long? = null,
    val cloudTelegramChatId: Long? = null,
    val cloudVirtualPath: String? = null,
    val isCloudBackedUp: Boolean = false,
    val isLocalOnDevice: Boolean = false,
    val bucketName: String? = null,
    val trashedTimestamp: Long = System.currentTimeMillis()
)

class TrashManager(
    private val context: Context,
    private val localRepository: LocalRepository,
    private val tdLibManager: TdLibManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val trashFile = File(context.filesDir, "trash_records.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _trashedItems = MutableStateFlow<List<UnifiedMediaItem>>(emptyList())
    val trashedItems: StateFlow<List<UnifiedMediaItem>> = _trashedItems.asStateFlow()

    @Volatile
    private var trashedIdsCache: Set<String> = emptySet()

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    fun getTrashedIds(): Set<String> = trashedIdsCache

    fun isTrashed(id: String): Boolean = trashedIdsCache.contains(id)

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (trashFile.exists()) {
                    val content = trashFile.readText()
                    if (content.isNotBlank()) {
                        val records = json.decodeFromString<List<TrashedRecord>>(content)
                        val items = records.map { it.toUnifiedMediaItem() }
                        _trashedItems.value = items
                        trashedIdsCache = items.map { it.id }.toSet()
                        AppLogger.i("TrashManager", "Loaded ${items.size} trashed items from storage")
                        return@withLock
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("TrashManager", "Error reading trash_records.json: ${e.message}", e)
            }
            _trashedItems.value = emptyList()
            trashedIdsCache = emptySet()
        }
    }

    private suspend fun persistToDisk(items: List<UnifiedMediaItem>) = withContext(Dispatchers.IO) {
        try {
            val records = items.map { it.toTrashedRecord() }
            val content = json.encodeToString(records)
            val tmp = File(context.filesDir, "trash_records.json.tmp")
            tmp.writeText(content)
            if (tmp.renameTo(trashFile) || (trashFile.delete() && tmp.renameTo(trashFile))) {
                AppLogger.i("TrashManager", "Persisted ${items.size} items to trash_records.json")
            }
        } catch (e: Exception) {
            AppLogger.e("TrashManager", "Failed to save trash records: ${e.message}", e)
        }
    }

    suspend fun moveToTrash(items: List<UnifiedMediaItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentMap = _trashedItems.value.associateBy { it.id }.toMutableMap()
            for (item in items) {
                currentMap[item.id] = item
            }
            val updated = currentMap.values.sortedByDescending { it.dateModified }
            _trashedItems.value = updated
            trashedIdsCache = updated.map { it.id }.toSet()
            persistToDisk(updated)
        }
    }

    suspend fun restore(items: List<UnifiedMediaItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val restoreIds = items.map { it.id }.toSet()
            val updated = _trashedItems.value.filterNot { restoreIds.contains(it.id) }
            _trashedItems.value = updated
            trashedIdsCache = updated.map { it.id }.toSet()
            persistToDisk(updated)
        }
    }

    suspend fun deletePermanently(items: List<UnifiedMediaItem>) = withContext(Dispatchers.IO) {
        val deleteIds = items.map { it.id }.toSet()

        for (item in items) {
            // 1. Permanently delete from Local device storage if present
            if (item.localUri != null) {
                try {
                    context.contentResolver.delete(item.localUri, null, null)
                } catch (e: Exception) {
                    AppLogger.w("TrashManager", "contentResolver.delete failed for ${item.localUri}: ${e.message}")
                }
            }
            if (item.localPath != null && item.localPath.isNotBlank()) {
                try {
                    val file = File(item.localPath)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    AppLogger.w("TrashManager", "File.delete failed for ${item.localPath}: ${e.message}")
                }
            }

            // 2. Permanently delete from Telegram Cloud & Room DB if present
            if (item.cloudFile != null) {
                try {
                    localRepository.deleteFile(item.cloudFile)
                } catch (e: Exception) {
                    AppLogger.w("TrashManager", "Failed to delete cloud file ${item.cloudFile.fileId}: ${e.message}")
                }
            }
        }

        mutex.withLock {
            val updated = _trashedItems.value.filterNot { deleteIds.contains(it.id) }
            _trashedItems.value = updated
            trashedIdsCache = updated.map { it.id }.toSet()
            persistToDisk(updated)
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val allItems = _trashedItems.value
        deletePermanently(allItems)
    }

    private fun UnifiedMediaItem.toTrashedRecord(): TrashedRecord {
        return TrashedRecord(
            id = this.id,
            displayName = this.displayName,
            dateModified = this.dateModified,
            isVideo = this.isVideo,
            durationMs = this.durationMs,
            mimeType = this.mimeType,
            fileSize = this.fileSize,
            localUriString = this.localUri?.toString(),
            localPath = this.localPath,
            cloudFileId = this.cloudFile?.fileId,
            cloudTelegramMessageId = this.cloudFile?.telegramMessageId,
            cloudTelegramChatId = this.cloudFile?.telegramChatId,
            cloudVirtualPath = this.cloudFile?.virtualPath,
            isCloudBackedUp = this.isCloudBackedUp,
            isLocalOnDevice = this.isLocalOnDevice,
            bucketName = this.bucketName
        )
    }

    private fun TrashedRecord.toUnifiedMediaItem(): UnifiedMediaItem {
        val cloudFileEntity = if (this.cloudFileId != null && this.cloudTelegramMessageId != null && this.cloudTelegramChatId != null) {
            FileEntity(
                fileId = this.cloudFileId,
                telegramMessageId = this.cloudTelegramMessageId,
                telegramChatId = this.cloudTelegramChatId,
                virtualPath = this.cloudVirtualPath ?: "/",
                fileName = this.displayName,
                fileSize = this.fileSize,
                mimeType = this.mimeType,
                uploadTimestamp = this.dateModified
            )
        } else null

        return UnifiedMediaItem(
            id = this.id,
            displayName = this.displayName,
            dateModified = this.dateModified,
            isVideo = this.isVideo,
            durationMs = this.durationMs,
            mimeType = this.mimeType,
            fileSize = this.fileSize,
            localUri = this.localUriString?.let { Uri.parse(it) },
            localPath = this.localPath,
            cloudFile = cloudFileEntity,
            isCloudBackedUp = this.isCloudBackedUp,
            isLocalOnDevice = this.isLocalOnDevice,
            bucketName = this.bucketName
        )
    }
}
