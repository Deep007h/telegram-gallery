package com.teledrive.app.ui.albums

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teledrive.app.data.repository.DeviceAlbum
import com.teledrive.app.data.repository.LocalMediaItem
import com.teledrive.app.ui.theme.GoogleDarkBackground
import com.teledrive.app.ui.theme.GoogleDarkCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: DeviceAlbum,
    onBack: () -> Unit,
    onUploadItems: (List<LocalMediaItem>) -> Unit,
    onMoveToTrash: (List<LocalMediaItem>) -> Unit = {},
    onItemClick: (LocalMediaItem) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<Long>() }
    var showTrashConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isSelectionMode) "${selectedItemIds.size} selected" else album.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isSelectionMode) {
                            val countText = when {
                                album.videoCount > 0 && album.photoCount > 0 -> "${album.photoCount} photos, ${album.videoCount} videos"
                                album.videoCount > 0 -> "${album.videoCount} videos"
                                else -> "${album.itemCount} items"
                            }
                            Text(
                                text = countText,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectedItemIds.clear()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = {
                                if (selectedItemIds.size == album.items.size) {
                                    selectedItemIds.clear()
                                } else {
                                    selectedItemIds.clear()
                                    selectedItemIds.addAll(album.items.map { it.id })
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedItemIds.size == album.items.size) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = "Toggle Select All",
                                tint = Color.White
                            )
                        }
                    } else {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select Mode", tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                onUploadItems(album.items)
                            }
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Upload All to Cloud", tint = Color(0xFFA8C7FA))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF191922))
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSelectionMode && selectedItemIds.isNotEmpty(),
                enter = slideInVertically(animationSpec = tween(180)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(animationSpec = tween(150)) { it } + fadeOut(tween(150))
            ) {
                Surface(
                    color = Color(0xFF1E1F2B),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    val chosen = remember(selectedItemIds.toList(), album.items) {
                        val selectedSet = selectedItemIds.toSet()
                        album.items.filter { it.id in selectedSet }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val uris = ArrayList(chosen.map { it.contentUri })
                                    val intent = if (uris.size == 1) {
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = chosen.first().mimeType
                                            putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    } else {
                                        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = if (chosen.all { it.isVideo }) "video/*" else if (chosen.all { !it.isVideo }) "image/*" else "*/*"
                                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share ${chosen.size} item(s)"))
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Share", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        // Backup
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onUploadItems(chosen)
                                    isSelectionMode = false
                                    selectedItemIds.clear()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Backup", tint = Color(0xFFA8C7FA), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Backup", color = Color(0xFFA8C7FA), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        // Trash
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showTrashConfirmDialog = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Trash", tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Trash", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        val defaultFling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
        val halfSpeedFling = remember(defaultFling) {
            object : androidx.compose.foundation.gestures.FlingBehavior {
                override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
                    return with(defaultFling) {
                        performFling(initialVelocity * 0.65f)
                    }
                }
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            flingBehavior = halfSpeedFling,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 90.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GoogleDarkBackground)
        ) {
            items(album.items, key = { it.id }, contentType = { if (it.isVideo) "video" else "image" }) { item ->
                val isSelected = item.id in selectedItemIds

                LocalMediaGridTile(
                    item = item,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (isSelectionMode) {
                            if (isSelected) {
                                selectedItemIds.remove(item.id)
                            } else {
                                selectedItemIds.add(item.id)
                            }
                        } else {
                            onItemClick(item)
                        }
                    },
                    onLongClick = {
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedItemIds.add(item.id)
                        }
                    }
                )
            }
        }
    }

    if (showTrashConfirmDialog) {
        val chosen = remember(selectedItemIds.toList(), album.items) {
            val selectedSet = selectedItemIds.toSet()
            album.items.filter { it.id in selectedSet }
        }
        AlertDialog(
            onDismissRequest = { showTrashConfirmDialog = false },
            containerColor = Color(0xFF1E293B),
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
            title = { Text("Move to Trash?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Move ${chosen.size} item(s) to Trash? You can restore them anytime from Trash in Collections.",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTrashConfirmDialog = false
                        onMoveToTrash(chosen)
                        isSelectionMode = false
                        selectedItemIds.clear()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Move to Trash", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrashConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalMediaGridTile(
    item: LocalMediaItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 10.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "localTileCorner"
    )
    val tileInset by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "localTileInset"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "localCheckScale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color(0xFF22232E))
            .padding(tileInset)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (isSelected) Modifier.border(2.5.dp, Color(0xFF4285F4), RoundedCornerShape(cornerRadius))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val itemId = "local_${item.id}"
        val cachedThumb = remember(itemId) {
            com.teledrive.app.core.FastThumbnailCacheManager.getCachedThumbnailPath(itemId)
        }
        val displaySource: Any = remember(cachedThumb, item.contentUri) {
            if (cachedThumb != null) java.io.File(cachedThumb) else item.contentUri
        }
        val req = remember(itemId, displaySource) {
            coil.request.ImageRequest.Builder(context)
                .data(displaySource)
                .size(256, 256)
                .memoryCacheKey("lowres_$itemId")
                .diskCacheKey("lowres_$itemId")
                .crossfade(false)
                .allowHardware(true)
                .build()
        }
        AsyncImage(
            model = req,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video Duration Overlay
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 60f
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatVideoDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Selection Checkbox Badge
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color(0x664285F4) else Color.Transparent)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    }
            ) {
                if (isSelected) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF4285F4), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.6.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun LocalFullscreenMediaViewer(
    initialItem: LocalMediaItem,
    allItems: List<LocalMediaItem>,
    onClose: () -> Unit,
    onUpload: (LocalMediaItem) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(allItems.indexOf(initialItem).coerceAtLeast(0)) }
    val currentItem = allItems.getOrNull(currentIndex) ?: initialItem

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = currentItem.contentUri,
            contentDescription = currentItem.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Top App Bar Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentItem.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${currentIndex + 1} of ${allItems.size}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = { onUpload(currentItem) }) {
                Icon(Icons.Default.CloudUpload, contentDescription = "Upload to Cloud", tint = Color(0xFFA8C7FA))
            }
        }
    }
}

private fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        String.format("%d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
