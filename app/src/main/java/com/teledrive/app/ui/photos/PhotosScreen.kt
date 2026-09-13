package com.teledrive.app.ui.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.ui.components.TelegramThumbnail
import com.teledrive.app.ui.theme.GoogleDarkBackground
import com.teledrive.app.ui.theme.GoogleOnDarkText
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import com.teledrive.app.ui.theme.GoogleOnDarkTextSubtle
import com.teledrive.app.ui.theme.GooglePrimaryAccent
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.teledrive.app.core.FastThumbnailCacheManager
import com.teledrive.app.core.InteractionCoordinator
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Immutable
sealed class GalleryGridEntry {
    abstract val key: String
    abstract val monthYear: String

    data class Header(
        override val key: String,
        val text: String,
        val subtitle: String,
        override val monthYear: String,
        val groupItems: List<UnifiedMediaItem>
    ) : GalleryGridEntry()

    data class Media(
        val item: UnifiedMediaItem,
        override val monthYear: String
    ) : GalleryGridEntry() {
        override val key: String get() = item.id
    }
}

private fun buildGridEntries(items: List<UnifiedMediaItem>): List<GalleryGridEntry> {
    val dayGroups = linkedMapOf<Long, MutableList<UnifiedMediaItem>>()
    for (item in items) {
        val dayKey = if (item.dateModified > 0) {
            TimeUnit.MILLISECONDS.toDays(item.dateModified)
        } else Long.MIN_VALUE
        dayGroups.getOrPut(dayKey) { mutableListOf() }.add(item)
    }
    val dayFormat = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    val monthYearFormat = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    val entries = ArrayList<GalleryGridEntry>(items.size + dayGroups.size)
    for ((dayKey, list) in dayGroups) {
        val (headerLabel, monthYearLabel) = if (dayKey == Long.MIN_VALUE) {
            "Recent" to "Recent"
        } else {
            val instant = java.time.Instant.ofEpochMilli(TimeUnit.DAYS.toMillis(dayKey))
            val localDate = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val h = try { dayFormat.format(localDate) } catch (_: Exception) { "Recent" }
            val m = try { monthYearFormat.format(localDate) } catch (_: Exception) { "Recent" }
            h to m
        }
        entries.add(
            GalleryGridEntry.Header(
                key = "header_${dayKey}_$headerLabel",
                text = headerLabel,
                subtitle = "${list.size} items",
                monthYear = monthYearLabel,
                groupItems = list
            )
        )
        for (mediaItem in list) {
            entries.add(
                GalleryGridEntry.Media(
                    item = mediaItem,
                    monthYear = monthYearLabel
                )
            )
        }
    }
    return entries
}

