package com.teledrive.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.ai.DetectedFaceResult
import com.teledrive.app.ai.FaceRecognitionEngine
import com.teledrive.app.ai.RustFaceEngine
import com.teledrive.app.core.AppLogger
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.telegram.TdLibManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.Immutable
import android.os.Process
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.sqrt

@Immutable
data class PersonCluster(
    val personId: String,
    val name: String?,
    val coverFacePath: String,
    val faceCount: Int,
    val cloudFiles: List<FileEntity>,
    val representativeFeature: FloatArray? = null
)

class PeopleRepository(
    private val context: Context,
    private val tdLibManager: TdLibManager
) {
    private val faceEngine = FaceRecognitionEngine(context)
    private val mutex = Mutex()

    private val _peopleClusters = MutableStateFlow<List<PersonCluster>>(emptyList())
    val peopleClusters: StateFlow<List<PersonCluster>> = _peopleClusters.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val processedFileIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val avatarDir = File(context.filesDir, "people_avatars").apply { mkdirs() }
    private val metadataFile = File(context.filesDir, "people_metadata.json")
    // Face inference + bitmap decode must not run on Dispatchers.IO (it starves
    // thumbnail fetches on the shared IO pool). Single-threaded Default worker
    // with ONE reusable scope (was: a fresh CoroutineScope per scan → leak).
    private val faceWorker = Dispatchers.Default.limitedParallelism(1)
    private val faceScope = kotlinx.coroutines.CoroutineScope(faceWorker + kotlinx.coroutines.SupervisorJob())
    @Volatile private var scanJob: kotlinx.coroutines.Job? = null

    init {
        loadPersistedClusters()
    }

    private fun loadPersistedClusters() {
        try {
            if (metadataFile.exists()) {
                val jsonStr = metadataFile.readText()
                val root = JSONObject(jsonStr)
                val clustersArray = root.optJSONArray("clusters") ?: JSONArray()
                val loaded = mutableListOf<PersonCluster>()

                for (i in 0 until clustersArray.length()) {
                    val obj = clustersArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = if (obj.has("name") && !obj.isNull("name")) obj.getString("name") else null
                    val cover = obj.getString("cover")
                    val faceCount = obj.optInt("faceCount", 1)

                    val featureArray = obj.optJSONArray("features")
                    val features = if (featureArray != null && featureArray.length() == 192) {
                        FloatArray(featureArray.length()) { featureArray.getDouble(it).toFloat() }
                    } else {
                        // Stale cluster from before the MobileFaceNet upgrade — skip.
                        null
                    }
                    if (features == null) continue

                    loaded.add(
                        PersonCluster(
                            personId = id,
                            name = name,
                            coverFacePath = cover,
                            faceCount = faceCount,
                            cloudFiles = emptyList(),
                            representativeFeature = features
                        )
                    )
                }
                _peopleClusters.value = loaded
            }
        } catch (e: Exception) {
            AppLogger.e("PeopleRepo", "Failed to load persisted clusters: ${e.message}", e)
        }
    }

    private fun persistClusters(clusters: List<PersonCluster>, fileMappings: Map<String, List<Long>>) {
        try {
            val root = JSONObject()
            val arr = JSONArray()

            for (cluster in clusters) {
                val obj = JSONObject().apply {
                    put("id", cluster.personId)
                    put("name", cluster.name ?: JSONObject.NULL)
                    put("cover", cluster.coverFacePath)
                    put("faceCount", cluster.faceCount)
                    if (cluster.representativeFeature != null) {
                        val fArr = JSONArray()
                        for (f in cluster.representativeFeature) {
                            fArr.put(f.toDouble())
                        }
                        put("features", fArr)
                    }
                    val idsArr = JSONArray()
                    val fIds = fileMappings[cluster.personId] ?: cluster.cloudFiles.map { it.fileId }
                    for (fid in fIds) {
                        idsArr.put(fid)
                    }
                    put("fileIds", idsArr)
                }
                arr.put(obj)
            }
            root.put("clusters", arr)
            metadataFile.writeText(root.toString())
        } catch (e: Exception) {
            AppLogger.e("PeopleRepo", "Failed to persist clusters: ${e.message}", e)
        }
    }

    suspend fun renamePerson(personId: String, newName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _peopleClusters.value.map { cluster ->
                if (cluster.personId == personId) {
                    cluster.copy(name = if (newName.isBlank()) null else newName.trim())
                } else cluster
            }
            _peopleClusters.value = updated
            persistClusters(updated, updated.associate { it.personId to it.cloudFiles.map { f -> f.fileId } })
        }
    }

    suspend fun rescanAll(cloudFiles: List<FileEntity>) {
        try { scanJob?.cancelAndJoin() } catch (_: Exception) {}
        mutex.withLock {
            processedFileIds.clear()
            avatarDir.deleteRecursively()
            avatarDir.mkdirs()
            if (metadataFile.exists()) metadataFile.delete()
            _peopleClusters.value = emptyList()
        }
        scanCloudMediaInternal(cloudFiles, forceRescan = true)
    }

    suspend fun scanCloudMedia(cloudFiles: List<FileEntity>) {
        // Drop overlapping bursts (ViewModel debounces to 150ms already); the
        // previous fire-and-forget storm contended the thumbnail dispatcher.
        // Reuses one scope (no per-call scope leak) and joins so callers can
        // fire-and-forget from their own background launch.
        if (_isScanning.value) return
        val job = faceScope.launch {
            scanCloudMediaInternal(cloudFiles, forceRescan = false)
        }
        scanJob = job
        try { job.join() } catch (_: Exception) {}
    }

    private suspend fun scanCloudMediaInternal(cloudFiles: List<FileEntity>, forceRescan: Boolean) = withContext(faceWorker) {
        if (_isScanning.value) return@withContext
        _isScanning.value = true

        try {
            val mediaFiles = cloudFiles.filter { file ->
                val mime = file.mimeType.lowercase()
                val name = file.fileName.lowercase()
                mime.startsWith("image/") || mime.startsWith("video/") ||
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                        name.endsWith(".webp") || name.endsWith(".mp4") || name.endsWith(".mkv")
            }

            val currentClusters = if (forceRescan) mutableListOf() else _peopleClusters.value.toMutableList()
            val clusterFilesMap = HashMap<String, MutableList<FileEntity>>()

            // Hydrate existing mappings from metadata
            if (!forceRescan && metadataFile.exists()) {
                try {
                    val root = JSONObject(metadataFile.readText())
                    val arr = root.optJSONArray("clusters") ?: JSONArray()
                    val filesById = cloudFiles.associateBy { it.fileId }

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.getString("id")
                        val idsArr = obj.optJSONArray("fileIds")
                        if (idsArr != null) {
                            val list = clusterFilesMap.getOrPut(id) { mutableListOf() }
                            for (j in 0 until idsArr.length()) {
                                val fid = idsArr.getLong(j)
                                filesById[fid]?.let { list.add(it) }
                                processedFileIds.add(fid)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (ignored: Exception) {}

            for (file in mediaFiles) {
                if (!forceRescan && processedFileIds.contains(file.fileId)) {
                    continue
                }

                try {
                    val bitmap = loadBitmapForFile(file)
                    if (bitmap != null) {
                        val faces: List<DetectedFaceResult> = faceEngine.detectFaces(bitmap)
                        AppLogger.i("PeopleRepo", "File ${file.fileName}: detected ${faces.size} faces")

                        if (faces.isNotEmpty()) {
                            for (face in faces) {
                                // MobileFaceNet L2-normalized cosine threshold: 0.55 ≈ 99% LFW.
                                // 0.50 is a safer setting for clustering (more permissive — a face
                                // that's "likely the same person" merges in, while different people
                                // score < 0.40 typically).
                                val dim = face.featureVector.size
                                var bestClusterIdx = -1

                                if (currentClusters.isNotEmpty()) {
                                    val excludeList = mutableListOf<Int>()
                                    val centroidsFlat = FloatArray(currentClusters.size * dim)
                                    for (cIdx in currentClusters.indices) {
                                        val c = currentClusters[cIdx]
                                        val feat = c.representativeFeature
                                        val alreadyInFile = clusterFilesMap[c.personId]?.any { it.fileId == file.fileId } == true
                                        if (feat == null || feat.size != dim || alreadyInFile) {
                                            excludeList.add(cIdx)
                                        } else {
                                            System.arraycopy(feat, 0, centroidsFlat, cIdx * dim, dim)
                                        }
                                    }

                                    bestClusterIdx = RustFaceEngine.batchFindBestCluster(
                                        faceVec = face.featureVector,
                                        centroidsFlat = centroidsFlat,
                                        numCentroids = currentClusters.size,
                                        dim = dim,
                                        threshold = 0.50f,
                                        excludeIndices = if (excludeList.isNotEmpty()) excludeList.toIntArray() else null
                                    )
                                }

                                if (bestClusterIdx >= 0) {
                                    val matched = currentClusters[bestClusterIdx]
                                    clusterFilesMap.getOrPut(matched.personId) { mutableListOf() }.apply {
                                        if (!any { it.fileId == file.fileId }) add(file)
                                    }

                                    // Update cluster centroid with running-mean L2-normalized average via Rust / optimized fallback
                                    val updatedFeature = if (matched.representativeFeature != null) {
                                        RustFaceEngine.updateCentroid(matched.representativeFeature, face.featureVector, matched.faceCount)
                                    } else face.featureVector

                                    val updatedFiles = clusterFilesMap[matched.personId] ?: listOf(file)
                                    currentClusters[bestClusterIdx] = matched.copy(
                                        faceCount = updatedFiles.size,
                                        cloudFiles = updatedFiles,
                                        representativeFeature = updatedFeature
                                    )
                                } else {
                                    // Create new Person cluster for this distinct individual.
                                    // UUID avoids collisions when several faces are found
                                    // within the same millisecond (old timestamp+random did collide).
                                    val personId = "person_${java.util.UUID.randomUUID()}"
                                    val avatarFile = File(avatarDir, "$personId.jpg")
                                    try {
                                        FileOutputStream(avatarFile).use { out ->
                                            face.faceBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    val newCluster = PersonCluster(
                                        personId = personId,
                                        name = null,
                                        coverFacePath = avatarFile.absolutePath,
                                        faceCount = 1,
                                        cloudFiles = listOf(file),
                                        representativeFeature = face.featureVector
                                    )
                                    currentClusters.add(newCluster)
                                    clusterFilesMap[personId] = mutableListOf(file)
                                    AppLogger.i("PeopleRepo", "Created new PersonCluster $personId from ${file.fileName}")
                                }
                            }
                        }
                        processedFileIds.add(file.fileId)
                        // Recycle crops + source to bound native memory during long scans.
                        try {
                            for (f in faces) {
                                try { f.faceBitmap.recycle() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                        try { bitmap.recycle() } catch (_: Exception) {}
                    }
                    // Longer pace + faceWorker isolation: face scan shares the
                    // thumbnail dispatcher, so yielding here protects visible tiles.
                    delay(80)
                    // Honor cancellation promptly (rescan).
                    ensureActive()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    t.printStackTrace()
                }
            }

            // Update cluster file lists and sort by photo count descending
            val finalClusters = currentClusters.map { cluster ->
                val files = (clusterFilesMap[cluster.personId] ?: cluster.cloudFiles).distinctBy { it.fileId }
                cluster.copy(
                    cloudFiles = files,
                    faceCount = files.size
                )
            }.filter { it.faceCount > 0 }.sortedByDescending { it.faceCount }

            _peopleClusters.value = finalClusters
            persistClusters(finalClusters, finalClusters.associate { it.personId to it.cloudFiles.map { f -> f.fileId } })
            AppLogger.i("PeopleRepo", "Face scanning complete. Total people identified: ${finalClusters.size}")
        } catch (e: Exception) {
            AppLogger.e("PeopleRepo", "Error during face clustering: ${e.message}", e)
        } finally {
            _isScanning.value = false
        }
    }

    private suspend fun loadBitmapForFile(file: FileEntity): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val thumbManager = TeleDriveApplication.instance.thumbnailCacheManager
            val cachedPath = thumbManager.getFastCachedPath(file) ?: thumbManager.getOrFetchThumbnail(file)
            if (!cachedPath.isNullOrEmpty()) {
                val f = File(cachedPath)
                if (f.exists() && f.length() > 0) {
                    return@withContext decodeSampledBitmap(f.absolutePath)
                }
            }

            val cachedFile = File(context.cacheDir, file.fileName)
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return@withContext decodeSampledBitmap(cachedFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int = 800, reqHeight: Int = 800): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            var sampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // ARGB_8888 preserves chrominance for MobileFaceNet embeddings;
                // RGB_565 banding measurably hurts accuracy.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, decodeOptions)
        } catch (t: Throwable) {
            t.printStackTrace()
            return null
        }
    }
}
