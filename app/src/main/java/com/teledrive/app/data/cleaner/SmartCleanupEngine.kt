package com.teledrive.app.data.cleaner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import com.teledrive.app.ai.RustFaceEngine
import com.teledrive.app.data.repository.DeviceMediaRepository
import com.teledrive.app.data.repository.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class CleanupCategory {
    object Duplicates : CleanupCategory()
    data class LargeVideos(val thresholdMB: Int = 50) : CleanupCategory()
    data class OldMedia(val daysOld: Int = 180) : CleanupCategory()
}

@Immutable
data class CleanupGroup(
    val id: String,
    val title: String,
    val description: String,
    val category: CleanupCategory,
    val items: List<LocalMediaItem>,
    val totalSizeBytes: Long,
    val reclaimableSizeBytes: Long
)

class SmartCleanupEngine(
    private val context: Context,
    private val deviceMediaRepository: DeviceMediaRepository
) {
    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Finds duplicate photos on device by grouping by size and computing SHA-256 header hashes.
     */
    fun findDuplicateImages(): Flow<List<CleanupGroup>> = flow {
        val allMedia = deviceMediaRepository.getAllDeviceMedia()
        val images = allMedia.filter { !it.isVideo }

        // Step 1: Pre-filter by file size to avoid hashing unique files
        val sizeBuckets = images.groupBy { it.size }.filter { it.value.size > 1 && it.key > 10_000L }
        val duplicateGroups = mutableListOf<CleanupGroup>()

        var groupIdx = 0
        for ((_, bucket) in sizeBuckets) {
            val hashBuckets = mutableMapOf<String, MutableList<LocalMediaItem>>()
            for (item in bucket) {
                val hash = calculateHeaderHash(item.contentUri)
                if (hash != null) {
                    hashBuckets.getOrPut(hash) { mutableListOf() }.add(item)
                }
            }

            for ((_, duplicateItems) in hashBuckets) {
                if (duplicateItems.size > 1) {
                    val totalSize = duplicateItems.sumOf { it.size }
                    val reclaimable = duplicateItems.drop(1).sumOf { it.size }
                    duplicateGroups.add(
                        CleanupGroup(
                            id = "dup_${groupIdx++}",
                            title = "Duplicate Group #${groupIdx} (${duplicateItems.size} photos)",
                            description = "Identical photos taking ${formatSize(totalSize)}",
                            category = CleanupCategory.Duplicates,
                            items = duplicateItems,
                            totalSizeBytes = totalSize,
                            reclaimableSizeBytes = reclaimable
                        )
                    )
                }
            }
        }

        emit(duplicateGroups)
    }.flowOn(Dispatchers.IO)

    /**
     * Finds large videos exceeding threshold (e.g. 50 MB / 100 MB).
     */
    fun findLargeVideos(thresholdMB: Int = 50): Flow<List<CleanupGroup>> = flow {
        val thresholdBytes = thresholdMB * 1024L * 1024L
        val allMedia = deviceMediaRepository.getAllDeviceMedia()
        val largeVideos = allMedia.filter { it.isVideo && it.size >= thresholdBytes }
            .sortedByDescending { it.size }

        if (largeVideos.isNotEmpty()) {
            val totalSize = largeVideos.sumOf { it.size }
            emit(
                listOf(
                    CleanupGroup(
                        id = "large_videos_${thresholdMB}mb",
                        title = "Large Videos (>${thresholdMB}MB)",
                        description = "${largeVideos.size} videos taking up ${formatSize(totalSize)}",
                        category = CleanupCategory.LargeVideos(thresholdMB),
                        items = largeVideos,
                        totalSizeBytes = totalSize,
                        reclaimableSizeBytes = totalSize
                    )
                )
            )
        } else {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Finds dormant media items older than N days.
     */
    fun findOldMedia(daysOld: Int = 180): Flow<List<CleanupGroup>> = flow {
        emit(findOldMediaFromList(daysOld, deviceMediaRepository.getAllDeviceMedia()))
    }.flowOn(Dispatchers.IO)

    fun findOldMedia(daysOld: Int = 180, allMedia: List<LocalMediaItem>): Flow<List<CleanupGroup>> = flow {
        emit(findOldMediaFromList(daysOld, allMedia))
    }.flowOn(Dispatchers.IO)

    private fun findOldMediaFromList(daysOld: Int, allMedia: List<LocalMediaItem>): List<CleanupGroup> {
        // dateModified from DeviceMediaRepository is in MILLISECONDS (DATE_MODIFIED*1000).
        val cutoffTimeMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysOld.toLong())
        val oldMedia = allMedia.filter { it.dateModified > 0 && it.dateModified < cutoffTimeMs }
            .sortedBy { it.dateModified }

        return if (oldMedia.isNotEmpty()) {
            val totalSize = oldMedia.sumOf { it.size }
            listOf(
                CleanupGroup(
                    id = "old_media_${daysOld}days",
                    title = "Old Media (>${daysOld} days old)",
                    description = "${oldMedia.size} dormant photos/videos taking ${formatSize(totalSize)}",
                    category = CleanupCategory.OldMedia(daysOld),
                    items = oldMedia,
                    totalSizeBytes = totalSize,
                    reclaimableSizeBytes = totalSize
                )
            )
        } else {
            emptyList()
        }
    }

    /**
     * Deletes local media items from device MediaStore.
     */
    suspend fun deleteLocalMediaItems(items: List<LocalMediaItem>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (item in items) {
                val deleted = contentResolver.delete(item.contentUri, null, null)
                if (deleted > 0) count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculateHeaderHash(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(32768)
                val read = stream.read(buffer)
                if (read > 0) {
                    val bytesToHash = if (read == buffer.size) buffer else buffer.copyOf(read)
                    RustFaceEngine.sha256Header(bytesToHash)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
