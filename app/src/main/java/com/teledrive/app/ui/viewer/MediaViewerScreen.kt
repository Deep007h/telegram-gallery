package com.teledrive.app.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.FileUtils
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

/**
 * Ultra-smooth Media Viewer for Telegram Cloud & Local Gallery items.
 * Uses direct TDLib progressive download/streaming architecture with rehydration.
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
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Swipe down to dismiss
    val offsetY = remember { Animatable(0f) }
    val dragScale by remember { derivedStateOf { (1f - (offsetY.value / 1200f)).coerceIn(0.55f, 1f) } }
    val dragAlpha by remember { derivedStateOf { (1f - (offsetY.value / 800f)).coerceIn(0.2f, 1f) } }
    val bgDimAlpha by remember { derivedStateOf { (1f - (offsetY.value / 600f)).coerceIn(0f, 1f) } }

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
    ) {
        HorizontalPager(
            state = pagerState,
            key = { idx -> effectiveList.getOrNull(idx)?.id ?: idx.toString() },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = effectiveList.getOrNull(page) ?: return@HorizontalPager
            val isCurrentPage = (pagerState.currentPage == page)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0 || offsetY.value > 0) {
                                    scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                                }
                            },
                            onDragEnd = {
                                if (offsetY.value > 200f) {
                                    onBack()
                                } else {
                                    scope.launch { offsetY.animateTo(0f, tween(150)) }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetY.animateTo(0f, tween(150)) }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (item.isVideo) {
                    if (item.localUri != null) {
                        SingleLocalVideoPlayerPage(
                            uri = item.localUri,
                            isCurrentPage = isCurrentPage,
                            onTap = { showControls = !showControls }
                        )
                    } else if (item.localPath != null && File(item.localPath).exists()) {
                        SingleLocalVideoPlayerPage(
                            uri = Uri.fromFile(File(item.localPath)),
                            isCurrentPage = isCurrentPage,
                            onTap = { showControls = !showControls }
                        )
                    } else if (item.cloudFile != null) {
                        SingleVideoPlayerPage(
                            file = item.cloudFile,
                            isCurrentPage = isCurrentPage,
                            onTap = { showControls = !showControls }
                        )
                    }
                } else {
                    if (item.localUri != null) {
                        SingleLocalImageViewerPage(
                            uri = item.localUri,
                            onTap = { showControls = !showControls }
                        )
                    } else if (item.localPath != null && File(item.localPath).exists()) {
                        SingleLocalImageViewerPage(
                            uri = Uri.fromFile(File(item.localPath)),
                            onTap = { showControls = !showControls }
                        )
                    } else if (item.cloudFile != null) {
                        SingleImageViewerPage(
                            file = item.cloudFile,
                            onTap = { showControls = !showControls }
                        )
                    }
                }
            }
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.9f),
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatViewerDate(currentItem.dateModified),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (currentItem.isCloudBackedUp) "Telegram Cloud" else (currentItem.bucketName ?: "On this device"),
                                fontSize = 11.sp,
                                color = Color.LightGray
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
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100)),
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
                                Color.Black.copy(alpha = 0.9f)
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
                    ViewerBottomAction(icon = Icons.Default.Share, label = "Share", onClick = {
                        shareMediaItem(context, currentItem)
                    })

                    ViewerBottomAction(icon = Icons.Default.Edit, label = "Edit", onClick = {
                        editMediaItem(context, currentItem)
                    })

                    if (!currentItem.isCloudBackedUp) {
                        ViewerBottomAction(icon = Icons.Default.CloudUpload, label = "Backup", onClick = {
                            onUpload(currentItem)
                            Toast.makeText(context, "Uploading ${currentItem.displayName} to Cloud...", Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(context, "Downloading ${currentItem.displayName} to device...", Toast.LENGTH_SHORT).show()
                            }
                        })
                    } else {
                        ViewerBottomAction(icon = Icons.Default.CloudDone, label = "Backed Up", onClick = {
                            Toast.makeText(context, "Safely stored on Telegram Cloud", Toast.LENGTH_SHORT).show()
                        })
                    }

                    ViewerBottomAction(icon = Icons.Default.Info, label = "Details", onClick = {
                        showDetailsSheet = true
                    })

                    ViewerBottomAction(icon = Icons.Default.Delete, label = "Delete", onClick = {
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

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = Color(0xFF1E293B),
                title = {
                    Text("Delete media?", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Are you sure you want to delete \"${currentItem.displayName}\"?",
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
                        Text("Delete", fontWeight = FontWeight.Bold)
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
 * Backward compatibility MediaViewerScreen for FileEntity.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    initialItem: FileEntity,
    allItems: List<FileEntity>,
    onBack: () -> Unit,
    onDelete: (FileEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = TeleDriveApplication.instance

    var activeList by remember(allItems) { mutableStateOf(allItems) }
    val effectiveList = if (activeList.isEmpty()) listOf(initialItem) else activeList

    val initialIndex = effectiveList.indexOfFirst { it.fileId == initialItem.fileId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { effectiveList.size })

    val currentItem = effectiveList.getOrNull(pagerState.currentPage.coerceIn(0, effectiveList.size - 1)) ?: initialItem
    var showControls by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Swipe down to dismiss
    val offsetY = remember { Animatable(0f) }
    val dragScale by remember { derivedStateOf { (1f - (offsetY.value / 1200f)).coerceIn(0.55f, 1f) } }
    val dragAlpha by remember { derivedStateOf { (1f - (offsetY.value / 800f)).coerceIn(0.2f, 1f) } }
    val bgDimAlpha by remember { derivedStateOf { (1f - (offsetY.value / 600f)).coerceIn(0f, 1f) } }

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
    ) {
        HorizontalPager(
            state = pagerState,
            key = { idx -> effectiveList.getOrNull(idx)?.fileId ?: idx },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = effectiveList.getOrNull(page) ?: return@HorizontalPager
            val isCurrentPage = (pagerState.currentPage == page)

            val isVideo = item.mimeType.startsWith("video/") || item.fileName.endsWith(".mp4", true) || item.fileName.endsWith(".mkv", true)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0 || offsetY.value > 0) {
                                    scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                                }
                            },
                            onDragEnd = {
                                if (offsetY.value > 200f) {
                                    onBack()
                                } else {
                                    scope.launch { offsetY.animateTo(0f, tween(150)) }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetY.animateTo(0f, tween(150)) }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    SingleVideoPlayerPage(
                        file = item,
                        isCurrentPage = isCurrentPage,
                        onTap = { showControls = !showControls }
                    )
                } else {
                    SingleImageViewerPage(
                        file = item,
                        onTap = { showControls = !showControls }
                    )
                }
            }
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.9f),
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatViewerDate(currentItem.uploadTimestamp),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Telegram Cloud",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }

        // Bottom Action Bar: Download, Delete
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
                                Color.Black.copy(alpha = 0.7f),
                                Color.Black.copy(alpha = 0.95f)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViewerBottomAction(
                        icon = Icons.Default.Download,
                        label = "Download",
                        onClick = {
                            scope.launch {
                                app.transferManager.enqueueDownload(
                                    virtualPath = currentItem.virtualPath,
                                    fileName = currentItem.fileName,
                                    fileSize = currentItem.fileSize,
                                    chatId = currentItem.telegramChatId,
                                    messageId = currentItem.telegramMessageId
                                )
                                Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    ViewerBottomAction(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        onClick = { showDeleteConfirmDialog = true }
                    )
                }
            }
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = Color(0xFF1E293B),
                title = {
                    Text("Delete from Telegram Cloud?", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Are you sure you want to permanently delete \"${currentItem.fileName}\" from your Telegram Saved Messages?",
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
                        Text("Delete", fontWeight = FontWeight.Bold)
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

@Composable
fun SingleImageViewerPage(
    file: FileEntity,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val tdLibManager = app.tdLibManager
    var localPath by remember(file.fileId) { mutableStateOf<String?>(null) }
    var isLoading by remember(file.fileId) { mutableStateOf(true) }
    var errorMessage by remember(file.fileId) { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val cachedThumb = remember(file.fileId) {
        app.thumbnailCacheManager.getFastCachedPath(file)
            ?: com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath("cloud_${file.fileId}")
    }

    LaunchedEffect(file.fileId, file.telegramFileId, file.telegramMessageId) {
        isLoading = true
        errorMessage = null

        // 1. Check local cache or download folder
        val cached = File(context.cacheDir, file.fileName)
        if (cached.exists() && cached.length() > 0) {
            localPath = cached.absolutePath
            isLoading = false
            return@LaunchedEffect
        }

        val downloaded = File(FileUtils.getDownloadDir(context), file.fileName)
        if (downloaded.exists() && downloaded.length() > 0) {
            localPath = downloaded.absolutePath
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Probe current telegramFileId
        var currentFileId = file.telegramFileId
        if (currentFileId != 0) {
            try {
                val tdFile = tdLibManager.getFile(currentFileId)
                if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                    localPath = tdFile.local.path
                    isLoading = false
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                // File ID invalid or from previous session: reset to 0 to rehydrate
                currentFileId = 0
            }
        }

        // 3. Rehydrate from TDLib message info if file ID was stale or missing
        if (currentFileId == 0 && file.telegramMessageId != 0L) {
            val chatId = when {
                file.telegramChatId != 0L -> file.telegramChatId
                app.preferences.getCachedStorageChatId() != 0L -> app.preferences.getCachedStorageChatId()
                else -> tdLibManager.getSavedMessagesChatId()
            }
            if (chatId != 0L) {
                val info = tdLibManager.getMessageInfo(chatId, file.telegramMessageId)
                if (info != null && info.documentFileId != 0) {
                    currentFileId = info.documentFileId
                    try {
                        app.database.fileDao().updateFileIds(file.fileId, info.documentFileId, info.thumbnailFileId)
                    } catch (_: Exception) {}
                } else if (info == null) {
                    try { app.database.fileDao().delete(file) } catch (_: Exception) {}
                }
            }
        }

        // 4. Download and listen for updates
        if (currentFileId != 0) {
            try {
                tdLibManager.startDownload(currentFileId, 32)
            } catch (ignored: Exception) {}

            launch {
                val finalPath = tdLibManager.downloadFile(currentFileId, 32, timeoutMs = 45_000L)
                if (finalPath.isNotEmpty() && File(finalPath).exists()) {
                    localPath = finalPath
                    isLoading = false
                } else if (localPath == null) {
                    isLoading = false
                    errorMessage = "Failed to download image"
                }
            }

            tdLibManager.fileUpdates
                .filter { it.fileId == currentFileId }
                .collect { update ->
                    if (update.isDownloadingCompleted && update.localPath.isNotEmpty() && File(update.localPath).exists()) {
                        localPath = update.localPath
                        isLoading = false
                    }
                }
        } else {
            isLoading = false
            errorMessage = "Unable to locate photo on Telegram servers"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        if (scale > 1f) {
                            scope.launch {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        } else {
                            scope.launch {
                                scale = 2.5f
                                offsetX = -offset.x * 1.5f
                                offsetY = -offset.y * 1.5f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (localPath != null) {
            AsyncImage(
                model = File(localPath!!),
                contentDescription = file.fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
        } else if (isLoading) {
            if (cachedThumb != null && File(cachedThumb).exists()) {
                AsyncImage(
                    model = File(cachedThumb),
                    contentDescription = file.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(36.dp)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "Unable to load photo",
                    color = GoogleOnDarkTextMuted,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun SingleVideoPlayerPage(
    file: FileEntity,
    isCurrentPage: Boolean,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val tdLibManager = app.tdLibManager
    var localPath by remember(file.fileId) { mutableStateOf<String?>(null) }
    var isLoading by remember(file.fileId) { mutableStateOf(true) }
    var errorMessage by remember(file.fileId) { mutableStateOf<String?>(null) }
    var downloadProgress by remember(file.fileId) { mutableFloatStateOf(0f) }

    val cachedThumb = remember(file.fileId) {
        app.thumbnailCacheManager.getFastCachedPath(file)
            ?: com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath("cloud_${file.fileId}")
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(file.fileId, file.telegramFileId, file.telegramMessageId) {
        isLoading = true
        errorMessage = null
        downloadProgress = 0f

        // 1. Check local cache or download folder
        val cached = File(context.cacheDir, file.fileName)
        if (cached.exists() && cached.length() > 0) {
            localPath = cached.absolutePath
            isLoading = false
            return@LaunchedEffect
        }

        val downloaded = File(FileUtils.getDownloadDir(context), file.fileName)
        if (downloaded.exists() && downloaded.length() > 0) {
            localPath = downloaded.absolutePath
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Determine target file ID
        var currentFileId = file.telegramFileId
        if (currentFileId != 0) {
            try {
                val tdFile = tdLibManager.getFile(currentFileId)
                if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                    localPath = tdFile.local.path
                    isLoading = false
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                currentFileId = 0
            }
        }

        // 3. If stale or 0, rehydrate from TDLib message
        if (currentFileId == 0 && file.telegramMessageId != 0L) {
            val chatId = when {
                file.telegramChatId != 0L -> file.telegramChatId
                app.preferences.getCachedStorageChatId() != 0L -> app.preferences.getCachedStorageChatId()
                else -> tdLibManager.getSavedMessagesChatId()
            }
            if (chatId != 0L) {
                val info = tdLibManager.getMessageInfo(chatId, file.telegramMessageId)
                if (info != null && info.documentFileId != 0) {
                    currentFileId = info.documentFileId
                    try {
                        app.database.fileDao().updateFileIds(file.fileId, info.documentFileId, info.thumbnailFileId)
                    } catch (_: Exception) {}
                } else if (info == null) {
                    try { app.database.fileDao().delete(file) } catch (_: Exception) {}
                }
            }
        }

        if (currentFileId != 0) {
            try {
                tdLibManager.startDownload(currentFileId, 32)
            } catch (ignored: Exception) {}

            launch {
                val finalPath = tdLibManager.downloadFile(currentFileId, 32, timeoutMs = 60_000L) { down, total ->
                    if (total > 0) downloadProgress = (down.toFloat() / total).coerceIn(0f, 1f)
                }
                if (finalPath.isNotEmpty() && File(finalPath).exists()) {
                    localPath = finalPath
                    isLoading = false
                } else if (localPath == null) {
                    isLoading = false
                    errorMessage = "Failed to download video"
                }
            }

            tdLibManager.fileUpdates
                .filter { it.fileId == currentFileId }
                .collect { update ->
                    if (update.expectedSize > 0) {
                        downloadProgress = (update.downloadedSize.toFloat() / update.expectedSize).coerceIn(0f, 1f)
                    }
                    if (update.isDownloadingCompleted && update.localPath.isNotEmpty() && File(update.localPath).exists()) {
                        localPath = update.localPath
                        isLoading = false
                    }
                }
        } else {
            isLoading = false
            errorMessage = "Unable to locate video on Telegram servers"
        }
    }

    LaunchedEffect(localPath, isCurrentPage) {
        if (isCurrentPage && localPath != null) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(localPath!!)))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (localPath != null) {
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (isLoading) {
            if (cachedThumb != null && File(cachedThumb).exists()) {
                AsyncImage(
                    model = File(cachedThumb),
                    contentDescription = file.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
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
        } else {
            Text(errorMessage ?: "Unable to load video", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

@Composable
fun SingleLocalImageViewerPage(
    uri: Uri,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        if (scale > 1f) {
                            scope.launch {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        } else {
                            scope.launch {
                                scale = 2.5f
                                offsetX = -offset.x * 1.5f
                                offsetY = -offset.y * 1.5f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        )
    }
}

@Composable
fun SingleLocalVideoPlayerPage(
    uri: Uri,
    isCurrentPage: Boolean,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(uri, isCurrentPage) {
        if (isCurrentPage) {
            val mediaItem = MediaItem.fromUri(uri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
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
                value = if (item.isVideo) "Video" else "Image (${item.mimeType})"
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}
