package com.teledrive.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DetectedFaceResult(
    val faceBitmap: Bitmap,
    val featureVector: FloatArray,
    val boundingBox: Rect
)

/**
 * Two-stage face recognition pipeline:
 *   1. ML Kit Face Detection — finds face bounding boxes + landmarks
 *   2. MobileFaceNet TFLite — produces a 192-D L2-normalized embedding per crop
 *
 * Cosine similarity >= 0.55 between two L2-normalized MobileFaceNet embeddings
 * corresponds to ~99% LFW accuracy and is the standard threshold used in
 * face-clustering pipelines (e.g. Google Photos, Apple Photos).
 */
class FaceRecognitionEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceEngine"
        private const val MODEL_ASSET = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 192
    }

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.04f)
            .build()
        FaceDetection.getClient(options)
    }

    private val interpreter: Interpreter by lazy { loadInterpreter() }

    private var modelBatchSize: Int = 1
    @Volatile private var shapesLogged = false

    private fun ensureShapesLogged() {
        if (shapesLogged) return
        try {
            val inShape = interpreter.getInputTensor(0).shape()
            modelBatchSize = inShape[0]
            Log.i(TAG, "MobileFaceNet ready. Input=${inShape.joinToString()}, Output=${interpreter.getOutputTensor(0).shape().joinToString()}")
            shapesLogged = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect model shapes: ${e.message}")
        }
    }

    private fun loadInterpreter(): Interpreter {
        val model = loadModelFile()
        val options = Interpreter.Options().apply {
            setUseXNNPACK(true)
            setNumThreads(2)
        }
        return Interpreter(model, options)
    }

    private fun loadModelFile(): MappedByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel = fis.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    suspend fun detectFaces(rawBitmap: Bitmap): List<DetectedFaceResult> {
        // Run on the caller's dispatcher (PeopleRepository.faceWorker, single
        // thread): the old withContext(Default) hopped off the isolated worker
        // onto the shared Default pool, re-contending the UI-adjacent threads
        // the isolation was meant to protect.
        ensureShapesLogged()
        val results = mutableListOf<DetectedFaceResult>()
        try {
            val maxDim = max(rawBitmap.width, rawBitmap.height)
            val bitmap = if (maxDim > 1200) {
                val scale = 1200f / maxDim
                val targetW = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
            } else {
                rawBitmap
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces: List<Face> = detector.process(inputImage).await()

            for (face in faces) {
                val box = face.boundingBox
                if (box.width() < 24 || box.height() < 24) continue

                val expandX = (box.width() * 0.30f).toInt()
                val expandY = (box.height() * 0.35f).toInt()

                val left = max(0, box.left - expandX)
                val top = max(0, box.top - expandY)
                val right = min(bitmap.width, box.right + expandX)
                val bottom = min(bitmap.height, box.bottom + expandY)

                val cropWidth = right - left
                val cropHeight = bottom - top

                if (cropWidth > 32 && cropHeight > 32) {
                    val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
                    val embedding = runMobileFaceNet(cropped)
                    // Ownership of `cropped` passes to the caller (recycled in
                    // PeopleRepository after avatar persist); do not recycle here.
                    results.add(
                        DetectedFaceResult(
                            faceBitmap = cropped,
                            featureVector = embedding,
                            boundingBox = Rect(left, top, right, bottom)
                        )
                    )
                }
            }
            // Recycle the downscaled working copy (caller recycles rawBitmap).
            try {
                if (bitmap !== rawBitmap) bitmap.recycle()
            } catch (_: Exception) {}
        } catch (t: Throwable) {
            Log.e(TAG, "detectFaces failed: ${t.message}", t)
        }
        return results
    }

    /**
     * Runs MobileFaceNet on a face crop, returns an L2-normalized 192-D
     * FloatArray. Input is resized to 112x112 and normalized to [-1, 1].
     * Synchronized: TFLite Interpreter is not thread-safe.
     */
    @Synchronized
    private fun runMobileFaceNet(faceBitmap: Bitmap): FloatArray {
        ensureShapesLogged()
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

        // [batch, 112, 112, 3] in RGB order, float32, range [-1, 1]
        val inputBuffer = ByteBuffer.allocateDirect(modelBatchSize * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16 and 0xFF) - 128f) / 128f)
            inputBuffer.putFloat(((pixel shr 8 and 0xFF) - 128f) / 128f)
            inputBuffer.putFloat(((pixel and 0xFF) - 128f) / 128f)
        }
        // Zero-pad the remaining batch slots if model expects >1
        for (i in 1 until modelBatchSize) {
            val floatsPerSlot = INPUT_SIZE * INPUT_SIZE * 3
            for (j in 0 until floatsPerSlot) inputBuffer.putFloat(0f)
        }
        inputBuffer.rewind()

        val output = Array(modelBatchSize) { FloatArray(EMBEDDING_SIZE) }
        interpreter.run(inputBuffer, output)
        return l2Normalize(output[0])
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        return RustFaceEngine.l2Normalize(v)
    }

    fun computeCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        return RustFaceEngine.cosineSimilarity(v1, v2)
    }
}
