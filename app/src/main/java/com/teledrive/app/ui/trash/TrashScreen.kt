package com.teledrive.app.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.ui.photos.GoogleMediaTile
import com.teledrive.app.ui.theme.GoogleDarkBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    trashedItems: List<UnifiedMediaItem> = emptyList(),
    onBackClick: () -> Unit,
    onRestore: (List<UnifiedMediaItem>) -> Unit = {},
    onDeletePermanently: (List<UnifiedMediaItem>) -> Unit = {},
    onEmptyTrash: () -> Unit = {}
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showEmptyDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var itemForActionSheet by remember { mutableStateOf<UnifiedMediaItem?>(null) }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    val selectedItems = remember(trashedItems, selectedIds) {
        trashedItems.filter { selectedIds.contains(it.id) }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedIds.size} selected",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size >= trashedItems.size) {
                                isSelectionMode = false
                                emptySet()
                            } else {
                                trashedItems.map { it.id }.toSet()
                            }
                        }) {
                            val allSelected = trashedItems.isNotEmpty() && selectedIds.size >= trashedItems.size
                            Text(
                                text = if (allSelected) "Deselect All" else "Select All",
                                color = Color(0xFFA8C7FA),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF191922))
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Trash / Bin", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (trashedItems.isNotEmpty()) {
                                Text(
                                    text = "${trashedItems.size} item(s)",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        if (trashedItems.isNotEmpty()) {
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = "Select", tint = Color.White)
                            }
                            TextButton(onClick = { showEmptyDialog = true }) {
                                Text("Empty", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF191922))
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSelectionMode && selectedIds.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF1E1F2B),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onRestore(selectedItems)
                                isSelectionMode = false
                                selectedIds = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore (${selectedItems.size})", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete (${selectedItems.size})", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        if (trashedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(GoogleDarkBackground),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Trash is empty", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Items moved to trash will stay here. You can restore them to your gallery or permanently delete them at any time.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            val trashGridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = trashGridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(GoogleDarkBackground)
            ) {
                items(trashedItems, key = { it.id }, contentType = { if (it.isVideo) "video" else "image" }) { item ->
                    val isSelected = selectedIds.contains(item.id)
                    GoogleMediaTile(
                        item = item,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelectionMode) {
                                selectedIds = if (isSelected) {
                                    val next = selectedIds - item.id
                                    if (next.isEmpty()) isSelectionMode = false
                                    next
                                } else {
                                    selectedIds + item.id
                                }
                            } else {
                                itemForActionSheet = item
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds = setOf(item.id)
                            } else {
                                selectedIds = if (isSelected) {
                                    val next = selectedIds - item.id
                                    if (next.isEmpty()) isSelectionMode = false
                                    next
                                } else {
                                    selectedIds + item.id
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Empty Trash confirmation dialog
    if (showEmptyDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyDialog = false },
            containerColor = Color(0xFF1E293B),
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444)) },
            title = { Text("Empty Trash?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "All ${trashedItems.size} items will be permanently erased from your device storage and Telegram Cloud. This cannot be undone.",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyDialog = false
                        onEmptyTrash()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Empty Trash", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Batch permanent delete confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = Color(0xFF1E293B),
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444)) },
            title = { Text("Delete Permanently?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Permanently remove ${selectedItems.size} selected item(s)? They will be completely erased from device storage and Telegram Cloud.",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeletePermanently(selectedItems)
                        isSelectionMode = false
                        selectedIds = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Delete Forever", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Single item action modal bottom sheet
    itemForActionSheet?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { itemForActionSheet = null },
            containerColor = Color(0xFF1E1F2B),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = item.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (item.isCloudBackedUp) "Telegram Cloud" else (item.bucketName ?: "Device Media"),
                    color = Color.Gray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onRestore(listOf(item))
                            itemForActionSheet = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore")
                    }

                    Button(
                        onClick = {
                            onDeletePermanently(listOf(item))
                            itemForActionSheet = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}
