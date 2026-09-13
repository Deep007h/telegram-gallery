package com.teledrive.app.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Immutable

@Immutable
data class LocalMediaItem(
    val id: Long,
    val contentUri: Uri,
    val filePath: String,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val bucketId: String,
    val bucketName: String
)

@Immutable
data class DeviceAlbum(
    val bucketId: String,
    val name: String,
    val coverUri: Uri,
    val coverPath: String,
    val itemCount: Int,
    val videoCount: Int,
    val photoCount: Int,
    val items: List<LocalMediaItem>
)

@Immutable
data class UnifiedMediaItem(
    val id: String,
    val displayName: String,
    val dateModified: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val mimeType: String,
    val fileSize: Long,
    val localUri: Uri? = null,
    val localPath: String? = null,
    val cloudFile: FileEntity? = null,
    val isCloudBackedUp: Boolean = false,
    val isLocalOnDevice: Boolean = false,
    val bucketName: String? = null
)

fun LocalMediaItem.toUnifiedMediaItem(): UnifiedMediaItem = UnifiedMediaItem(
    id = "local_${id}",
    displayName = displayName,
    dateModified = dateModified,
    isVideo = isVideo,
    durationMs = durationMs,
    mimeType = mimeType,
    fileSize = size,
    localUri = contentUri,
    localPath = filePath,
    cloudFile = null,
    isCloudBackedUp = false,
    isLocalOnDevice = true,
    bucketName = bucketName
)

class DeviceMediaRepository(private val context: Context) {

    private val mutex = Mutex()
    private var cachedLocalMedia: List<LocalMediaItem>? = null
    private var cachedDeviceAlbums: List<DeviceAlbum>? = null

    fun invalidateCache() {
        cachedLocalMedia = null
        cachedDeviceAlbums = null
    }

