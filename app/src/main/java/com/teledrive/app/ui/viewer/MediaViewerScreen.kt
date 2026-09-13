package com.teledrive.app.ui.viewer

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.Constants
import com.teledrive.app.core.FileUtils
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.telegram.TelegramBotApiEngine
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

/**
 * Ultra-smooth Media Viewer for Telegram Cloud & Local Gallery items.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UnifiedMediaViewerScreen(
    initialItem: UnifiedMediaItem,
    allItems: List<UnifiedMediaItem>,
    onBack: () -> Unit,
    onUpload: (UnifiedMediaItem) -> Unit = {},
    onDelete: (UnifiedMediaItem) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = TeleDriveApplication.instance

    val effectiveList = if (allItems.isEmpty()) listOf(initialItem) else allItems
    val initialIndex = remember(initialItem.id, allItems.size) {
        effectiveList.indexOfFirst { it.id == initialItem.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { effectiveList.size })

    val currentItem = effectiveList.getOrNull(pagerState.currentPage.coerceIn(0, effectiveList.size - 1)) ?: initialItem
    var showControls by remember { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Reset zoom state on page transition
    LaunchedEffect(pagerState.currentPage) {
        isCurrentZoomed = false
    }

    // Swipe down to dismiss with dynamic scale and rounded corners
    val offsetY = remember { Animatable(0f) }
    val dragScale by remember { derivedStateOf { (1f - (offsetY.value / 1200f)).coerceIn(0.6f, 1f) } }
    val dragAlpha by remember { derivedStateOf { (1f - (offsetY.value / 800f)).coerceIn(0.2f, 1f) } }
    val bgDimAlpha by remember { derivedStateOf { (1f - (offsetY.value / 600f)).coerceIn(0f, 1f) } }
    val dragCornerRadius by remember { derivedStateOf { (offsetY.value / 15f).coerceIn(0f, 28f).dp } }

    LaunchedEffect(pagerState.currentPage) {
        if (offsetY.value != 0f) {
            try { offsetY.snapTo(0f) } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgDimAlpha))
            .graphicsLayer {
                translationY = offsetY.value
                scaleX = dragScale
                scaleY = dragScale
                alpha = dragAlpha
            }
            .clip(RoundedCornerShape(dragCornerRadius))
    ) {
        HorizontalPager(
            state = pagerState,
            key = { idx -> effectiveList.getOrNull(idx)?.id ?: idx.toString() },
            userScrollEnabled = !isCurrentZoomed,
            pageSpacing = 16.dp,
            beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = effectiveList.getOrNull(page) ?: return@HorizontalPager
            val isCurrentPage = (pagerState.currentPage == page)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isCurrentZoomed) {
                        if (!isCurrentZoomed) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (dragAmount.y > 0 || offsetY.value > 0f) {
                                        if (offsetY.value > 0f || dragAmount.y > kotlin.math.abs(dragAmount.x) * 1.4f) {
                                            change.consume()
                                            scope.launch {
                                                offsetY.snapTo((offsetY.value + dragAmount.y).coerceAtLeast(0f))
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (offsetY.value > 260f) {
                                        onBack()
                                    } else {
                                        scope.launch {
                                            offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                    }
                                }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (item.isVideo) {
                    val thumbModel = remember(item.id, item.localUri, item.localPath) {
                        item.localUri ?: item.localPath?.let { java.io.File(it) }
                            ?: com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath(item.id)?.let { java.io.File(it) }
                    }
                    if (item.localUri != null) {
                        GalleryVideoPlayer(
                            uri = item.localUri,
                            filePath = null,
                            thumbnailModel = thumbModel,
                            durationMs = item.durationMs,
                            isCurrentPage = isCurrentPage,
                            onTap = { showControls = !showControls }
                        )
                    } else if (item.cloudFile != null) {
                        SingleCloudVideoPlayerPage(
                            file = item.cloudFile,
                            durationMs = item.durationMs,
                            isCurrentPage = isCurrentPage,
                            onTap = { showControls = !showControls }
                        )
                    }
                } else {
                    val cachedThumb = remember(item.id) {
                        com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath(item.id)?.let { java.io.File(it) }
                    }
                    val effectiveModel = item.localUri ?: item.localPath?.let { java.io.File(it) }
                    if (effectiveModel != null) {
                        SmoothZoomableImageViewer(
                            model = effectiveModel,
                            thumbnailModel = cachedThumb ?: effectiveModel,
                            thumbnailCacheKey = "local_${item.id}",
                            contentDescription = item.displayName,
                            onTap = { showControls = !showControls },
                            onZoomStateChanged = { zoomed ->
                                if (isCurrentPage) isCurrentZoomed = zoomed
                            }
                        )
                    } else if (item.cloudFile != null) {
                        SingleCloudImageViewerPage(
                            file = item.cloudFile,
                            onTap = { showControls = !showControls },
                            onZoomStateChanged = { zoomed ->
                                if (isCurrentPage) isCurrentZoomed = zoomed
                            }
                        )
                    }
                }
            }
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(animationSpec = tween(180)) { -it } + fadeIn(tween(180)),
            exit = slideOutVertically(animationSpec = tween(160)) { -it } + fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(bottom = 14.dp)
            ) {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatViewerDate(currentItem.dateModified),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = if (currentItem.isCloudBackedUp) "Telegram Cloud" else (currentItem.bucketName ?: "On this device"),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDetailsSheet = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Details", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        }

        // Bottom Action Bar Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(animationSpec = tween(180)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(animationSpec = tween(160)) { it } + fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = 0.95f)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Share Action
                    ViewerBottomAction(icon = Icons.Default.Share, label = "Share", onClick = {
                        shareMediaItem(context, currentItem)
                    })

                    // Edit Action
                    ViewerBottomAction(icon = Icons.Default.Edit, label = "Edit", onClick = {
                        editMediaItem(context, currentItem)
                    })

                    // Backup / Download Action
                    if (!currentItem.isCloudBackedUp) {
                        ViewerBottomAction(icon = Icons.Default.CloudUpload, label = "Backup", onClick = {
                            onUpload(currentItem)
                            Toast.makeText(context, "Backing up ${currentItem.displayName} to Cloud...", Toast.LENGTH_SHORT).show()
                        })
                    } else if (currentItem.cloudFile != null) {
                        ViewerBottomAction(icon = Icons.Default.Download, label = "Save", onClick = {
                            scope.launch {
                                app.transferManager.enqueueDownload(
                                    virtualPath = currentItem.cloudFile.virtualPath,
                                    fileName = currentItem.cloudFile.fileName,
                                    fileSize = currentItem.cloudFile.fileSize,
                                    chatId = currentItem.cloudFile.telegramChatId,
                                    messageId = currentItem.cloudFile.telegramMessageId
                                )
                                Toast.makeText(context, "Saving to local device...", Toast.LENGTH_SHORT).show()
                            }
                        })
                    } else {
                        ViewerBottomAction(icon = Icons.Default.CloudDone, label = "Backed Up", onClick = {
                            Toast.makeText(context, "Safely backed up to Telegram Cloud", Toast.LENGTH_SHORT).show()
                        })
                    }

                    // Info Sheet Action
                    ViewerBottomAction(icon = Icons.Default.Info, label = "Info", onClick = {
                        showDetailsSheet = true
                    })

                    // Trash Action
                    ViewerBottomAction(icon = Icons.Default.Delete, label = "Trash", onClick = {
                        showDeleteConfirmDialog = true
                    })
                }
            }
        }

        // Details Bottom Sheet
        if (showDetailsSheet) {
            MediaDetailsSheet(
                item = currentItem,
                onDismiss = { showDetailsSheet = false }
            )
        }

        // Move to Trash Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = Color(0xFF1E293B),
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
                title = {
                    Text("Move to Trash?", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "\"${currentItem.displayName}\" will be moved to Trash. You can restore it anytime from Trash in Collections.",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDelete(currentItem)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Text("Move to Trash", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }
    }
}

/**
 * Fluid Zoomable Image Viewer with progressive two-tier loading:
 * 1. Tier 1: Instant Low-Res Thumbnail (synchronous hit from Coil Memory Cache, 0ms latency, zero black flash).
 * 2. Tier 2: Crystal-Clear Full-Resolution Photo (smooth crossfade on background decode completion).
 * 3. Exact tap-point-anchored double-tap zoom that smoothly magnifies directly at the touched coordinate
 *    instead of jumping to a corner, with dynamic aspect-ratio boundary clamping and fluid pinch gestures.
 */
