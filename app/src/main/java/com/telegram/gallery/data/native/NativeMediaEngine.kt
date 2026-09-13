package com.telegram.gallery.data.native

import android.util.Log

object NativeMediaEngine {
    private var isCppLoaded = false

    init {
        try {
            System.loadLibrary("native_media")
            isCppLoaded = true
            Log.i("NativeMediaEngine", "C++ Native Media Engine library loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeMediaEngine", "Failed to load C++ native_media library", e)
        }
    }

    external fun getNativeVersion(): String
    external fun processFastBilinearScale(
        inputPixels: ByteArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): ByteArray
    external fun computeFastMediaHash(data: ByteArray): Long

    fun isNativeLoaded(): Boolean = isCppLoaded
}
