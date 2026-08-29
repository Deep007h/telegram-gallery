package com.teledrive.app.ui.photos

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.DeviceAlbum
import com.teledrive.app.data.cleaner.SmartCleanupEngine
import com.teledrive.app.data.repository.DeviceMediaRepository
import com.teledrive.app.data.repository.LocalMediaItem
import com.teledrive.app.data.repository.PersonCluster
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.ui.albums.AlbumDetailScreen
import com.teledrive.app.ui.cleaner.SmartCleanerScreen
import com.teledrive.app.ui.collections.CollectionsScreen
import com.teledrive.app.ui.collections.DeviceAlbumsGridScreen
import com.teledrive.app.ui.components.GooglePhotosBottomNav
import com.teledrive.app.ui.components.GooglePhotosTab
import com.teledrive.app.ui.components.GooglePhotosTopBar
import com.teledrive.app.ui.explorer.ExplorerViewModel
import com.teledrive.app.ui.files.FilesScreen
import com.teledrive.app.ui.navigation.Screen
import com.teledrive.app.ui.people.PeopleScreen
import com.teledrive.app.ui.people.PersonDetailScreen
import com.teledrive.app.ui.profile.GooglePhotosProfileSheet
import com.teledrive.app.ui.search.SearchSheet
import com.teledrive.app.ui.theme.GooglePhotosTheme
import com.teledrive.app.ui.trash.TrashScreen
import com.teledrive.app.ui.viewer.MediaViewerScreen
import com.teledrive.app.ui.components.UpdateDialog
import com.teledrive.app.ui.viewer.UnifiedMediaViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GooglePhotosMainScreen(
    navController: NavController,
    viewModel: ExplorerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var selectedTab by remember { mutableStateOf(GooglePhotosTab.PHOTOS) }
    var activeUnifiedItem by remember { mutableStateOf<UnifiedMediaItem?>(null) }
    var activeViewerItem by remember { mutableStateOf<FileEntity?>(null) }
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }
    var activePersonDetail by remember { mutableStateOf<PersonCluster?>(null) }
    var selectedDeviceAlbum by remember { mutableStateOf<DeviceAlbum?>(null) }
    var showDeviceAlbumsGrid by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showTrashScreen by remember { mutableStateOf(false) }
    var showSmartCleaner by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val scope = rememberCoroutineScope()

    val otaManager = app.otaUpdateManager
    val otaState by otaManager.updateState.collectAsState()

    // Silent background update check on app launch
    LaunchedEffect(Unit) {
        val autoCheck = app.preferences.autoCheckUpdates.first()
        if (autoCheck) {
            otaManager.checkForUpdates(force = false)
        }
    }

    // Telegram account info (display name, phone, profile photo). Resolved on
    // first composition and refreshed each time the profile sheet opens so the
    // avatar tracks live updates the user makes inside Telegram.
    var userDisplayName by rememberSaveable { mutableStateOf("") }
    var userPhone by remember { mutableStateOf("") }
    var profilePhotoPath by remember { mutableStateOf<String?>(null) }

    val refreshUserProfile: () -> Unit = {
        scope.launch {
            try {
                val user = withContext(Dispatchers.IO) { app.tdLibManager.getMeUser() }
                val name = listOf(user.firstName, user.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                    .ifBlank {
                        user.usernames?.activeUsernames?.firstOrNull()?.removePrefix("@")
                            ?: "Telegram User"
                    }
                userDisplayName = name
                userPhone = user.phoneNumber
                val photoFileId: Int? = user.profilePhoto?.let { photo ->
                    (photo.small ?: photo.big)?.id
                }
                if (photoFileId != null && photoFileId != 0) {
                    val file = withContext(Dispatchers.IO) {
                        app.tdLibManager.downloadProfilePhoto(photoFileId, context)
                    }
                    profilePhotoPath = file?.absolutePath
                } else {
                    profilePhotoPath = null
                }
            } catch (_: Exception) {
                // Keep the previous values on failure
            }
        }
    }

    LaunchedEffect(Unit) { refreshUserProfile() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
            navController.navigate(Screen.Transfers.route)
        }
    }

    // Filter media items (photos & videos) with search query support & deduplication
    val allFiles = remember(uiState.allMedia, uiState.allCloudFiles, uiState.files, uiState.searchQuery) {
        val base = if (uiState.allCloudFiles.isNotEmpty()) uiState.allCloudFiles
                   else if (uiState.allMedia.isNotEmpty()) uiState.allMedia
                   else uiState.files
        val query = uiState.searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            base
        } else if (query.equals("Photos", ignoreCase = true)) {
            base.filter { it.mimeType.startsWith("image/") || it.fileName.endsWith(".jpg", true) || it.fileName.endsWith(".jpeg", true) || it.fileName.endsWith(".png", true) }
        } else if (query.equals("Videos", ignoreCase = true)) {
            base.filter { it.mimeType.startsWith("video/") || it.fileName.endsWith(".mp4", true) || it.fileName.endsWith(".mkv", true) }
        } else if (query.equals("Documents", ignoreCase = true)) {
            base.filter { !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") }
        } else if (query.equals("Screenshots", ignoreCase = true)) {
            base.filter { it.fileName.contains("screenshot", ignoreCase = true) }
        } else {
            base.filter { it.fileName.contains(query, ignoreCase = true) }
        }

        filtered.distinctBy { file ->
            if (file.fileSize > 0) "${file.fileName.lowercase().trim()}_${file.fileSize}"
            else if (file.telegramFileId != 0) "tg_${file.telegramFileId}"
            else file.fileName.lowercase().trim()
        }
    }

    val mediaFiles = remember(allFiles) {
        allFiles.filter {
            it.mimeType.startsWith("image/") ||
            it.mimeType.startsWith("video/") ||
            it.fileName.endsWith(".jpg", true) ||
            it.fileName.endsWith(".jpeg", true) ||
            it.fileName.endsWith(".png", true) ||
            it.fileName.endsWith(".webp", true) ||
            it.fileName.endsWith(".mp4", true) ||
            it.fileName.endsWith(".mov", true) ||
            it.fileName.endsWith(".mkv", true) ||
            it.fileName.endsWith(".dng", true)
        }
    }

    val totalSizeBytes = remember(allFiles) {
        allFiles.sumOf { it.fileSize }
    }

    // Category filtering for Unified Media
    val categoryFilteredUnifiedMedia = remember(uiState.unifiedMedia, selectedCategoryName) {
        if (selectedCategoryName == null) emptyList()
        else when (selectedCategoryName) {
            "Screenshots" -> uiState.unifiedMedia.filter { (it.bucketName?.lowercase() ?: "").contains("screenshot") || it.displayName.lowercase().contains("screenshot") }
            "Videos" -> uiState.unifiedMedia.filter { it.isVideo }
            "Documents" -> uiState.unifiedMedia.filter {
                val name = it.displayName.lowercase()
                val bucket = it.bucketName?.lowercase() ?: ""
                bucket.contains("document") || name.contains("doc") || name.contains("pdf") ||
                name.contains("receipt") || name.contains("bill") || name.contains("id") ||
                name.contains("form") || name.contains("page") || name.contains("sheet")
            }
            "Stickers" -> uiState.unifiedMedia.filter {
                it.mimeType.contains("png") || (it.bucketName?.lowercase() ?: "").contains("sticker") ||
                (it.bucketName?.lowercase() ?: "").contains("new folder")
            }
            "Places" -> uiState.unifiedMedia.take(12)
            "Favorites" -> uiState.unifiedMedia.take(6)
            "Archive" -> emptyList()
            else -> uiState.unifiedMedia
        }
    }

    BackHandler(
        enabled = selectedCategoryName != null ||
                  showDeviceAlbumsGrid ||
                  selectedDeviceAlbum != null ||
                  showSmartCleaner ||
                  showTrashScreen ||
                  showSearchSheet ||
                  showProfileSheet ||
                  activeViewerItem != null ||
                  activeUnifiedItem != null ||
                  selectedTab != GooglePhotosTab.PHOTOS
    ) {
        when {
            activeUnifiedItem != null -> activeUnifiedItem = null
            activeViewerItem != null -> activeViewerItem = null
            selectedDeviceAlbum != null -> selectedDeviceAlbum = null
            showDeviceAlbumsGrid -> showDeviceAlbumsGrid = false
            selectedCategoryName != null -> selectedCategoryName = null
            showSmartCleaner -> showSmartCleaner = false
            showTrashScreen -> showTrashScreen = false
            showSearchSheet -> showSearchSheet = false
            showProfileSheet -> showProfileSheet = false
            selectedTab != GooglePhotosTab.PHOTOS -> selectedTab = GooglePhotosTab.PHOTOS
        }
    }

    GooglePhotosTheme {
        if (activeUnifiedItem != null) {
            UnifiedMediaViewerScreen(
                initialItem = activeUnifiedItem!!,
                allItems = if (selectedCategoryName != null) categoryFilteredUnifiedMedia else uiState.unifiedMedia,
                onBack = { activeUnifiedItem = null },
                onUpload = { item ->
                    item.localUri?.let { uri ->
                        viewModel.uploadLocalMediaItems(
                            listOf(
                                LocalMediaItem(
                                    id = 0L,
                                    contentUri = uri,
                                    filePath = item.localPath ?: "",
                                    displayName = item.displayName,
                                    size = item.fileSize,
                                    mimeType = item.mimeType,
                                    dateModified = item.dateModified,
                                    isVideo = item.isVideo,
                                    durationMs = item.durationMs,
                                    bucketId = "",
                                    bucketName = item.bucketName ?: ""
                                )
                            )
                        )
                        navController.navigate(Screen.Transfers.route)
                    }
                },
                onDelete = { item ->
                    if (item.cloudFile != null) {
                        viewModel.deleteFile(item.cloudFile)
                    }
                    activeUnifiedItem = null
                }
            )
        } else if (activeViewerItem != null) {
            MediaViewerScreen(
                initialItem = activeViewerItem!!,
                allItems = mediaFiles,
                onBack = { activeViewerItem = null },
                onDelete = { fileToDelete ->
                    viewModel.deleteFile(fileToDelete)
                    activeViewerItem = null
                }
            )
        } else if (showSmartCleaner) {
            val cleanupEngine = remember { SmartCleanupEngine(context, DeviceMediaRepository(context)) }
            SmartCleanerScreen(
                cleanupEngine = cleanupEngine,
                onBack = { showSmartCleaner = false },
                onUploadAndClean = { items ->
                    viewModel.uploadLocalMediaItems(items)
                    scope.launch {
                        cleanupEngine.deleteLocalMediaItems(items)
                        viewModel.loadDeviceAlbums()
                    }
                    navController.navigate(Screen.Transfers.route)
                },
                onDeleteDirectly = { items ->
                    scope.launch {
                        cleanupEngine.deleteLocalMediaItems(items)
                        viewModel.loadDeviceAlbums()
                    }
                }
            )
        } else if (showTrashScreen) {
            TrashScreen(
                trashedItems = emptyList(),
                onBackClick = { showTrashScreen = false }
            )
        } else if (selectedDeviceAlbum != null) {
            AlbumDetailScreen(
                album = selectedDeviceAlbum!!,
                onBack = { selectedDeviceAlbum = null },
                onUploadItems = { items ->
                    viewModel.uploadLocalMediaItems(items)
                    navController.navigate(Screen.Transfers.route)
                }
            )
        } else if (showDeviceAlbumsGrid) {
            DeviceAlbumsGridScreen(
                title = "Albums on this device",
                albums = uiState.deviceAlbums,
                onAlbumClick = { selectedDeviceAlbum = it },
                onBack = { showDeviceAlbumsGrid = false }
            )
        } else if (activePersonDetail != null) {
            PersonDetailScreen(
                person = activePersonDetail!!,
                onPhotoClick = { activeViewerItem = it },
                onRenamePerson = { personId, newName -> viewModel.renamePerson(personId, newName) },
                onBack = { activePersonDetail = null }
            )
        } else if (selectedCategoryName != null) {
            if (selectedCategoryName == "People") {
                PeopleScreen(
                    peopleClusters = uiState.peopleClusters,
                    isScanning = uiState.isScanningPeople,
                    onPersonClick = { activePersonDetail = it },
                    onRenamePerson = { personId, newName -> viewModel.renamePerson(personId, newName) },
                    onRescan = { viewModel.rescanPeople() },
                    onBack = { selectedCategoryName = null }
                )
            } else {
                val matchingAlbum = uiState.deviceAlbums.find { it.name.equals(selectedCategoryName, ignoreCase = true) }
                if (matchingAlbum != null) {
                    AlbumDetailScreen(
                        album = matchingAlbum,
                        onBack = { selectedCategoryName = null },
                        onUploadItems = { items ->
                            viewModel.uploadLocalMediaItems(items)
                            navController.navigate(Screen.Transfers.route)
                        }
                    )
                } else {
                    // Show Category Grid for Documents, Places, Stickers, Favorites
                    GooglePhotosMainGrid(
                        items = categoryFilteredUnifiedMedia,
                        onItemClick = { activeUnifiedItem = it }
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        GooglePhotosTopBar(
                            statusText = "Backup complete",
                            photoCount = uiState.unifiedMedia.size,
                            isRefreshing = uiState.isRefreshing,
                            userDisplayName = userDisplayName.ifBlank { "Cloud" },
                            profilePhotoPath = profilePhotoPath,
                            onAddClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            onNotificationClick = { navController.navigate(Screen.Transfers.route) },
                            onAvatarClick = {
                                showProfileSheet = true
                                refreshUserProfile()
                            }
                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        key(selectedTab) {
                            when (selectedTab) {
                                GooglePhotosTab.PHOTOS -> {
                                    GooglePhotosMainGrid(
                                        items = uiState.unifiedMedia,
                                        onItemClick = { item -> activeUnifiedItem = item }
                                    )
                                }
                                GooglePhotosTab.COLLECTIONS -> {
                                    CollectionsScreen(
                                        deviceAlbums = uiState.deviceAlbums,
                                        unifiedMedia = uiState.unifiedMedia,
                                        peopleClusters = uiState.peopleClusters,
                                        onOpenAlbums = { showDeviceAlbumsGrid = true },
                                        onOpenDeviceMedia = { showDeviceAlbumsGrid = true },
                                        onAlbumClick = { album -> selectedDeviceAlbum = album },
                                        onCategoryClick = { category -> selectedCategoryName = category },
                                        onTrashClick = { showTrashScreen = true },
                                        onSmartCleanerClick = { showSmartCleaner = true },
                                        onRefresh = {
                                            viewModel.loadDeviceAlbums()
                                            viewModel.syncCurrentSource()
                                        }
                                    )
                                }
                                GooglePhotosTab.SEARCH -> {
                                    LaunchedEffect(Unit) { showSearchSheet = true }
                                }
                                GooglePhotosTab.FILES -> {
                                    FilesScreen(
                                        files = allFiles,
                                        onFileClick = { file ->
                                            if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
                                                activeViewerItem = file
                                            }
                                        },
                                        onDownloadClick = { file ->
                                            scope.launch {
                                                TeleDriveApplication.instance.transferManager.enqueueDownload(
                                                    virtualPath = file.virtualPath,
                                                    fileName = file.fileName,
                                                    fileSize = file.fileSize,
                                                    chatId = file.telegramChatId,
                                                    messageId = file.telegramMessageId
                                                )
                                            }
                                        },
                                        onDeleteFile = { file -> viewModel.deleteFile(file) },
                                        onUploadFiles = { uris ->
                                            viewModel.uploadFiles(uris)
                                            navController.navigate(Screen.Transfers.route)
                                        },
                                        onSyncClick = { viewModel.syncCurrentSource() }
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Navigation Bar
                GooglePhotosBottomNav(
                    selectedTab = selectedTab,
                    onTabSelected = { tab -> selectedTab = tab },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Search Overlay Sheet
                if (showSearchSheet) {
                    SearchSheet(
                        onDismiss = { showSearchSheet = false },
                        onQueryChange = { q -> viewModel.setSearchQuery(q) }
                    )
                }

                // Account / Profile Overlay Sheet
                if (showProfileSheet) {
                    GooglePhotosProfileSheet(
                        userDisplayName = userDisplayName.ifBlank { "Telegram User" },
                        phoneNumber = userPhone,
                        profilePhotoPath = profilePhotoPath,
                        totalCount = allFiles.size,
                        syncedCount = allFiles.size,
                        totalSizeBytes = totalSizeBytes,
                        onDismiss = { showProfileSheet = false },
                        onLogout = { navController.navigate(Screen.Settings.route) },
                        onTriggerBackup = { viewModel.syncCurrentSource() },
                        onOpenSettings = {
                            showProfileSheet = false
                            navController.navigate(Screen.Settings.route)
                        },
                        onOpenLogs = {
                            showProfileSheet = false
                            navController.navigate(Screen.Settings.route)
                        },
                        onCheckUpdates = {
                            showProfileSheet = false
                            otaManager.checkForUpdates(force = true)
                        }
                    )
                }

                // Modal Over-The-Air Update Dialog
                UpdateDialog(
                    state = otaState,
                    currentVersion = otaManager.currentVersionName,
                    onStartDownload = { info -> otaManager.startDownload(info) },
                    onInstall = { apkFile -> otaManager.promptInstall(apkFile) },
                    onCancelDownload = { otaManager.cancelDownload() },
                    onDismiss = { otaManager.dismiss() },
                    onRetry = { otaManager.checkForUpdates(force = true) }
                )
            }
        }
    }
}