@Composable
fun GooglePhotosMainGrid(
    items: List<UnifiedMediaItem>,
    isSelectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onItemClick: (UnifiedMediaItem) -> Unit,
    onItemLongClick: (UnifiedMediaItem) -> Unit = {},
    onToggleSelectDateGroup: (List<UnifiedMediaItem>) -> Unit = {}
) {
    val columnCount = 3
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val thumbManager = remember {
        try { TeleDriveApplication.instance.thumbnailCacheManager } catch (_: Exception) { null }
    }
    LaunchedEffect(gridState.isScrollInProgress) {
        val mgr = thumbManager
        if (gridState.isScrollInProgress) {
            InteractionCoordinator.setInteracting(true)
            mgr?.setPreloadPaused(true)
        } else {
            kotlinx.coroutines.delay(350)
            InteractionCoordinator.setInteracting(false)
            mgr?.setPreloadPaused(false)
        }
    }

    // Flatten into single-pass pre-formatted list of grid entries off the composition thread
    val initialEntries = remember { if (items.size in 1..300) buildGridEntries(items) else emptyList() }
    val gridEntries by produceState(initialValue = initialEntries, key1 = items) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildGridEntries(items)
        }
    }

    // 144Hz instant derived month/year text: updates on the exact frame the index changes
    val currentMonthText by remember(gridEntries) {
        derivedStateOf {
            val idx = gridState.firstVisibleItemIndex
            gridEntries.getOrNull(idx)?.monthYear ?: ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GoogleDarkBackground)
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = GooglePrimaryAccent,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Photos or Videos Found",
                        color = GoogleOnDarkTextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add media from the + button to back it up",
                        color = GoogleOnDarkTextSubtle,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = gridEntries.size,
                    key = { index -> gridEntries[index].key },
                    span = { index ->
                        if (gridEntries[index] is GalleryGridEntry.Header) GridItemSpan(maxLineSpan)
                        else GridItemSpan(1)
                    },
                    contentType = { index ->
                        when (val entry = gridEntries[index]) {
                            is GalleryGridEntry.Header -> "header"
                            is GalleryGridEntry.Media -> if (entry.item.isVideo) "video" else "image"
                        }
                    }
                ) { index ->
                    when (val entry = gridEntries[index]) {
                        is GalleryGridEntry.Header -> {
                            val isGroupSelected = remember(entry.groupItems, selectedIds) {
                                entry.groupItems.isNotEmpty() && entry.groupItems.all { selectedIds.contains(it.id) }
                            }
                            DateHeaderRow(
                                text = entry.text,
                                subtitle = entry.subtitle,
                                isSelectionMode = isSelectionMode,
                                isGroupSelected = isGroupSelected,
                                onToggleSelectGroup = { onToggleSelectDateGroup(entry.groupItems) }
                            )
                        }
                        is GalleryGridEntry.Media -> {
                            val isSelected = selectedIds.contains(entry.item.id)
                            val tileClick = remember(entry.item.id, onItemClick) {
                                { onItemClick(entry.item) }
                            }
                            val tileLongClick = remember(entry.item.id, onItemLongClick) {
                                { onItemLongClick(entry.item) }
                            }
                            GoogleMediaTile(
                                item = entry.item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = tileClick,
                                onLongClick = tileLongClick
                            )
                        }
                    }
                }
            }

            // Google Photos Interactive Fast-Scroll Scrubber & Instant Month Indicator
            FastScrollMonthScrubber(
                gridState = gridState,
                totalEntries = gridEntries.size,
                currentMonthText = currentMonthText,
                modifier = Modifier.fillMaxSize(),
                onScrub = { targetIndex ->
                    scope.launch {
                        gridState.scrollToItem(targetIndex)
                    }
                }
            )
        }
    }
}

@Composable
private fun FastScrollMonthScrubber(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    totalEntries: Int,
    currentMonthText: String,
    modifier: Modifier = Modifier,
    onScrub: (Int) -> Unit
) {
    if (totalEntries <= 6) return

    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val scrollFraction by remember(totalEntries) {
        derivedStateOf {
            if (totalEntries <= 1) 0f
            else (gridState.firstVisibleItemIndex.toFloat() / (totalEntries - 1)).coerceIn(0f, 1f)
        }
    }

    val activeFraction = if (isDragging) dragProgress else scrollFraction

    val isScrollActive = gridState.isScrollInProgress || isDragging
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isScrollActive) {
        if (isScrollActive) {
            isVisible = true
            InteractionCoordinator.setInteracting(true)
        } else {
            kotlinx.coroutines.delay(800)
            isVisible = false
            InteractionCoordinator.setInteracting(false)
        }
    }

    // Subtle haptic tick when month changes during dragging
    var lastHapticMonth by remember { mutableStateOf("") }
    LaunchedEffect(currentMonthText, isDragging) {
        if (isDragging && currentMonthText.isNotEmpty() && currentMonthText != lastHapticMonth) {
            lastHapticMonth = currentMonthText
            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val thumbHeightDp = 48.dp
        val topPaddingDp = 24.dp
        val bottomPaddingDp = 108.dp

        val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
        val topPaddingPx = with(density) { topPaddingDp.toPx() }
        val bottomPaddingPx = with(density) { bottomPaddingDp.toPx() }
        val totalHeightPx = with(density) { maxHeight.toPx() }
        val trackHeightPx = (totalHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)

        val thumbYPx = topPaddingPx + (activeFraction * trackHeightPx)

        // Draggable right touch strip
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(44.dp)
                .pointerInput(totalEntries, trackHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val rawFraction = ((offset.y - topPaddingPx - (thumbHeightPx / 2)) / trackHeightPx).coerceIn(0f, 1f)
                            dragProgress = rawFraction
                            val targetIndex = (rawFraction * (totalEntries - 1)).roundToInt().coerceIn(0, totalEntries - 1)
                            onScrub(targetIndex)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val deltaFraction = dragAmount / trackHeightPx
                            val next = (dragProgress + deltaFraction).coerceIn(0f, 1f)
                            dragProgress = next
                            val targetIndex = (next * (totalEntries - 1)).roundToInt().coerceIn(0, totalEntries - 1)
                            onScrub(targetIndex)
                        }
                    )
                }
        )

        // Floating Month Pill + Scroll Thumb pinned to the right edge
        AnimatedVisibility(
            visible = isVisible && currentMonthText.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(120)) + scaleIn(initialScale = 0.88f, animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.88f, animationSpec = tween(220)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset(
                        x = 0,
                        y = thumbYPx.roundToInt()
                    )
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                // Month & Year Capsule Bubble (Google Photos style)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF21E1F24),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = currentMonthText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                // Vertical Scrollbar Thumb
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = if (isDragging) thumbHeightDp else 36.dp)
                        .background(
                            color = if (isDragging) Color(0xFF4285F4) else Color.White.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}
