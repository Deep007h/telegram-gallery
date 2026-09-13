package com.teledrive.app.ai

import android.util.Log
import java.security.MessageDigest
import kotlin.math.sqrt

object RustFaceEngine {
    private const val TAG = "RustFaceEngine"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("teledrive_native")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Rust native library not available, using Kotlin fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Rust library: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = isLoaded

    // Native JNI methods
    @JvmStatic
    private external fun nativeL2Normalize(input: FloatArray): FloatArray

    @JvmStatic
    private external fun nativeCosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float

    @JvmStatic
    private external fun nativeBatchFindBestCluster(
        faceVec: FloatArray,
        centroidsFlat: FloatArray,
        numCentroids: Int,
        dim: Int,
        threshold: Float,
        excludeIndices: IntArray?
    ): Int

    @JvmStatic
    private external fun nativeUpdateCentroid(
        oldCentroid: FloatArray,
        newFace: FloatArray,
        count: Int
    ): FloatArray

    @JvmStatic
    private external fun nativeSha256Header(data: ByteArray): String

    // Public API with fallbacks
    fun l2Normalize(input: FloatArray): FloatArray {
        if (isLoaded) {
            try {
                return nativeL2Normalize(input)
            } catch (e: Exception) {
                Log.e(TAG, "Native L2 normalization failed, using fallback", e)
            }
        }
        
        // Fallback
        var sumSq = 0.0f
        for (v in input) {
            sumSq += v * v
        }
        var norm = sqrt(sumSq.toDouble()).toFloat()
        if (norm < 1e-5f) norm = 1e-5f
        
        val result = FloatArray(input.size)
        for (i in input.indices) {
            result[i] = input[i] / norm
        }
        return result
    }

    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (isLoaded) {
            try {
                return nativeCosineSimilarity(vec1, vec2)
            } catch (e: Exception) {
                Log.e(TAG, "Native cosine similarity failed, using fallback", e)
            }
        }
        
        // Fallback
        if (vec1.size != vec2.size) return 0f
        var dot = 0f
        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    fun batchFindBestCluster(
        faceVec: FloatArray,
        centroidsFlat: FloatArray,
        numCentroids: Int,
        dim: Int,
        threshold: Float,
        excludeIndices: IntArray? = null
    ): Int {
        if (isLoaded) {
            try {
                return nativeBatchFindBestCluster(faceVec, centroidsFlat, numCentroids, dim, threshold, excludeIndices)
            } catch (e: Exception) {
                Log.e(TAG, "Native batch find cluster failed, using fallback", e)
            }
        }
        
        // Fallback
        var bestIdx = -1
        var bestScore = -1f
        val excludes = excludeIndices?.toSet() ?: emptySet()
        
        for (i in 0 until numCentroids) {
            if (i in excludes) continue
            
            val start = i * dim
            var dot = 0f
            for (j in 0 until dim) {
                dot += faceVec[j] * centroidsFlat[start + j]
            }
            val score = dot.coerceIn(-1f, 1f)
            
            if (score >= threshold && score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    fun updateCentroid(
        oldCentroid: FloatArray,
        newFace: FloatArray,
        count: Int
    ): FloatArray {
        if (isLoaded) {
            try {
                return nativeUpdateCentroid(oldCentroid, newFace, count)
            } catch (e: Exception) {
                Log.e(TAG, "Native update centroid failed, using fallback", e)
            }
        }
        
        // Fallback
        if (oldCentroid.size != newFace.size) return FloatArray(0)
        
        val merged = FloatArray(oldCentroid.size)
        var sumSq = 0.0f
        
        for (i in oldCentroid.indices) {
            merged[i] = (oldCentroid[i] * count + newFace[i]) / (count + 1)
            sumSq += merged[i] * merged[i]
        }
        
        var norm = sqrt(sumSq.toDouble()).toFloat()
        if (norm < 1e-5f) norm = 1e-5f
        
        for (i in merged.indices) {
            merged[i] /= norm
        }
        return merged
    }

    fun sha256Header(data: ByteArray): String {
        if (isLoaded) {
            try {
                return nativeSha256Header(data)
            } catch (e: Exception) {
                Log.e(TAG, "Native sha256 failed, using fallback", e)
            }
        }
        
        // Fallback
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(data)
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