@Composable
fun SmoothZoomableImageViewer(
    model: Any?,
    thumbnailModel: Any? = null,
    thumbnailCacheKey: String? = null,
    contentDescription: String?,
    onTap: () -> Unit,
    onZoomStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var isFullImageLoaded by remember(model) { mutableStateOf(false) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var intrinsicWidth by remember(model) { mutableFloatStateOf(0f) }
    var intrinsicHeight by remember(model) { mutableFloatStateOf(0f) }

    val viewWidth = if (containerSize.width > 0) containerSize.width.toFloat() else 1080f
    val viewHeight = if (containerSize.height > 0) containerSize.height.toFloat() else 2400f
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // Calculate effective fitted dimensions of the image inside the container
    val fittedDimensions = remember(intrinsicWidth, intrinsicHeight, viewWidth, viewHeight) {
        if (intrinsicWidth > 0f && intrinsicHeight > 0f) {
            val imgAspect = intrinsicWidth / intrinsicHeight
            val viewAspect = viewWidth / viewHeight
            if (imgAspect > viewAspect) {
                // Constrained by width
                val fw = viewWidth
                val fh = viewWidth / imgAspect
                Pair(fw, fh)
            } else {
                // Constrained by height
                val fh = viewHeight
                val fw = (viewHeight * imgAspect).coerceAtMost(viewWidth)
                Pair(fw, fh)
            }
        } else {
            Pair(viewWidth, viewHeight)
        }
    }
    val fittedWidth = fittedDimensions.first
    val fittedHeight = fittedDimensions.second

    fun calculateMaxPan(scale: Float): Pair<Float, Float> {
        val maxX = ((scale * fittedWidth - viewWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((scale * fittedHeight - viewHeight) / 2f).coerceAtLeast(0f)
        return Pair(maxX, maxY)
    }

    // Notify parent pager when zoom state changes
    val isZoomed = scaleAnim.value > 1.05f
    LaunchedEffect(isZoomed) {
        onZoomStateChanged?.invoke(isZoomed)
    }

    // Spring back smoothly if pinch-to-zoom was released below 1.0x or panned out of bounds
    LaunchedEffect(scaleAnim.value) {
        val currentScale = scaleAnim.value
        if (currentScale < 1.0f && !scaleAnim.isRunning) {
            launch { scaleAnim.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
            launch { offsetXAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            launch { offsetYAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        } else if (currentScale >= 1.0f && !scaleAnim.isRunning) {
            val (maxPanX, maxPanY) = calculateMaxPan(currentScale)
            if (offsetXAnim.value !in -maxPanX..maxPanX) {
                launch { offsetXAnim.animateTo(offsetXAnim.value.coerceIn(-maxPanX, maxPanX), spring(stiffness = Spring.StiffnessMediumLow)) }
            }
            if (offsetYAnim.value !in -maxPanY..maxPanY) {
                launch { offsetYAnim.animateTo(offsetYAnim.value.coerceIn(-maxPanY, maxPanY), spring(stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(model) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        scope.launch {
                            if (scaleAnim.value > 1.1f) {
                                // Smoothly zoom out to 1.0x centered
                                launch { scaleAnim.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetXAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetYAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                            } else {
                                // Smoothly zoom in centered directly on the tapped position
                                val targetScale = 2.75f
                                val diffX = tapOffset.x - centerX
                                val diffY = tapOffset.y - centerY

                                // Keep the tapped position pinned under the user's touch
                                val targetX = -diffX * (targetScale - 1f)
                                val targetY = -diffY * (targetScale - 1f)

                                val (maxPanX, maxPanY) = calculateMaxPan(targetScale)
                                val clampedX = targetX.coerceIn(-maxPanX, maxPanX)
                                val clampedY = targetY.coerceIn(-maxPanY, maxPanY)

                                launch { scaleAnim.animateTo(targetScale, tween(280, easing = FastOutSlowInEasing)) }
                                launch { offsetXAnim.animateTo(clampedX, tween(280, easing = FastOutSlowInEasing)) }
                                launch { offsetYAnim.animateTo(clampedY, tween(280, easing = FastOutSlowInEasing)) }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(model, fittedWidth, fittedHeight) {
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        val currentScale = scaleAnim.value
                        if (zoom != 1f || currentScale > 1.05f) {
                            val newScale = (currentScale * zoom).coerceIn(0.85f, 5.0f)
                            scope.launch { scaleAnim.snapTo(newScale) }

                            val (maxPanX, maxPanY) = calculateMaxPan(newScale)
                            if (newScale > 1f) {
                                val centroidFromCenter = Offset(centroid.x - centerX, centroid.y - centerY)
                                val zoomFactor = if (currentScale > 0.001f) newScale / currentScale else 1f
                                val zoomOffsetDelta = -(centroidFromCenter - Offset(offsetXAnim.value, offsetYAnim.value)) * (zoomFactor - 1f)

                                val newOffsetX = (offsetXAnim.value + pan.x + zoomOffsetDelta.x).coerceIn(-maxPanX, maxPanX)
                                val newOffsetY = (offsetYAnim.value + pan.y + zoomOffsetDelta.y).coerceIn(-maxPanY, maxPanY)

                                scope.launch {
                                    offsetXAnim.snapTo(newOffsetX)
                                    offsetYAnim.snapTo(newOffsetY)
                                }
                            } else {
                                scope.launch {
                                    offsetXAnim.snapTo(0f)
                                    offsetYAnim.snapTo(0f)
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Tier 1: Instant Low-Res Thumbnail Layer (0ms, synchronous from Coil Memory Cache!)
            // Stays visible underneath while Tier 2 loads to eliminate any black flash / jitter.
            val thumbAlpha by animateFloatAsState(
                targetValue = if (isFullImageLoaded) 0f else 1f,
                animationSpec = tween(200),
                label = "thumbAlpha"
            )

            if (thumbnailModel != null && thumbAlpha > 0f) {
                val thumbReq = remember(thumbnailModel, thumbnailCacheKey) {
                    val builder = ImageRequest.Builder(context)
                        .data(thumbnailModel)
                        .size(360)
                        .crossfade(false)
                        .allowHardware(true)
                        .listener(
                            onSuccess = { _, result ->
                                if (intrinsicWidth <= 0f) {
                                    intrinsicWidth = result.drawable.intrinsicWidth.toFloat()
                                    intrinsicHeight = result.drawable.intrinsicHeight.toFloat()
                                }
                            }
                        )
                    if (thumbnailCacheKey != null) {
                        builder.memoryCacheKey(thumbnailCacheKey).diskCacheKey(thumbnailCacheKey)
                    }
                    builder.build()
                }
                AsyncImage(
                    model = thumbReq,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scaleAnim.value
                            scaleY = scaleAnim.value
                            translationX = offsetXAnim.value
                            translationY = offsetYAnim.value
                            alpha = thumbAlpha
                        }
                )
            }

            // Tier 2: Crystal-Clear Full Resolution Photo (smooth crossfade on background load completion)
            val fullReq = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(180)
                    .allowHardware(true)
                    .listener(
                        onSuccess = { _: ImageRequest, result: SuccessResult ->
                            intrinsicWidth = result.drawable.intrinsicWidth.toFloat()
                            intrinsicHeight = result.drawable.intrinsicHeight.toFloat()
                            isFullImageLoaded = true
                        }
                    )
                    .build()
            }
            AsyncImage(
                model = fullReq,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value
                    }
            )
        }
    }
}

/**
 * Feature-rich native Gallery Video Player with play/pause center icon, seekbar, time stamps, mute, and loop controls.
 */
@Composable
fun GalleryVideoPlayer(
    uri: Uri?,
    filePath: String?,
    thumbnailModel: Any? = null,
    durationMs: Long = 0L,
    isCurrentPage: Boolean,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var exoPlayer by remember(uri, filePath) { mutableStateOf<ExoPlayer?>(null) }
    var isVideoReady by remember(uri, filePath) { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage, uri, filePath) {
        if (!isCurrentPage) {
            try { exoPlayer?.pause() } catch (_: Exception) {}
            return@LaunchedEffect
        }
        try {
            var player = exoPlayer
            if (player == null) {
                player = ExoPlayer.Builder(context).build().apply {
                    repeatMode = ExoPlayer.REPEAT_MODE_ONE
                    playWhenReady = false
                }
                exoPlayer = player
            }
            val mediaItem = if (uri != null) {
                MediaItem.fromUri(uri)
            } else if (filePath != null) {
                MediaItem.fromUri(Uri.fromFile(File(filePath)))
            } else null
            if (mediaItem != null) {
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
        } catch (_: Exception) {}
    }

    DisposableEffect(uri, filePath) {
        onDispose {
            try { exoPlayer?.pause() } catch (_: Exception) {}
            try { exoPlayer?.release() } catch (_: Exception) {}
            exoPlayer = null
            isVideoReady = false
        }
    }

    val player = exoPlayer
    if (player == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailModel)
                        .crossfade(false)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            if (durationMs > 0L) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 90.dp, end = 20.dp)
                ) {
                    Text(
                        text = formatDuration(durationMs),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        return
    }

    var isPlaying by remember(uri, filePath) { mutableStateOf(false) }
    var currentPositionMs by remember(uri, filePath) { mutableLongStateOf(0L) }
    var totalDurationMs by remember(uri, filePath) { mutableLongStateOf(durationMs) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(true) }
    var showPlayerControls by remember(uri, filePath) { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    // Play/Pause follows page visibility (player exists only when current).
    LaunchedEffect(isCurrentPage, player) {
        try {
            if (isCurrentPage) {
                player.play()
            } else {
                player.pause()
                isPlaying = false
            }
        } catch (_: Exception) {}
    }

    // Listener for state updates
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isVideoReady = true
                    try {
                        val dur = player.duration
                        if (dur > 0L) totalDurationMs = dur
                    } catch (_: Exception) {}
                }
            }
        }
        player.addListener(listener)
        onDispose {
            try { player.removeListener(listener) } catch (_: Exception) {}
        }
    }

    // Live progress polling loop (only while playing: no work when paused).
    LaunchedEffect(isPlaying, isScrubbing, player) {
        while (isPlaying && !isScrubbing) {
            try {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration
                if (dur > 0L) totalDurationMs = dur
            } catch (_: Exception) {}
            delay(120)
        }
    }

    // Auto-hide controls after 3.5 seconds of playing
    LaunchedEffect(showPlayerControls, isPlaying) {
        if (showPlayerControls && isPlaying && !isScrubbing) {
            delay(3500)
            showPlayerControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                showPlayerControls = !showPlayerControls
                onTap()
            },
        contentAlignment = Alignment.Center
    ) {
        // Instant thumbnail layer underneath PlayerView to prevent black flash while buffering/preparing
        if (thumbnailModel != null) {
            val thumbAlpha by animateFloatAsState(
                targetValue = if (isVideoReady && isPlaying) 0f else 1f,
                animationSpec = tween(250),
                label = "videoThumbAlpha"
            )
            if (thumbAlpha > 0f) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailModel)
                        .crossfade(false)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = thumbAlpha }
                )
            }
        }

        // ExoPlayer View
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    useController = false
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize()
        )

        // Center Play/Pause Button Overlay
        AnimatedVisibility(
            visible = showPlayerControls || !isPlaying,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(68.dp)
                    .clickable {
                        try {
                            if (isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        } catch (_: Exception) {}
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }

        // Bottom Timeline & Controller Bar Overlay
        AnimatedVisibility(
            visible = showPlayerControls || !isPlaying,
            enter = slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Seekbar row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(if (isScrubbing) scrubPositionMs.toLong() else currentPositionMs),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Slider(
                        value = (if (isScrubbing) scrubPositionMs else currentPositionMs.toFloat()).coerceIn(0f, totalDurationMs.coerceAtLeast(1L).toFloat()),
                        onValueChange = {
                            isScrubbing = true
                            scrubPositionMs = it
                        },
                        onValueChangeFinished = {
                            try {
                                player.seekTo(scrubPositionMs.toLong())
                            } catch (_: Exception) {}
                            currentPositionMs = scrubPositionMs.toLong()
                            isScrubbing = false
                        },
                        valueRange = 0f..totalDurationMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF38BDF8),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                    Text(
                        text = formatDuration(totalDurationMs),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Quick Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        try {
                            val newPos = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                            player.seekTo(newPos)
                            currentPositionMs = newPos
                        } catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                    }

                    IconButton(onClick = {
                        try {
                            if (isPlaying) player.pause() else player.play()
                        } catch (_: Exception) {}
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = {
                        try {
                            val newPos = (player.currentPosition + 10_000L).coerceAtMost(totalDurationMs)
                            player.seekTo(newPos)
                            currentPositionMs = newPos
                        } catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                    }

                    IconButton(onClick = {
                        isMuted = !isMuted
                        try {
                            player.volume = if (isMuted) 0f else 1f
                        } catch (_: Exception) {}
                    }) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = if (isMuted) Color(0xFFEF4444) else Color.White
                        )
                    }

                    IconButton(onClick = {
                        isLooping = !isLooping
                        try {
                            player.repeatMode = if (isLooping) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF
                        } catch (_: Exception) {}
                    }) {
                        Icon(
                            imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Loop",
                            tint = if (isLooping) Color(0xFF38BDF8) else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cloud Image Viewer that rehydrates / downloads via TDLib and delegates to SmoothZoomableImageViewer.
 */
@Composable
private fun SingleCloudImageViewerPage(
    file: FileEntity,
    onTap: () -> Unit,
    onZoomStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val tdLibManager = app.tdLibManager
    var localPath by remember(file.fileId) { mutableStateOf<String?>(null) }
    var isLoading by remember(file.fileId) { mutableStateOf(true) }
    var errorMessage by remember(file.fileId) { mutableStateOf<String?>(null) }
    var downloadProgress by remember(file.fileId) { mutableFloatStateOf(0f) }
    var statusText by remember(file.fileId) { mutableStateOf("Loading high-res...") }

    LaunchedEffect(file.fileId, file.telegramFileId, file.telegramMessageId) {
        isLoading = true
        errorMessage = null
        localPath = null
        downloadProgress = 0f
        statusText = "Loading high-res..."

        val hitPath: String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cached = File(context.cacheDir, file.fileName)
            if (cached.exists() && cached.length() > 0) return@withContext cached.absolutePath
            try {
                val downloaded = File(FileUtils.getDownloadDir(context), file.fileName)
                if (downloaded.exists() && downloaded.length() > 0) return@withContext downloaded.absolutePath
            } catch (_: Exception) {}
            if (file.telegramFileId != 0) {
                try {
                    val tdFile = tdLibManager.getFile(file.telegramFileId)
                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                        return@withContext tdFile.local.path
                    }
                } catch (_: Exception) {}
            }
            null
        }

        if (hitPath != null) {
            localPath = hitPath
            isLoading = false
            return@LaunchedEffect
        }

        try {
            val chatId = if (file.telegramChatId != 0L) file.telegramChatId else tdLibManager.getSavedMessagesChatId()
            val path = tdLibManager.rehydrateAndDownloadFile(
                chatId = chatId,
                messageId = file.telegramMessageId,
                preferredFileId = file.telegramFileId,
                priority = 32,
                onProgress = { downloaded, total ->
                    if (total > 0) {
                        val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        downloadProgress = progress
                        statusText = "Downloading ${(progress * 100).toInt()}%"
                    }
                }
            )
            if (path.isNotEmpty() && File(path).exists()) {
                localPath = path
                isLoading = false
                return@LaunchedEffect
            }
        } catch (_: Exception) {}

        // Fallback: Telegram Bot API direct download if available
        try {
            val botToken = app.preferences.getBotTokenSync() ?: Constants.DEFAULT_BOT_TOKEN
            val botFileId = TelegramBotApiEngine.getPersistedBotFileId(context, file.telegramMessageId)
            if (botFileId != null && botToken.isNotBlank()) {
                statusText = "Downloading via Bot API..."
                val dest = File(context.cacheDir, file.fileName)
                val botResult = TelegramBotApiEngine.downloadFile(
                    botToken = botToken,
                    fileId = botFileId,
                    destFile = dest,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            downloadProgress = progress
                            statusText = "Downloading ${(progress * 100).toInt()}%"
                        }
                    }
                )
                if (botResult.isSuccess && dest.exists()) {
                    localPath = dest.absolutePath
                    isLoading = false
                    return@LaunchedEffect
                }
            }
        } catch (_: Exception) {}

        errorMessage = "Failed to download image"
        isLoading = false
    }

    val cachedThumb = remember(file.fileId, file.telegramFileId) {
        app.thumbnailCacheManager.getFastCachedPath(file)
            ?: com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath("cloud_${file.fileId}")
    }

    if (localPath != null) {
        SmoothZoomableImageViewer(
            model = File(localPath!!),
            thumbnailModel = if (cachedThumb != null) File(cachedThumb) else null,
            contentDescription = file.fileName,
            onTap = onTap,
            onZoomStateChanged = onZoomStateChanged
        )
    } else if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cachedThumb != null) {
                SmoothZoomableImageViewer(
                    model = File(cachedThumb),
                    thumbnailModel = File(cachedThumb),
                    contentDescription = file.fileName,
                    onTap = onTap,
                    onZoomStateChanged = onZoomStateChanged
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(statusText, color = Color.White, fontSize = 12.sp)
                    }
                }
            } else {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(36.dp))
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage ?: "Unable to load photo", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

/**
 * Cloud Video Player that rehydrates / downloads via TDLib and delegates to GalleryVideoPlayer.
 * Supports direct Bot API progressive streaming and real-time TDLib download progress.
 */
@Composable
private fun SingleCloudVideoPlayerPage(
    file: FileEntity,
    durationMs: Long = 0L,
    isCurrentPage: Boolean,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val tdLibManager = app.tdLibManager
    var localPath by remember(file.fileId) { mutableStateOf<String?>(null) }
    var streamUri by remember(file.fileId) { mutableStateOf<Uri?>(null) }
    var isLoading by remember(file.fileId) { mutableStateOf(true) }
    var errorMessage by remember(file.fileId) { mutableStateOf<String?>(null) }
    var downloadProgress by remember(file.fileId) { mutableFloatStateOf(0f) }
    var statusText by remember(file.fileId) { mutableStateOf("Streaming from Telegram Cloud...") }

    val cachedThumb = remember(file.fileId) {
        app.thumbnailCacheManager.getFastCachedPath(file)
            ?: com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath("cloud_${file.fileId}")
    }

    LaunchedEffect(file.fileId, file.telegramFileId, file.telegramMessageId) {
        isLoading = true
        errorMessage = null
        localPath = null
        streamUri = null
        downloadProgress = 0f

        // 1. Check local cached or downloaded file
        val hitPath: String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cached = File(context.cacheDir, file.fileName)
            if (cached.exists() && cached.length() > 0) return@withContext cached.absolutePath
            try {
                val downloaded = File(FileUtils.getDownloadDir(context), file.fileName)
                if (downloaded.exists() && downloaded.length() > 0) return@withContext downloaded.absolutePath
            } catch (_: Exception) {}
            if (file.telegramFileId != 0) {
                try {
                    val tdFile = tdLibManager.getFile(file.telegramFileId)
                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                        return@withContext tdFile.local.path
                    }
                } catch (_: Exception) {}
            }
            null
        }

        if (hitPath != null) {
            localPath = hitPath
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Try fast progressive streaming via Bot API stream URL if available
        val botToken = app.preferences.getBotTokenSync() ?: Constants.DEFAULT_BOT_TOKEN
        val botFileId = TelegramBotApiEngine.getPersistedBotFileId(context, file.telegramMessageId)
        if (botFileId != null && botToken.isNotBlank()) {
            val liveStreamUrl = TelegramBotApiEngine.getStreamUrl(botToken, botFileId)
            if (liveStreamUrl != null) {
                streamUri = Uri.parse(liveStreamUrl)
                isLoading = false
            }
        }

        // 3. Download/buffer via TDLib with real-time progress
        try {
            val chatId = if (file.telegramChatId != 0L) file.telegramChatId else tdLibManager.getSavedMessagesChatId()
            val path = tdLibManager.rehydrateAndDownloadFile(
                chatId = chatId,
                messageId = file.telegramMessageId,
                preferredFileId = file.telegramFileId,
                priority = 32,
                onProgress = { downloaded, total ->
                    if (total > 0) {
                        val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        downloadProgress = progress
                        statusText = "Streaming ${(progress * 100).toInt()}% • ${FileUtils.formatFileSize(downloaded)} / ${FileUtils.formatFileSize(total)}"
                    } else {
                        statusText = "Streaming ${FileUtils.formatFileSize(downloaded)}..."
                    }
                }
            )
            if (path.isNotEmpty() && File(path).exists()) {
                localPath = path
                isLoading = false
                return@LaunchedEffect
            }
        } catch (_: Exception) {}

        // 4. If TDLib failed and no streamUri yet, fallback to Bot API download
        if (streamUri == null && botFileId != null && botToken.isNotBlank()) {
            try {
                statusText = "Buffering via Telegram Bot API..."
                val dest = File(context.cacheDir, file.fileName)
                val botResult = TelegramBotApiEngine.downloadFile(
                    botToken = botToken,
                    fileId = botFileId,
                    destFile = dest,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            downloadProgress = progress
                            statusText = "Buffering ${(progress * 100).toInt()}% • ${FileUtils.formatFileSize(downloaded)} / ${FileUtils.formatFileSize(total)}"
                        }
                    }
                )
                if (botResult.isSuccess && dest.exists()) {
                    localPath = dest.absolutePath
                    isLoading = false
                    return@LaunchedEffect
                }
            } catch (_: Exception) {}
        }

        if (streamUri == null && localPath == null) {
            errorMessage = "Unable to stream or download video"
        }
        isLoading = false
    }

    if (localPath != null || streamUri != null) {
        GalleryVideoPlayer(
            uri = streamUri,
            filePath = localPath,
            thumbnailModel = if (cachedThumb != null) File(cachedThumb) else null,
            durationMs = durationMs,
            isCurrentPage = isCurrentPage,
            onTap = onTap
        )
    } else if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cachedThumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(cachedThumb))
                        .crossfade(false)
                        .allowHardware(true)
                        .build(),
                    contentDescription = file.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (downloadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            color = Color(0xFF38BDF8),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(statusText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage ?: "Unable to stream video", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

/**
 * Detailed Information Bottom Sheet for photos & videos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(
    item: UnifiedMediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Details", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

            DetailRow(icon = Icons.Default.Description, label = "File Name", value = item.displayName)
            DetailRow(
                icon = Icons.Default.CalendarToday,
                label = "Date Taken",
                value = formatViewerDate(item.dateModified) + " • " + formatViewerTime(item.dateModified)
            )
            DetailRow(
                icon = Icons.Default.DataUsage,
                label = "File Size",
                value = formatBytes(item.fileSize)
            )
            DetailRow(
                icon = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                label = "Media Type",
                value = if (item.isVideo) "Video (${formatDuration(item.durationMs)})" else "Image (${item.mimeType})"
            )
            DetailRow(
                icon = Icons.Default.Folder,
                label = "Album / Bucket",
                value = item.bucketName ?: "Camera"
            )
            DetailRow(
                icon = Icons.Default.Cloud,
                label = "Storage Status",
                value = if (item.isCloudBackedUp) "Backed Up to Telegram Cloud" else "Local Device Storage"
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(item.displayName))
                    Toast.makeText(context, "Copied file name to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy File Name")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 11.sp, color = Color.Gray)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

@Composable
private fun ViewerBottomAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun shareMediaItem(context: Context, item: UnifiedMediaItem) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            item.localUri?.let {
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share ${item.displayName}"))
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to share item: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun editMediaItem(context: Context, item: UnifiedMediaItem) {
    try {
        item.localUri?.let { uri ->
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(editIntent, "Edit Media"))
        } ?: run {
            Toast.makeText(context, "Download from cloud first to edit", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "No editor app available", Toast.LENGTH_SHORT).show()
    }
}

private fun formatViewerDate(timestamp: Long): String {
    val date = Date(if (timestamp > 0) timestamp else System.currentTimeMillis())
    return SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
}

private fun formatViewerTime(timestamp: Long): String {
    val date = Date(if (timestamp > 0) timestamp else System.currentTimeMillis())
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "00:00"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

/**
 * Backward compatibility overload for legacy FileEntity items.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    initialItem: FileEntity,
    allItems: List<FileEntity>,
    onBack: () -> Unit,
    onDelete: (FileEntity) -> Unit = {}
) {
    val convertedInitial = UnifiedMediaItem(
        id = "tg_${initialItem.fileId}",
        displayName = initialItem.fileName,
        fileSize = initialItem.fileSize,
        dateModified = initialItem.uploadTimestamp,
        mimeType = initialItem.mimeType,
        isVideo = initialItem.mimeType.startsWith("video/") || initialItem.fileName.endsWith(".mp4", true),
        isCloudBackedUp = true,
        cloudFile = initialItem
    )
    val convertedList = allItems.map {
        UnifiedMediaItem(
            id = "tg_${it.fileId}",
            displayName = it.fileName,
            fileSize = it.fileSize,
            dateModified = it.uploadTimestamp,
            mimeType = it.mimeType,
            isVideo = it.mimeType.startsWith("video/") || it.fileName.endsWith(".mp4", true),
            isCloudBackedUp = true,
            cloudFile = it
        )
    }

    UnifiedMediaViewerScreen(
        initialItem = convertedInitial,
        allItems = convertedList,
        onBack = onBack,
        onDelete = { onDelete(initialItem) }
    )
}
