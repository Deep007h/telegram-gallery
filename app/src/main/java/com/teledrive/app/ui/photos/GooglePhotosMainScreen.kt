package com.teledrive.app.ui.photos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.cleaner.SmartCleanupEngine
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.DeviceAlbum
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
import com.teledrive.app.ui.components.UpdateDialog
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
import com.teledrive.app.ui.viewer.UnifiedMediaViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun shareMediaItems(context: Context, items: List<UnifiedMediaItem>) {
    val uris = ArrayList<Uri>()
    for (item in items) {
        if (item.localUri != null) {
            uris.add(item.localUri)
        } else if (item.localPath != null && File(item.localPath).exists()) {
            try {
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(item.localPath)
                )
                uris.add(contentUri)
            } catch (_: Exception) {}
        }
    }
    if (uris.isEmpty()) {
        Toast.makeText(context, "No local media available to share. Please download first.", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items.firstOrNull()?.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.all { it.isVideo }) "video/*" else if (items.all { !it.isVideo }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share ${items.size} item(s)"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePhotosMainScreen(
    navController: NavController,
    viewModel: ExplorerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var selectedTab by remember { mutableStateOf(GooglePhotosTab.PHOTOS) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedItemIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        viewModel.syncCurrentSource()
    }

    // Stable click handlers: inline `{ activeUnifiedItem = it }` in grid items
    // allocates per tile per recomposition (tiles never skippable). These
    // write to the same remembered holders, so caching once is correct.
    val onUnifiedClick: (UnifiedMediaItem) -> Unit = remember {
        { item -> activeUnifiedItem = item }
    }
    val onFileOpen: (FileEntity) -> Unit = remember {
        { file ->
            if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
                activeViewerItem = file
            }
        }
    }

    fun handleSelectionClick(itemId: String) {
        if (isSelectionMode) {
            selectedItemIds = if (selectedItemIds.contains(itemId)) {
                val next = selectedItemIds - itemId
                if (next.isEmpty()) isSelectionMode = false
                next
            } else {
                selectedItemIds + itemId
            }
        }
    }

    fun handleSelectionLongClick(itemId: String) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedItemIds = setOf(itemId)
        } else {
            handleSelectionClick(itemId)
        }
    }

    fun handleToggleSelectGroup(groupItems: List<UnifiedMediaItem>) {
        if (!isSelectionMode) isSelectionMode = true
        val groupIds = groupItems.map { it.id }.toSet()
        val allGroupSelected = groupIds.all { selectedItemIds.contains(it) }
        selectedItemIds = if (allGroupSelected) {
            val next = selectedItemIds - groupIds
            if (next.isEmpty()) isSelectionMode = false
            next
        } else {
            selectedItemIds + groupIds
        }
    }

    // Silent background update check on app launch
    LaunchedEffect(Unit) {
        val autoCheck = app.preferences.autoCheckUpdates.first()
        if (autoCheck) {
            otaManager.checkForUpdates(force = false)
        }
    }

    // Telegram account info (display name, phone, profile photo). Resolved on
    // first composition and refreshed each time the profile sheet opens so the
    // avatar tracks live updates the user makes inside Telegram or preferences.
    var userDisplayName by rememberSaveable { mutableStateOf("") }
    var userPhone by remember { mutableStateOf("") }
    var profilePhotoPath by remember { mutableStateOf<String?>(null) }

    val customPhotoPath by app.preferences.customProfilePhotoPath.collectAsState(initial = "")
    val telegramPhotoPath by app.preferences.telegramProfilePhotoPath.collectAsState(initial = app.preferences.getCachedTelegramProfilePhotoPath())
    val tdLibPhotoPath by app.tdLibManager.myProfilePhotoPath.collectAsState()
    val savedDisplayName by app.preferences.userDisplayName.collectAsState(initial = "")

    val effectivePhotoPath = remember(tdLibPhotoPath, profilePhotoPath, telegramPhotoPath, customPhotoPath) {
        when {
            customPhotoPath.isNotBlank() && !customPhotoPath.contains("profile_photo_") && !customPhotoPath.contains("telegram_avatar_") && File(customPhotoPath).exists() -> customPhotoPath
            !tdLibPhotoPath.isNullOrBlank() && File(tdLibPhotoPath!!).exists() -> tdLibPhotoPath
            !profilePhotoPath.isNullOrBlank() && File(profilePhotoPath!!).exists() -> profilePhotoPath
            telegramPhotoPath.isNotBlank() && File(telegramPhotoPath).exists() -> telegramPhotoPath
            else -> null
        }
    }
    val effectiveDisplayName = remember(userDisplayName, savedDisplayName) {
        when {
            userDisplayName.isNotBlank() && userDisplayName != "Telegram User" -> userDisplayName
            savedDisplayName.isNotBlank() -> savedDisplayName
            else -> "TeleDrive User"
        }
    }

    val refreshUserProfile: () -> Unit = {
        scope.launch {
            val custom = withContext(Dispatchers.IO) {
                try { app.preferences.customProfilePhotoPath.first() } catch (_: Exception) { "" }
            }
            if (custom.contains("profile_photo_") || custom.contains("telegram_avatar_")) {
                withContext(Dispatchers.IO) {
                    try { app.preferences.setCustomProfilePhotoPath("") } catch (_: Exception) {}
                }
            }
            val prefName = withContext(Dispatchers.IO) {
                try { app.preferences.userDisplayName.first() } catch (_: Exception) { "" }
            }
            if (prefName.isNotBlank() && userDisplayName.isBlank()) {
                userDisplayName = prefName
            }
            val cachedTelegram = app.preferences.getCachedTelegramProfilePhotoPath()
            if (cachedTelegram.isNotBlank() && File(cachedTelegram).exists()) {
                profilePhotoPath = cachedTelegram
            }

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
                if (prefName.isBlank()) {
                    app.preferences.setUserDisplayName(name)
                }

                val file = withContext(Dispatchers.IO) {
                    app.tdLibManager.fetchAndDownloadTelegramProfilePhoto(context)
                }
                if (file != null && file.exists()) {
                    profilePhotoPath = file.absolutePath
                    app.preferences.setTelegramProfilePhotoPath(file.absolutePath)
                }
            } catch (_: Exception) {
                // Keep the previous values on failure
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshUserProfile()
        app.tdLibManager.authState.collect { state ->
            if (state is com.teledrive.app.telegram.TdLibAuthState.Ready) {
                refreshUserProfile()
            }
        }
    }

    LaunchedEffect(Unit) {
        app.tdLibManager.myProfilePhotoPath.collect { path ->
            if (!path.isNullOrBlank() && File(path).exists()) {
                profilePhotoPath = path
            }
        }
    }

    LaunchedEffect(Unit) {
        app.preferences.telegramProfilePhotoPath.collect { path ->
            if (path.isNotBlank() && File(path).exists()) {
                profilePhotoPath = path
            }
        }
    }

    LaunchedEffect(Unit) {
        app.preferences.userDisplayName.collect { name ->
            if (name.isNotBlank() && name != "undefined" && name != "null") {
                userDisplayName = name
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
            Toast.makeText(context, "Uploading ${uris.size} item(s)...", Toast.LENGTH_SHORT).show()
        }
    }

    // Filter media items (photos & videos) with search query support & deduplication
    // Upstream (LocalRepository.getAllFiles/Media) already distinctBy's on IO;
    // re-distincting 1000s of rows on the Main thread per sync tick cost frames
    // for zero benefit. Filter only (query empty = same reference, O(1)).
    val allFiles = remember(uiState.allMedia, uiState.allCloudFiles, uiState.files, uiState.searchQuery) {
        val base = if (uiState.allCloudFiles.isNotEmpty()) uiState.allCloudFiles
                   else if (uiState.allMedia.isNotEmpty()) uiState.allMedia
                   else uiState.files
        val query = uiState.searchQuery.trim()
        if (query.isEmpty()) {
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

    // Photos tab must respect the search query too: previously the SearchSheet
    // only filtered the Files tab (allFiles), leaving PHOTOS unfiltered with no
    // way to clear the stale query except reopening the sheet.
    val photosQuery = uiState.searchQuery.trim()
    val photosVisibleMedia = remember(uiState.unifiedMedia, photosQuery) {
        if (photosQuery.isEmpty()) uiState.unifiedMedia
        else if (photosQuery.equals("Photos", ignoreCase = true)) {
            uiState.unifiedMedia.filter { !it.isVideo }
        } else if (photosQuery.equals("Videos", ignoreCase = true)) {
            uiState.unifiedMedia.filter { it.isVideo }
        } else if (photosQuery.equals("Documents", ignoreCase = true)) {
            uiState.unifiedMedia.filter {
                val n = it.displayName.lowercase()
                n.contains("doc") || n.contains("pdf") || n.contains("receipt") || n.contains("bill")
            }
        } else if (photosQuery.equals("Screenshots", ignoreCase = true)) {
            uiState.unifiedMedia.filter {
                (it.bucketName?.lowercase() ?: "").contains("screenshot") ||
                it.displayName.lowercase().contains("screenshot")
            }
        } else {
            uiState.unifiedMedia.filter { it.displayName.contains(photosQuery, ignoreCase = true) }
        }
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
        enabled = isSelectionMode ||
                  selectedCategoryName != null ||
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
            isSelectionMode -> {
                isSelectionMode = false
                selectedItemIds = emptySet()
            }
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
                allItems = if (selectedCategoryName != null) categoryFilteredUnifiedMedia else photosVisibleMedia,
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
                        Toast.makeText(context, "Backing up item to Cloud...", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { item ->
                    viewModel.moveToTrash(listOf(item))
                    Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
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
            val cleanerEngine = remember { SmartCleanupEngine(context, app.deviceMediaRepository) }
            SmartCleanerScreen(
                cleanupEngine = cleanerEngine,
                onBack = { showSmartCleaner = false },
                onUploadAndClean = { items ->
                    viewModel.uploadLocalMediaItems(items)
                    scope.launch {
                        cleanerEngine.deleteLocalMediaItems(items)
                        // Local MediaStore changed: force MediaStore re-query,
                        // otherwise the cached album list stays stale.
                        viewModel.loadDeviceAlbums(forceRefresh = true)
                    }
                    Toast.makeText(context, "Backing up ${items.size} item(s)...", Toast.LENGTH_SHORT).show()
                },
                onDeleteDirectly = { items ->
                    scope.launch {
                        cleanerEngine.deleteLocalMediaItems(items)
                        viewModel.loadDeviceAlbums(forceRefresh = true)
                    }
                }
            )
        } else if (showTrashScreen) {
            val trashedList by app.trashManager.trashedItems.collectAsState()
            TrashScreen(
                trashedItems = trashedList,
                onBackClick = { showTrashScreen = false },
                onRestore = { items ->
                    viewModel.restoreFromTrash(items)
                    Toast.makeText(context, "Restored ${items.size} item(s)", Toast.LENGTH_SHORT).show()
                },
                onDeletePermanently = { items ->
                    viewModel.permanentlyDeleteTrash(items)
                    Toast.makeText(context, "Permanently deleted ${items.size} item(s)", Toast.LENGTH_SHORT).show()
                },
                onEmptyTrash = {
                    viewModel.emptyTrash()
                    Toast.makeText(context, "Trash emptied", Toast.LENGTH_SHORT).show()
                }
            )
        } else if (selectedDeviceAlbum != null) {
            AlbumDetailScreen(
                album = selectedDeviceAlbum!!,
                onBack = { selectedDeviceAlbum = null },
                onUploadItems = { items ->
                    viewModel.uploadLocalMediaItems(items)
                    Toast.makeText(context, "Backing up ${items.size} item(s)...", Toast.LENGTH_SHORT).show()
                },
                onMoveToTrash = { localItems ->
                    val unifiedItems = localItems.map { local ->
                        UnifiedMediaItem(
                            id = "local_${local.id}",
                            displayName = local.displayName,
                            dateModified = local.dateModified,
                            isVideo = local.isVideo,
                            durationMs = local.durationMs,
                            mimeType = local.mimeType,
                            fileSize = local.size,
                            localUri = local.contentUri,
                            localPath = local.filePath,
                            isLocalOnDevice = true,
                            bucketName = local.bucketName
                        )
                    }
                    viewModel.moveToTrash(unifiedItems)
                    Toast.makeText(context, "Moved ${localItems.size} item(s) to Trash", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, "Backing up ${items.size} item(s)...", Toast.LENGTH_SHORT).show()
                        },
                        onMoveToTrash = { localItems ->
                            val unifiedItems = localItems.map { local ->
                                UnifiedMediaItem(
                                    id = "local_${local.id}",
                                    displayName = local.displayName,
                                    dateModified = local.dateModified,
                                    isVideo = local.isVideo,
                                    durationMs = local.durationMs,
                                    mimeType = local.mimeType,
                                    fileSize = local.size,
                                    localUri = local.contentUri,
                                    localPath = local.filePath,
                                    isLocalOnDevice = true,
                                    bucketName = local.bucketName
                                )
                            }
                            viewModel.moveToTrash(unifiedItems)
                            Toast.makeText(context, "Moved ${localItems.size} item(s) to Trash", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    // Show Category Grid for Documents, Places, Stickers, Favorites
                    GooglePhotosMainGrid(
                        items = categoryFilteredUnifiedMedia,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedItemIds,
                        onItemClick = { item ->
                            if (isSelectionMode) {
                                handleSelectionClick(item.id)
                            } else {
                                onUnifiedClick(item)
                            }
                        },
                        onItemLongClick = { item ->
                            handleSelectionLongClick(item.id)
                        },
                        onToggleSelectDateGroup = { groupItems ->
                            handleToggleSelectGroup(groupItems)
                        }
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        if (isSelectionMode) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = "${selectedItemIds.size} selected",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        isSelectionMode = false
                                        selectedItemIds = emptySet()
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                                    }
                                },
                                actions = {
                                    val visibleList = if (selectedCategoryName != null) categoryFilteredUnifiedMedia else photosVisibleMedia
                                    val allSelected = visibleList.isNotEmpty() && selectedItemIds.size >= visibleList.size
                                    TextButton(onClick = {
                                        selectedItemIds = if (allSelected) {
                                            isSelectionMode = false
                                            emptySet()
                                        } else {
                                            visibleList.map { it.id }.toSet()
                                        }
                                    }) {
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
                            GooglePhotosTopBar(
                                title = when (selectedTab) {
                                    GooglePhotosTab.PHOTOS -> "Photos"
                                    GooglePhotosTab.COLLECTIONS -> "Collections"
                                    GooglePhotosTab.SEARCH -> "Search"
                                    GooglePhotosTab.FILES -> "Files"
                                },
                                isBackingUp = uiState.backupState.isBackingUp,
                                isBackupComplete = uiState.backupState.isJustCompleted,
                                backupProgress = uiState.backupState.progress,
                                backupStatusText = uiState.backupState.statusText,
                                backupSubText = uiState.backupState.subText,
                                userDisplayName = effectiveDisplayName,
                                profilePhotoPath = effectivePhotoPath,
                                onBackupClick = {
                                    showProfileSheet = true
                                    refreshUserProfile()
                                },
                                onSelectClick = if (selectedTab == GooglePhotosTab.PHOTOS) { { isSelectionMode = true } } else null,
                                onAddClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                onNotificationClick = { navController.navigate(Screen.Transfers.route) },
                                onAvatarClick = {
                                    showProfileSheet = true
                                    refreshUserProfile()
                                }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        Crossfade(
                            targetState = selectedTab,
                            animationSpec = androidx.compose.animation.core.tween(180),
                            label = "MainTabCrossfade"
                        ) { tab ->
                            when (tab) {
                                GooglePhotosTab.PHOTOS -> {
                                    GooglePhotosMainGrid(
                                        items = photosVisibleMedia,
                                        isSelectionMode = isSelectionMode,
                                        selectedIds = selectedItemIds,
                                        onItemClick = { item ->
                                            if (isSelectionMode) {
                                                handleSelectionClick(item.id)
                                            } else {
                                                onUnifiedClick(item)
                                            }
                                        },
                                        onItemLongClick = { item ->
                                            handleSelectionLongClick(item.id)
                                        },
                                        onToggleSelectDateGroup = { groupItems ->
                                            handleToggleSelectGroup(groupItems)
                                        }
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
                                    LaunchedEffect(tab) {
                                        showSearchSheet = true
                                    }
                                    GooglePhotosMainGrid(
                                        items = photosVisibleMedia,
                                        isSelectionMode = isSelectionMode,
                                        selectedIds = selectedItemIds,
                                        onItemClick = { item ->
                                            if (isSelectionMode) {
                                                handleSelectionClick(item.id)
                                            } else {
                                                onUnifiedClick(item)
                                            }
                                        },
                                        onItemLongClick = { item ->
                                            handleSelectionLongClick(item.id)
                                        },
                                        onToggleSelectDateGroup = { groupItems ->
                                            handleToggleSelectGroup(groupItems)
                                        }
                                    )
                                }
                                GooglePhotosTab.FILES -> {
                                    FilesScreen(
                                        files = allFiles,
                                        onFileClick = onFileOpen,
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
                                            Toast.makeText(context, "Uploading ${uris.size} file(s)...", Toast.LENGTH_SHORT).show()
                                        },
                                        onSyncClick = { viewModel.syncCurrentSource() }
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Navigation Bar (smoothly slides down when selection mode enters)
                AnimatedVisibility(
                    visible = !isSelectionMode,
                    enter = slideInVertically(animationSpec = androidx.compose.animation.core.tween(180)) { it } + fadeIn(androidx.compose.animation.core.tween(180)),
                    exit = slideOutVertically(animationSpec = androidx.compose.animation.core.tween(150)) { it } + fadeOut(androidx.compose.animation.core.tween(150)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    GooglePhotosBottomNav(
                        selectedTab = selectedTab,
                        onTabSelected = { tab -> selectedTab = tab }
                    )
                }

                // Floating Selection Action Bar
                AnimatedVisibility(
                    visible = isSelectionMode && selectedItemIds.isNotEmpty(),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val activeList = if (selectedCategoryName != null) categoryFilteredUnifiedMedia else photosVisibleMedia
                    val selectedItems = remember(selectedItemIds, activeList) {
                        activeList.filter { selectedItemIds.contains(it.id) }
                    }

                    Surface(
                        color = Color(0xFF1E1F2B),
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Share
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { shareMediaItems(context, selectedItems) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Share", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            // 2. Backup to Cloud
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val unbacked = selectedItems.filter { !it.isCloudBackedUp && it.localUri != null }
                                        if (unbacked.isEmpty()) {
                                            Toast.makeText(context, "Selected items are already backed up", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val toUpload = unbacked.mapNotNull { item ->
                                                item.localUri?.let { uri ->
                                                    LocalMediaItem(
                                                        id = item.id.removePrefix("local_").toLongOrNull() ?: 0L,
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
                                                }
                                            }
                                            viewModel.uploadLocalMediaItems(toUpload)
                                            Toast.makeText(context, "Backing up ${toUpload.size} item(s) to Cloud...", Toast.LENGTH_SHORT).show()
                                            isSelectionMode = false
                                            selectedItemIds = emptySet()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = "Backup", tint = Color(0xFFA8C7FA), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Backup", color = Color(0xFFA8C7FA), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            // 3. Save to Device
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val cloudOnly = selectedItems.filter { it.cloudFile != null && !it.isLocalOnDevice }
                                        if (cloudOnly.isEmpty()) {
                                            Toast.makeText(context, "Selected items are already on device", Toast.LENGTH_SHORT).show()
                                        } else {
                                            scope.launch {
                                                for (item in cloudOnly) {
                                                    item.cloudFile?.let { file ->
                                                        app.transferManager.enqueueDownload(
                                                            virtualPath = file.virtualPath,
                                                            fileName = file.fileName,
                                                            fileSize = file.fileSize,
                                                            chatId = file.telegramChatId,
                                                            messageId = file.telegramMessageId
                                                        )
                                                    }
                                                }
                                                Toast.makeText(context, "Downloading ${cloudOnly.size} item(s)...", Toast.LENGTH_SHORT).show()
                                                isSelectionMode = false
                                                selectedItemIds = emptySet()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Save", tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            // 4. Move to Trash
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showDeleteConfirmDialog = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Trash", tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Trash", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Batch Move to Trash confirmation dialog
                if (showDeleteConfirmDialog) {
                    val activeList = if (selectedCategoryName != null) categoryFilteredUnifiedMedia else photosVisibleMedia
                    val selectedItems = remember(selectedItemIds, activeList) {
                        activeList.filter { selectedItemIds.contains(it.id) }
                    }
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        containerColor = Color(0xFF1E293B),
                        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
                        title = { Text("Move to Trash?", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Move ${selectedItems.size} item(s) to Trash? You can restore them anytime from Trash in Collections.",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    viewModel.moveToTrash(selectedItems)
                                    Toast.makeText(context, "Moved ${selectedItems.size} item(s) to Trash", Toast.LENGTH_SHORT).show()
                                    isSelectionMode = false
                                    selectedItemIds = emptySet()
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

                // Search Overlay Sheet
                if (showSearchSheet) {
                    SearchSheet(
                        onDismiss = {
                            showSearchSheet = false
                            // Leaving SEARCH avoids a blank tab behind the sheet.
                            if (selectedTab == GooglePhotosTab.SEARCH) {
                                selectedTab = GooglePhotosTab.PHOTOS
                            }
                        },
                        onQueryChange = { q -> viewModel.setSearchQuery(q) }
                    )
                }

                // Account / Profile Overlay Sheet
                if (showProfileSheet) {
                    GooglePhotosProfileSheet(
                        userDisplayName = effectiveDisplayName,
                        phoneNumber = userPhone,
                        profilePhotoPath = effectivePhotoPath,
                        totalCount = allFiles.size,
                        syncedCount = if (uiState.backupState.isBackingUp) (allFiles.size - uiState.backupState.pendingCount).coerceAtLeast(0) else allFiles.size,
                        totalSizeBytes = totalSizeBytes,
                        onDismiss = { showProfileSheet = false },
                        onLogout = {
                            showProfileSheet = false
                            viewModel.logout {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        },
                        onTriggerBackup = { viewModel.triggerBackup() },
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
