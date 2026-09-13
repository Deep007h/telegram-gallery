package com.teledrive.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.util.LruCache
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.telegram.TdLibManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class ThumbnailCacheManager(
    private val context: Context,
    private val tdLibManager: TdLibManager
) {
    // Thumbnail fetching is suspending (network await), not CPU-blocking, so 2
    // threads starved 30 visible tiles behind each other (first-open blank grid
    // that pops in over seconds = perceived jank). 4 still protects the UI looper
    // while letting the viewport fill ~2x faster. Decode of >400KB files is the
    // only CPU part and is short.
    private val backgroundDispatcher = AppDispatchers.Backend
    private val scope = CoroutineScope(backgroundDispatcher + SupervisorJob())
    private val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }

    // In-memory fast cache (ChatId_MessageId -> LocalFilePath)
    private val memoryPathCache = LruCache<String, String>(1000)
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String?>>()
    private var preloadJob: Job? = null
    // Set by the Photos grid while flinging: preload yields so visible-tile
    // fetches (same dispatcher) win every race during scroll.
    @Volatile private var preloadPaused: Boolean = false

    fun setPreloadPaused(paused: Boolean) {
        preloadPaused = paused
    }

    fun getCacheKey(file: FileEntity): String {
        return if (file.telegramChatId != 0L && file.telegramMessageId != 0L) {
            "thumb_${file.telegramChatId}_${file.telegramMessageId}.jpg"
        } else if (file.fileSize > 0) {
            // Include fileId to avoid hashCode collisions between different files
            // that share a name and size (e.g. IMG_001.jpg from two folders).
            "thumb_${file.fileName.hashCode()}_${file.fileSize}_${file.fileId}.jpg"
        } else {
            "thumb_${file.fileName.hashCode()}_${file.fileId}.jpg"
        }
    }

    fun getLookupKey(file: FileEntity): String {
        return if (file.telegramChatId != 0L && file.telegramMessageId != 0L) {
            "${file.telegramChatId}_${file.telegramMessageId}"
        } else {
            // Must include fileId: name+size alone collides for burst shots
            // (IMG_001.jpg × N) and returned the WRONG cached path, causing a
            // wrong-image flash followed by a refetch — visible flicker plus a
            // wasted dedupe slot in getOrFetchThumbnail.
            "${file.fileName.hashCode()}_${file.fileSize}_${file.fileId}"
        }
    }

    /** RAM-only eviction for the Coil onError stale-path retry (no disk I/O). */
    fun evictRamEntry(file: FileEntity) {
        try {
            memoryPathCache.remove(getLookupKey(file))
        } catch (_: Exception) {}
    }

    /**
     * Synchronous fast lookup for Frame-0 instant rendering without any flicker or placeholder.
     * Main-safe: RAM LruCache get + a few file stats only (microseconds, no
     * decode/network). Hits also touch lastModified so prune() evicts true LRU
     * instead of oldest-created (which deleted still-viewed thumbs and forced
     * re-downloads on scroll-back).
     */
    fun getFastCachedPath(file: FileEntity): String? {
        val lookupKey = getLookupKey(file)

        // 1. Check RAM Cache
        memoryPathCache.get(lookupKey)?.let { path ->
            try {
                val f = File(path)
                if (f.exists() && f.length() > 0) {
                    // Touch at most hourly: setLastModified is a disk write; doing
                    // it per scroll frame would trip StrictMode and wear flash.
                    try {
                        val now = System.currentTimeMillis()
                        if (now - f.lastModified() > 3_600_000L) {
                            f.setLastModified(now)
                        }
                    } catch (_: Exception) {}
                    return path
                } else {
                    memoryPathCache.remove(lookupKey)
                }
            } catch (_: Exception) {
                // Fall through to disk checks.
            }
        }

        // 2. Check Fast Thumbnail Cache Store
        if (file.telegramMessageId != 0L) {
            FastThumbnailCacheManager.getCachedThumbnailPath(file.telegramMessageId.toString())?.let { path ->
                memoryPathCache.put(lookupKey, path)
                return path
            }
        }
        if (file.fileId != 0L) {
            FastThumbnailCacheManager.getCachedThumbnailPath("cloud_${file.fileId}")?.let { path ->
                memoryPathCache.put(lookupKey, path)
                return path
            }
        }

        // 3. Check Permanent Thumbnail Store
        val thumbFile = File(thumbDir, getCacheKey(file))
        if (thumbFile.exists() && thumbFile.length() > 0) {
            val path = thumbFile.absolutePath
            memoryPathCache.put(lookupKey, path)
            try {
                val now = System.currentTimeMillis()
                if (now - thumbFile.lastModified() > 3_600_000L) {
                    thumbFile.setLastModified(now)
                }
            } catch (_: Exception) {}
            return path
        }

        // 3. Check App Cache Dir
        val cachedAppFile = File(context.cacheDir, file.fileName)
        if (cachedAppFile.exists() && cachedAppFile.length() > 0) {
            val path = cachedAppFile.absolutePath
            memoryPathCache.put(lookupKey, path)
            return path
        }

        return null
    }

    /**
     * Asynchronous fetch and persistent cache on disk.
     */
    suspend fun getOrFetchThumbnail(file: FileEntity): String? = withContext(backgroundDispatcher) {
        // Fast path
        getFastCachedPath(file)?.let { return@withContext it }

        val lookupKey = getLookupKey(file)

        // Deduplicate in-flight fetch requests. computeIfAbsent is atomic; the
        // deferred is removed only by its creator to avoid stealing a new
        // request that replaced it after completion.
        val existing = inFlightRequests[lookupKey]
        if (existing != null) {
            return@withContext try { existing.await() } catch (_: Exception) { null }
        }
        val deferred = scope.async {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (ignored: Exception) {}
            fetchAndStoreThumbnail(file)
        }
        val prev = inFlightRequests.putIfAbsent(lookupKey, deferred)
        if (prev != null) {
            deferred.cancel()
            return@withContext try { prev.await() } catch (_: Exception) { null }
        }

        try {
            deferred.await()
        } finally {
            inFlightRequests.remove(lookupKey, deferred)
        }
    }

    private suspend fun fetchAndStoreThumbnail(file: FileEntity): String? {
        val targetThumbFile = File(thumbDir, getCacheKey(file))
        if (targetThumbFile.exists() && targetThumbFile.length() > 0) {
            val path = targetThumbFile.absolutePath
            memoryPathCache.put(getLookupKey(file), path)
            return path
        }

        val isImage = file.mimeType.startsWith("image/") || file.fileName.endsWith(".jpg", true) || file.fileName.endsWith(".jpeg", true) || file.fileName.endsWith(".png", true) || file.fileName.endsWith(".webp", true)
        val isVideo = file.mimeType.startsWith("video/") || file.fileName.endsWith(".mp4", true) || file.fileName.endsWith(".mov", true) || file.fileName.endsWith(".mkv", true)

        var targetFileId = when {
            file.thumbnailFileId != null && file.thumbnailFileId != 0 -> file.thumbnailFileId!!
            (isImage || isVideo) && file.telegramFileId != 0 -> file.telegramFileId
            else -> 0
        }

        // If target file ID is missing/0, resolve from Telegram message
        if (targetFileId == 0 && file.telegramMessageId != 0L) {
            try {
                val chatId = if (file.telegramChatId != 0L) file.telegramChatId else tdLibManager.getSavedMessagesChatId()
                if (chatId != 0L) {
                    val info = tdLibManager.getMessageInfo(chatId, file.telegramMessageId)
                    if (info != null) {
                        targetFileId = if (info.thumbnailFileId != null && info.thumbnailFileId != 0) {
                            info.thumbnailFileId
                        } else if (isImage || isVideo) {
                            info.documentFileId
                        } else 0
                    }
                }
            } catch (_: Exception) {}
        }

        if (targetFileId == 0) return null

        try {
            val downloadedPath = tdLibManager.downloadFile(targetFileId)
            if (downloadedPath.isNotEmpty()) {
                val sourceFile = File(downloadedPath)
                if (sourceFile.exists() && sourceFile.length() > 0) {
                    // Copy / optimize to permanent thumbnails directory
                    if (sourceFile.length() > 400 * 1024) {
                        saveOptimizedThumbnail(sourceFile, targetThumbFile)
                    } else {
                        sourceFile.copyTo(targetThumbFile, overwrite = true)
                    }

                    val finalPath = targetThumbFile.absolutePath
                    memoryPathCache.put(getLookupKey(file), finalPath)
                    return finalPath
                }
            }
        } catch (e: Exception) {
            AppLogger.w("ThumbCache", "Failed to fetch thumbnail for ${file.fileName}: ${e.message}")
        }
        return null
    }

    private fun saveOptimizedThumbnail(sourceFile: File, targetFile: File) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)

            var sampleSize = 1
            while ((options.outWidth / sampleSize) >= 480 || (options.outHeight / sampleSize) >= 480) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts)
            if (bitmap != null) {
                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                bitmap.recycle()
            } else {
                sourceFile.copyTo(targetFile, overwrite = true)
            }
        } catch (e: Exception) {
            sourceFile.copyTo(targetFile, overwrite = true)
        }
    }

    /**
     * Proactive preloading of thumbnails in background so scrolling is 100% instant.
     * Paced gently with yields so it never stutters the UI!
     * Single-flight + debounced: each sync batch previously cancelled the last
     * preload and restarted it, so 10 rapid batches did 10× truncated scans and
     * never finished (visible tiles starved the whole sync). Now waits 600ms
     * quiet before starting, and yields entirely while the grid is flinging.
     */
    fun preloadThumbnails(files: List<FileEntity>) {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            // Debounce burst emissions (sync batches, Room re-emits).
            try { delay(800) } catch (_: Exception) { return@launch }
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (ignored: Exception) {}

            val mediaFiles = files.filter { file ->
                val mime = file.mimeType.lowercase()
                val name = file.fileName.lowercase()
                mime.startsWith("image/") || mime.startsWith("video/") ||
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                        name.endsWith(".webp") || name.endsWith(".mp4") || name.endsWith(".mkv")
            }

            for (file in mediaFiles.take(80)) {
                ensureActive()
                // Yield the dispatcher while the user scrolls or interacts; visible tiles win.
                while (preloadPaused || InteractionCoordinator.isInteracting) {
                    delay(120)
                    ensureActive()
                }
                if (getFastCachedPath(file) == null) {
                    getOrFetchThumbnail(file)
                    // Paced gently to keep UI buttery smooth
                    delay(60)
                }
            }
            // Bound disk growth by true LRU (lastModified is touched on every
            // hit above). Cap raised 400 → 2000: with a 1000-photo library the
            // old cap deleted still-owned thumbs and forced re-downloads on
            // every scroll-back ("loads again and again"). 2000 × ~30KB ≈ 60MB.
            try {
                val thumbs = thumbDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
                if (thumbs.size > 2000) {
                    for (old in thumbs.take(thumbs.size - 2000)) {
                        try { old.delete() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Evict and delete thumbnail when an item is deleted.
     */
    fun removeThumbnail(file: FileEntity) {
        val lookupKey = getLookupKey(file)
        memoryPathCache.remove(lookupKey)
        val targetThumbFile = File(thumbDir, getCacheKey(file))
        if (targetThumbFile.exists()) {
            targetThumbFile.delete()
        }
    }

    fun removeThumbnailByMessageId(chatId: Long, messageId: Long) {
        val lookupKey = "${chatId}_${messageId}"
        memoryPathCache.remove(lookupKey)
        val targetThumbFile = File(thumbDir, "thumb_${chatId}_${messageId}.jpg")
        if (targetThumbFile.exists()) {
            targetThumbFile.delete()
        }
    }

    fun clearAll() {
        memoryPathCache.evictAll()
        thumbDir.deleteRecursively()
        thumbDir.mkdirs()
    }
}
