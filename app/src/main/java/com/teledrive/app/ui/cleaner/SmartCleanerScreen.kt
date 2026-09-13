package com.teledrive.app.ui.cleaner

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.teledrive.app.data.cleaner.CleanupCategory
import com.teledrive.app.data.cleaner.CleanupGroup
import com.teledrive.app.data.cleaner.SmartCleanupEngine
import com.teledrive.app.data.repository.LocalMediaItem
import com.teledrive.app.ui.theme.GoogleDarkBackground
import com.teledrive.app.ui.theme.GoogleDarkCard
import com.teledrive.app.ui.theme.GoogleOnDarkText
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import com.teledrive.app.ui.theme.GoogleOnDarkTextSubtle
import com.teledrive.app.ui.theme.GooglePrimaryAccent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class CleanerTab(val title: String) {
    DUPLICATES("Duplicates"),
    LARGE_VIDEOS("Large Videos"),
    OLD_MEDIA("Old Media")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCleanerScreen(
    cleanupEngine: SmartCleanupEngine,
    onBack: () -> Unit,
    onUploadAndClean: (List<LocalMediaItem>) -> Unit = {},
    onDeleteDirectly: (List<LocalMediaItem>) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(CleanerTab.DUPLICATES) }
    var duplicateGroups by remember { mutableStateOf<List<CleanupGroup>>(emptyList()) }
    var largeVideoGroups by remember { mutableStateOf<List<CleanupGroup>>(emptyList()) }
    var oldMediaGroups by remember { mutableStateOf<List<CleanupGroup>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val selectedItemIds = remember { mutableStateListOf<Long>() }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            // Run all scans on IO to avoid blocking the main thread.
            // Fetch device media ONCE and share across all three scans
            // (was 3 separate MediaStore cursor scans before).
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    val dup = async { cleanupEngine.findDuplicateImages().first() }
                    val large = async { cleanupEngine.findLargeVideos(50).first() }
                    val old = async { cleanupEngine.findOldMedia(180).first() }
                    duplicateGroups = dup.await()
                    largeVideoGroups = large.await()
                    oldMediaGroups = old.await()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    val activeGroups = when (selectedTab) {
        CleanerTab.DUPLICATES -> duplicateGroups
        CleanerTab.LARGE_VIDEOS -> largeVideoGroups
        CleanerTab.OLD_MEDIA -> oldMediaGroups
    }

    val totalReclaimableBytes = remember(duplicateGroups, largeVideoGroups, oldMediaGroups) {
        duplicateGroups.sumOf { it.reclaimableSizeBytes } +
        largeVideoGroups.sumOf { it.reclaimableSizeBytes } +
        oldMediaGroups.sumOf { it.reclaimableSizeBytes }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Smart Storage Cleaner",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GoogleOnDarkText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GoogleDarkBackground,
                    titleContentColor = GoogleOnDarkText
                )
            )
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header summary banner
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = GooglePrimaryAccent.copy(alpha = 0.15f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = GooglePrimaryAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (totalReclaimableBytes > 0) "Save up to ${formatBytes(totalReclaimableBytes)}" else "Storage is Optimized",
                            color = GoogleOnDarkText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Back up to Telegram Cloud to free device space",
                            color = GoogleOnDarkTextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Tab Selector
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = GoogleDarkBackground,
                contentColor = GooglePrimaryAccent,
                divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.08f)) }
            ) {
                CleanerTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = GooglePrimaryAccent)
                }
            } else if (activeGroups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No items found for this category",
                            color = GoogleOnDarkTextMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(activeGroups, key = { it.id }) { group ->
                        CleanerGroupCard(
                            group = group,
                            selectedItemIds = selectedItemIds,
                            onToggleSelection = { id ->
                                if (selectedItemIds.contains(id)) selectedItemIds.remove(id)
                                else selectedItemIds.add(id)
                            },
                            onSelectAll = {
                                val allIds = group.items.map { it.id }
                                if (selectedItemIds.containsAll(allIds)) selectedItemIds.removeAll(allIds)
                                else selectedItemIds.addAll(allIds)
                            }
                        )
                    }
                }
            }

            // Bottom Action Bar (visible when items selected)
            AnimatedVisibility(
                visible = selectedItemIds.isNotEmpty(),
                enter = slideInVertically(animationSpec = tween(180)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(animationSpec = tween(150)) { it } + fadeOut(tween(150))
            ) {
                val selectedItems = remember(selectedItemIds.toList(), duplicateGroups, largeVideoGroups, oldMediaGroups) {
                    val all = (duplicateGroups + largeVideoGroups + oldMediaGroups).flatMap { it.items }.distinctBy { it.id }
                    all.filter { selectedItemIds.contains(it.id) }
                }
                val selectedSizeBytes = selectedItems.sumOf { it.size }

                Surface(
                    color = Color(0xFF1E1E26),
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${selectedItems.size} selected",
                                color = GoogleOnDarkText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatBytes(selectedSizeBytes),
                                color = GooglePrimaryAccent,
                                fontSize = 12.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onDeleteDirectly(selectedItems)
                                    selectedItemIds.clear()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Delete", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    onUploadAndClean(selectedItems)
                                    selectedItemIds.clear()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GooglePrimaryAccent),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cloud & Free Space", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanerGroupCard(
    group: CleanupGroup,
    selectedItemIds: List<Long>,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.title,
                        color = GoogleOnDarkText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = group.description,
                        color = GoogleOnDarkTextSubtle,
                        fontSize = 11.sp
                    )
                }

                TextButton(onClick = onSelectAll) {
                    Text("Toggle All", fontSize = 11.sp, color = GooglePrimaryAccent)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Plain Row + horizontalScroll: LazyRow nested inside LazyColumn
            // creates nested-lazy measurement + extra composition passes that
            // stutter on scroll. Groups are small (dup sets), Row is cheaper.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                for (item in group.items) {
                    val isSelected = selectedItemIds.contains(item.id)
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF22222E))
                            .then(
                                if (isSelected) Modifier.border(2.dp, GooglePrimaryAccent, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable { onToggleSelection(item.id) }
                    ) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(ctx)
                                .data(item.contentUri).size(180)
                                .crossfade(false).allowHardware(true).build(),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Checkbox badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(
                                    if (isSelected) GooglePrimaryAccent else Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // Size pill
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = formatBytes(item.size),
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