    suspend fun getAllDeviceMedia(forceRefresh: Boolean = false): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedLocalMedia?.let { return@withContext it }
        }

        suspend fun queryImages(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
            val out = mutableListOf<LocalMediaItem>()
            // DATA column is deprecated on Android 10+ (scoped storage) and slow;
            // resolve path lazily and tolerate its absence.
            val imageProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val bucketIdCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                    val bucketNameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                    if (idCol != -1) {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = if (nameCol != -1) cursor.getString(nameCol) ?: "IMG_$id.jpg" else "IMG_$id.jpg"
                            val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                            val size = if (sizeCol != -1) { try { cursor.getLong(sizeCol) } catch (_: Exception) { 0L } } else 0L
                            val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                            val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()
                            val bucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) ?: "0" else "0"
                            val bucketName = if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "Pictures" else "Pictures"
                            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                            out.add(
                                LocalMediaItem(
                                    id = id,
                                    contentUri = contentUri,
                                    filePath = path,
                                    displayName = name,
                                    size = size,
                                    mimeType = mime,
                                    dateModified = date,
                                    isVideo = false,
                                    bucketId = bucketId,
                                    bucketName = bucketName
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.e("DeviceMedia", "Error querying images: ${e.message}", e)
            }
            out
        }

        suspend fun queryVideos(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
            val out = mutableListOf<LocalMediaItem>()
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )

            try {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                    val bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                    val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                    if (idCol != -1) {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = if (nameCol != -1) cursor.getString(nameCol) ?: "VID_$id.mp4" else "VID_$id.mp4"
                            val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                            val size = if (sizeCol != -1) { try { cursor.getLong(sizeCol) } catch (_: Exception) { 0L } } else 0L
                            val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                            val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()
                            val bucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) ?: "0" else "0"
                            val bucketName = if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "Videos" else "Videos"
                            val duration = if (durCol != -1) { try { cursor.getLong(durCol) } catch (_: Exception) { 0L } } else 0L
                            val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                            out.add(
                                LocalMediaItem(
                                    id = id,
                                    contentUri = contentUri,
                                    filePath = path,
                                    displayName = name,
                                    size = size,
                                    mimeType = mime,
                                    dateModified = date,
                                    isVideo = true,
                                    durationMs = duration,
                                    bucketId = bucketId,
                                    bucketName = bucketName
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.e("DeviceMedia", "Error querying videos: ${e.message}", e)
            }
            out
        }

        // Query images+videos concurrently instead of serially under one mutex,
        // then merge. The mutex only guards the cache write.
        val (images, videos) = coroutineScope {
            val imagesDeferred = async { queryImages() }
            val videosDeferred = async { queryVideos() }
            imagesDeferred.await() to videosDeferred.await()
        }
        val allMedia = images + videos
        com.teledrive.app.core.AppLogger.i("DeviceMedia", "Queried ${images.size} images and ${videos.size} videos from MediaStore")

        val trashedIds = try { com.teledrive.app.TeleDriveApplication.instance.trashManager.getTrashedIds() } catch (_: Exception) { emptySet() }
        val filtered = if (trashedIds.isEmpty()) allMedia else allMedia.filterNot { trashedIds.contains("local_${it.id}") }

        val sorted = filtered.sortedByDescending { it.dateModified }
        mutex.withLock { cachedLocalMedia = sorted }
        sorted
    }

    suspend fun getDeviceAlbums(forceRefresh: Boolean = false, cloudFiles: List<FileEntity>? = null): List<DeviceAlbum> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedDeviceAlbums != null && cloudFiles == null) {
            return@withContext cachedDeviceAlbums!!
        }

        val allMedia = getAllDeviceMedia(forceRefresh)
        val filteredMedia = if (cloudFiles != null) {
            val cloudLookup = HashMap<String, MutableList<FileEntity>>()
            for (cloud in cloudFiles) {
                cloudLookup.getOrPut(cloud.fileName.lowercase().trim()) { mutableListOf() }.add(cloud)
            }
            allMedia.filter { local ->
                val key = local.displayName.lowercase().trim()
                val candidates = cloudLookup[key]
                candidates?.any { it.fileSize == local.size || it.fileSize == 0L || local.size == 0L } == true
            }
        } else {
            allMedia
        }

        val grouped = filteredMedia.groupBy { it.bucketName }
        val albums = grouped.mapNotNull { (bucketName, items) ->
            if (items.isEmpty()) return@mapNotNull null
            val sortedItems = items.sortedByDescending { it.dateModified }
            val cover = sortedItems.first()
            val videoCount = sortedItems.count { it.isVideo }
            val photoCount = sortedItems.size - videoCount

            DeviceAlbum(
                bucketId = cover.bucketId,
                name = bucketName,
                coverUri = cover.contentUri,
                coverPath = cover.filePath,
                itemCount = sortedItems.size,
                videoCount = videoCount,
                photoCount = photoCount,
                items = sortedItems
            )
        }.sortedWith(
            compareByDescending<DeviceAlbum> {
                when (it.name.lowercase()) {
                    "camera" -> 100
                    "screenshots" -> 90
                    "whatsapp images" -> 80
                    "whatsapp video" -> 75
                    "pictures" -> 70
                    "movies", "videos" -> 65
                    "downloads", "download" -> 60
                    else -> 0
                }
            }.thenByDescending { it.itemCount }
        )

        if (cloudFiles == null) {
            cachedDeviceAlbums = albums
        }
        albums
    }

    suspend fun buildUnifiedMedia(cloudFiles: List<FileEntity>): List<UnifiedMediaItem> = withContext(Dispatchers.IO) {
        val trashedIds = try { com.teledrive.app.TeleDriveApplication.instance.trashManager.getTrashedIds() } catch (_: Exception) { emptySet() }
        val activeCloudFiles = if (trashedIds.isEmpty()) cloudFiles else cloudFiles.filterNot { trashedIds.contains("cloud_${it.fileId}") }
        val localMedia = getAllDeviceMedia(forceRefresh = false)
        val unified = ArrayList<UnifiedMediaItem>(activeCloudFiles.size)
        val matchedCloudFileIds = HashSet<Long>(activeCloudFiles.size)

        // Build fast lookup index for cloud files
        val cloudByName = HashMap<String, MutableList<FileEntity>>()
        for (cloud in activeCloudFiles) {
            val key = cloud.fileName.lowercase().trim()
            cloudByName.getOrPut(key) { mutableListOf() }.add(cloud)
        }

        for (local in localMedia) {
            if (trashedIds.contains("local_${local.id}")) {
                continue
            }
            val key = local.displayName.lowercase().trim()
            val candidateClouds = cloudByName[key]
            // Exact size match only: the old <4096 tolerance merged distinct
            // files that share a name (burst IMG_001.jpg) and showed a wrong
            // "backed up" badge + wrong cloud thumbnail.
            val matchingCloud = candidateClouds?.firstOrNull { cloud ->
                cloud.fileSize == local.size
            } ?: candidateClouds?.firstOrNull { cloud ->
                cloud.fileSize == 0L || local.size == 0L
            }

            if (matchingCloud != null) {
                matchedCloudFileIds.add(matchingCloud.fileId)
                unified.add(
                    UnifiedMediaItem(
                        id = "local_${local.id}",
                        displayName = local.displayName,
                        dateModified = local.dateModified,
                        isVideo = local.isVideo,
                        durationMs = local.durationMs,
                        mimeType = local.mimeType,
                        fileSize = local.size,
                        localUri = local.contentUri,
                        localPath = local.filePath,
                        cloudFile = matchingCloud,
                        isCloudBackedUp = true,
                        isLocalOnDevice = true,
                        bucketName = local.bucketName
                    )
                )
            }
            // Non-backed up local media items are omitted:
            // "only show files / phots / video present in saved messages chat of telegram , nothing else"
        }

        // Add remaining cloud files that are not stored locally on device
        for (cloud in activeCloudFiles) {
            if (!matchedCloudFileIds.contains(cloud.fileId)) {
                val isVid = cloud.mimeType.startsWith("video/") || cloud.fileName.endsWith(".mp4", true) || cloud.fileName.endsWith(".mkv", true)
                unified.add(
                    UnifiedMediaItem(
                        id = "cloud_${cloud.fileId}",
                        displayName = cloud.fileName,
                        dateModified = cloud.uploadTimestamp,
                        isVideo = isVid,
                        durationMs = 0L,
                        mimeType = cloud.mimeType,
                        fileSize = cloud.fileSize,
                        localUri = null,
                        localPath = null,
                        cloudFile = cloud,
                        isCloudBackedUp = true,
                        isLocalOnDevice = false,
                        bucketName = "Telegram Cloud"
                    )
                )
            }
        }

        unified.sortedByDescending { it.dateModified }
    }
}
