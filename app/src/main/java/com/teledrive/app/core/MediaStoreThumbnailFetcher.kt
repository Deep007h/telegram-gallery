package com.teledrive.app.core

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import kotlin.coroutines.coroutineContext

/**
 * High-performance Coil Fetcher that intercepts MediaStore Uris for thumbnail requests
 * (<= 720px) and loads them via Android's hardware-accelerated ContentResolver.loadThumbnail().
 *
 * Employs Semaphore(4) to eliminate Binder buffer exhaustion and IPC contention,
 * and attaches CancellationSignal to coroutine cancellation so off-screen tiles abort immediately.
 */
class MediaStoreThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    companion object {
        // Enforce maximum 4 concurrent Binder IPC transactions into system MediaProvider
        private val ipcSemaphore = Semaphore(4)
    }

    override suspend fun fetch(): FetchResult? = withContext(AppDispatchers.ImageLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val authority = uri.authority
            if (authority == MediaStore.AUTHORITY || authority == "media") {
                val reqW = options.size.width.pxOrElse { 360 }.coerceIn(120, 1080)
                val reqH = options.size.height.pxOrElse { 360 }.coerceIn(120, 1080)

                // Intercept thumbnail-sized requests up to 1080px. Full-resolution requests
                // (> 1080px) bypass this fetcher to decode the full-resolution photo.
                if (reqW <= 1080 && reqH <= 1080) {
                    val signal = CancellationSignal()
                    coroutineContext[Job]?.invokeOnCompletion {
                        try { signal.cancel() } catch (_: Exception) {}
                    }

                    try {
                        ipcSemaphore.acquire()
                        try {
                            val bitmap = context.contentResolver.loadThumbnail(
                                uri,
                                Size(reqW, reqH),
                                signal
                            )
                            if (bitmap != null) {
                                return@withContext DrawableResult(
                                    drawable = BitmapDrawable(context.resources, bitmap),
                                    isSampled = true,
                                    dataSource = DataSource.DISK
                                )
                            }
                        } finally {
                            ipcSemaphore.release()
                        }
                    } catch (_: Exception) {
                        // Fall back to standard Coil pipeline if MediaStore thumbnail fails
                    }
                }
            }
        }
        null
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val authority = data.authority
                if (authority == MediaStore.AUTHORITY || authority == "media") {
                    val reqW = options.size.width.pxOrElse { 360 }
                    val reqH = options.size.height.pxOrElse { 360 }
                    if (reqW <= 1080 && reqH <= 1080) {
                        return MediaStoreThumbnailFetcher(context, data, options)
                    }
                }
            }
            return null
        }
    }
}
