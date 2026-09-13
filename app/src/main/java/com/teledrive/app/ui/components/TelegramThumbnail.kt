package com.teledrive.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TelegramThumbnail(
    file: FileEntity,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val thumbManager = TeleDriveApplication.instance.thumbnailCacheManager

    // Stable string key: the previous Array<Any> key used referential equality,
    // so LaunchedEffect restarted on EVERY recomposition -> fetch storm + jank.
    val cacheKey = remember(file.fileId, file.telegramFileId, file.thumbnailFileId, file.fileName) {
        "${file.fileId}_${file.telegramFileId}_${file.thumbnailFileId}_${file.fileName}"
    }

    // Synchronous fast path on the composition thread (RAM + 2-3 file stats,
    // ~microseconds each). The old code started null and resolved via IO, so
    // EVERY scroll-back flashed empty for a frame even for cached thumbs, then
    // recomposed when IO finished — that flash + double-compose is the jitter.
    // Cached tiles now compose the image on the FIRST frame with zero extra work.
    var localPath by remember(cacheKey) {
        mutableStateOf(thumbManager.getFastCachedPath(file))
    }
    // didResolve=false only while a network fetch may still be outstanding.
    // Disk/RAM hits resolve synchronously above (no flash). True misses show
    // the flat background until the fetch lands; permanent misses (non-media)
    // show the icon AFTER the fetch fails (not a flash — first paint).
    var didResolveInitial by remember(cacheKey) {
        mutableStateOf(localPath != null)
    }
    // One-shot stale-path retry (prune vs stat race): if Coil fails to read a
    // path that stat'd OK, evict RAM and fetch once. Guarded so it can't loop.
    var retriedStale by remember(cacheKey) { mutableStateOf(false) }

    // Keyed on retriedStale as well: onError evicts RAM and nulls the path to
    // trigger ONE refetch. Without the key the effect would never restart and
    // the tile would stick on the fallback icon despite the image existing.
    // Conditional: cached tiles (the common scroll case) launch NO coroutine at
    // all — previously every tile composition paid a coroutine + dispatcher hop.
    if (localPath == null && !didResolveInitial) {
        LaunchedEffect(cacheKey, retriedStale) {
            val fetched = withContext(Dispatchers.IO) {
                thumbManager.getOrFetchThumbnail(file)
            }
            if (!fetched.isNullOrEmpty()) {
                localPath = fetched
            }
            didResolveInitial = true
        }
    }

    val activePath = localPath
    // Existence was already validated inside the manager; avoid a second
    // File.exists() stat on every recomposition (was a per-frame I/O stall).
    val hasImage = !activePath.isNullOrEmpty()

    // No background() here: every caller already paints its own placeholder
    // color behind us (tile 0xFF16161D, avatar circle, quadrant cell). An inner
    // solid fill would overdraw the full tile only to be covered by the image.
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (hasImage) {
            // Stable request: fixed size + memoryCacheKey prevents Coil from
            // re-decoding on every scroll, and crossfade=false kills the
            // placeholder->image flash that reads as flicker in grids.
            // placeholder(null) keeps the flat Box background while Coil decodes
            // from disk (no icon flash); error falls back to the icon below via
            // onError retry, not a second composition storm.
            val request = remember(activePath) {
                ImageRequest.Builder(context)
                    .data(File(activePath!!))
                    .size(360)
                    .memoryCacheKey("thumb_$cacheKey")
                    .diskCacheKey("thumb_$cacheKey")
                    .allowHardware(true)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = file.fileName,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    // Stale RAM/disk path (pruned between stat and decode): evict
                    // once and fetch fresh instead of showing a permanent icon
                    // for an image that exists on the server.
                    if (!retriedStale) {
                        retriedStale = true
                        thumbManager.evictRamEntry(file)
                        localPath = null
                        didResolveInitial = false
                    }
                }
            )
        } else if (!didResolveInitial) {
            // Keep the flat background while resolving — no icon flash.
            Box(modifier = Modifier.fillMaxSize())
        } else {
            FileIcon(
                mimeType = file.mimeType,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
            )
        }
    }
}
