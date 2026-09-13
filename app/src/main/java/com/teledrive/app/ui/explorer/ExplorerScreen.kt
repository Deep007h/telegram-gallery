package com.teledrive.app.ui.explorer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.ui.components.EmptyState
import com.teledrive.app.ui.components.ErrorState
import com.teledrive.app.ui.components.LoadingIndicator
import com.teledrive.app.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ExplorerScreen(
    navController: NavController,
    path: String = "/",
    viewModel: ExplorerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<FileEntity?>(null) }
    var fileToDelete by remember { mutableStateOf<FileEntity?>(null) }

    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
            Toast.makeText(context, "Uploading ${uris.size} file(s)...", Toast.LENGTH_SHORT).show()
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh() }
    )

    LaunchedEffect(path, uiState.activeChatId) {
        if (path.isNotBlank()) {
            viewModel.loadFolder(path, uiState.activeChatId)
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedFiles.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllFiles() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { viewModel.downloadSelectedFiles() }) {
                            Icon(Icons.Default.Download, contentDescription = "Download selected")
                        }
                        IconButton(onClick = { viewModel.deleteSelectedFiles() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search files & folders...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear query")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = if (uiState.storageChatTitle.isNotBlank()) uiState.storageChatTitle else "Telegram Storage"
                        )
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                imageVector = if (uiState.viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle view mode"
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            SortMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                currentSort = uiState.sortBy,
                                onSortSelected = {
                                    viewModel.setSortBy(it)
                                    showSortMenu = false
                                }
                            )
                        }
                        IconButton(onClick = { navController.navigate(Screen.Transfers.route) }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Transfers")
                        }
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.storageSource == StorageSource.TELEDRIVE_CHANNEL) {
                    FloatingActionButton(
                        onClick = { showCreateFolderDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    text = { Text("Upload") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            FolderBreadcrumb(
                segments = uiState.pathSegments,
                onSegmentClick = { viewModel.navigateToFolder(it.path) }
            )

            if (uiState.files.isNotEmpty()) {
                FileTypeFilterRow(
                    currentFilter = uiState.fileTypeFilter,
                    onFilterSelected = viewModel::setFileTypeFilter
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        LoadingIndicator(isGrid = uiState.viewMode == ViewMode.GRID)
                    }
                    uiState.error != null -> {
                        ErrorState(
                            error = uiState.error!!,
                            onRetry = { viewModel.loadFolder(uiState.currentPath) }
                        )
                    }
                    uiState.folders.isEmpty() && uiState.files.isEmpty() -> {
                        EmptyState(onUploadClick = { filePickerLauncher.launch(arrayOf("*/*")) })
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pullRefresh(pullRefreshState)
                        ) {
                            if (uiState.viewMode == ViewMode.GRID) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Adaptive(160.dp),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(uiState.folders, key = { "folder_${it.folderId}" }) { folder ->
                                        FolderGridItem(
                                            folder = folder,
                                            onClick = { viewModel.navigateToFolder(folder.virtualPath) }
                                        )
                                    }
                                    items(uiState.files, key = { "file_${it.fileId}" }) { file ->
                                        FileGridItem(
                                            file = file,
                                            isSelected = uiState.selectedFiles.contains(file.fileId),
                                            onClick = {
                                                if (uiState.isSelectionMode) {
                                                    viewModel.toggleFileSelection(file.fileId)
                                                } else {
                                                    navigateToFileViewer(navController, file, coroutineScope)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleFileSelection(file.fileId)
                                            }
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    items(uiState.folders, key = { "folder_${it.folderId}" }) { folder ->
                                        FolderListItem(
                                            folder = folder,
                                            onClick = { viewModel.navigateToFolder(folder.virtualPath) },
                                            onDelete = { viewModel.deleteFolder(folder) }
                                        )
                                    }
                                    items(uiState.files, key = { "file_${it.fileId}" }) { file ->
                                        FileListItem(
                                            file = file,
                                            isSelected = uiState.selectedFiles.contains(file.fileId),
                                            isSelectionMode = uiState.isSelectionMode,
                                            onClick = {
                                                if (uiState.isSelectionMode) {
                                                    viewModel.toggleFileSelection(file.fileId)
                                                } else {
                                                    navigateToFileViewer(navController, file, coroutineScope)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleFileSelection(file.fileId)
                                            },
                                            onDownload = {
                                                viewModel.downloadFile(file)
                                                navController.navigate(Screen.Transfers.route)
                                            },
                                            onRename = { fileToRename = file },
                                            onDelete = { fileToDelete = file }
                                        )
                                    }
                                }
                            }
                            PullRefreshIndicator(
                                refreshing = uiState.isRefreshing,
                                state = pullRefreshState,
                                modifier = Modifier.align(Alignment.TopCenter),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { folderName ->
                viewModel.createFolder(folderName)
                showCreateFolderDialog = false
            }
        )
    }

    fileToRename?.let { file ->
        RenameDialog(
            currentName = file.fileName,
            onDismiss = { fileToRename = null },
            onRename = { newName ->
                viewModel.renameFile(file.fileId, newName)
                fileToRename = null
            }
        )
    }

    fileToDelete?.let { file ->
        DeleteConfirmDialog(
            itemName = file.fileName,
            onDismiss = { fileToDelete = null },
            onConfirm = {
                viewModel.deleteFile(file)
                fileToDelete = null
            }
        )
    }
}

private fun navigateToFileViewer(
    navController: NavController,
    file: FileEntity,
    scope: kotlinx.coroutines.CoroutineScope
) {
    when {
        file.mimeType.startsWith("image/") -> {
            navController.navigate(Screen.ImageViewer.createRoute(file.fileId))
        }
        file.mimeType.startsWith("video/") -> {
            navController.navigate(Screen.VideoPlayer.createRoute(file.fileId))
        }
        file.mimeType.startsWith("audio/") -> {
            navController.navigate(Screen.AudioPlayer.createRoute(file.fileId))
        }
        else -> {
            scope.launch {
                TeleDriveApplication.instance.transferManager.enqueueDownload(
                    virtualPath = file.virtualPath,
                    fileName = file.fileName,
                    fileSize = file.fileSize,
                    chatId = file.telegramChatId,
                    messageId = file.telegramMessageId
                )
            }
            navController.navigate(Screen.Transfers.route)
        }
    }
}