@Composable
private fun DateHeaderRow(
    text: String,
    subtitle: String,
    isSelectionMode: Boolean = false,
    isGroupSelected: Boolean = false,
    onToggleSelectGroup: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isSelectionMode, onClick = onToggleSelectGroup)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSelectionMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onToggleSelectGroup)
                ) {
                    if (isGroupSelected) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1A73E8), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Group Selected",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.8.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                .background(Color.Transparent, CircleShape)
                        )
                    }
                }
            }

            Text(
                text = text,
                color = GoogleOnDarkText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier.background(
                Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
        ) {
            Text(
                text = subtitle,
                color = GoogleOnDarkTextSubtle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoogleMediaTile(
    item: UnifiedMediaItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 10.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileCorner"
    )
    val tileInset by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileInset"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "checkScale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color(0xFF16161D))
            .padding(tileInset)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (isSelected) Modifier.border(2.5.dp, Color(0xFF4285F4), RoundedCornerShape(cornerRadius))
                else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        val context = LocalContext.current

        val localModel = remember(item.id, item.localUri, item.localPath) {
            when {
                item.localUri != null -> item.localUri
                !item.localPath.isNullOrBlank() -> File(item.localPath)
                else -> null
            }
        }

        val fastCachedPath = remember(item.id) {
            FastThumbnailCacheManager.getCachedThumbnailPath(item.id)
        }
        val targetModel = remember(item.id, fastCachedPath, localModel) {
            if (fastCachedPath != null) File(fastCachedPath) else localModel
        }

        if (targetModel != null) {
            val thumbRequest = remember(item.id, targetModel) {
                ImageRequest.Builder(context)
                    .data(targetModel)
                    .size(360, 360)
                    .memoryCacheKey("media_${item.id}")
                    .diskCacheKey("media_${item.id}")
                    .allowHardware(true)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = thumbRequest,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item.cloudFile != null) {
            TelegramThumbnail(
                file = item.cloudFile,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Selection overlay tint
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x334285F4))
            )
        }

        // Selection circle badge on top-left
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
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
                            .background(Color(0xFF1A73E8), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.8.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                    )
                }
            }
        }

        // Video duration pill
        if (item.isVideo) {
            DurationPill(
                durationMs = item.durationMs,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            )
        }

        // Cloud badge
        if (item.isCloudBackedUp) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Backed up",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoogleMediaTile(
    item: FileEntity,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isVideo = item.mimeType.startsWith("video/") || item.fileName.endsWith(".mp4", true) || item.fileName.endsWith(".mkv", true)
    val haptic = LocalHapticFeedback.current
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 10.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileCornerFile"
    )
    val tileInset by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileInsetFile"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "checkScaleFile"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color(0xFF16161D))
            .padding(tileInset)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (isSelected) Modifier.border(2.5.dp, Color(0xFF4285F4), RoundedCornerShape(cornerRadius))
                else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        TelegramThumbnail(
            file = item,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection overlay tint
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x334285F4))
            )
        }

        // Selection circle badge on top-left
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
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
                            .background(Color(0xFF1A73E8), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.8.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                    )
                }
            }
        }

        if (isVideo) {
            DurationPill(
                durationMs = 0,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                fallbackText = "▶"
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = "Backed up",
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
@Composable
private fun DurationPill(
    durationMs: Long,
    modifier: Modifier = Modifier,
    fallbackText: String? = null
) {
    val durationStr = when {
        durationMs > 0 -> {
            val totalSeconds = durationMs / 1000
            val seconds = totalSeconds % 60
            val totalMinutes = totalSeconds / 60
            val minutes = totalMinutes % 60
            val hours = totalMinutes / 60
            if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
        fallbackText != null -> fallbackText
        else -> "0:00"
    }

    // Plain Box, not Surface: no Material elevation/tonal overhead per video tile.
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = durationStr,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
