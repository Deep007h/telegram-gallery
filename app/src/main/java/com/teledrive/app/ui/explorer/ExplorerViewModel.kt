package com.teledrive.app.ui.explorer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.db.entity.FolderEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ViewMode { GRID, LIST }
enum class SortBy { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }
enum class FileTypeFilter { ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES }
enum class StorageSource { TELEDRIVE_CHANNEL, SAVED_MESSAGES }

data class PathSegment(val path: String, val name: String)

data class BackupState(
    val isBackingUp: Boolean = false,
    val isJustCompleted: Boolean = false,
    val progress: Float = 0f,
    val pendingCount: Int = 0,
    val totalCount: Int = 0,
    val statusText: String = "",
    val subText: String = ""
)

data class ExplorerUiState(
    val currentPath: String = "/",
    val pathSegments: List<PathSegment> = listOf(PathSegment("/", "Home")),
    val folders: List<FolderEntity> = emptyList(),
    val files: List<FileEntity> = emptyList(),
    val allMedia: List<FileEntity> = emptyList(),
    val allCloudFiles: List<FileEntity> = emptyList(),
    val deviceAlbums: List<com.teledrive.app.data.repository.DeviceAlbum> = emptyList(),
    val unifiedMedia: List<com.teledrive.app.data.repository.UnifiedMediaItem> = emptyList(),
    val peopleClusters: List<com.teledrive.app.data.repository.PersonCluster> = emptyList(),
    val isScanningPeople: Boolean = false,
    val isLoadingAlbums: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortBy: SortBy = SortBy.NAME_ASC,
    val selectedFiles: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val searchQuery: String = "",
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.ALL,
    val storageChannelId: Long = 0L,
    val savedMessagesChatId: Long = 0L,
    val storageSource: StorageSource = StorageSource.SAVED_MESSAGES,
    val activeChatId: Long = 0L,
    val storageChatTitle: String = "Saved Messages",
    val backupState: BackupState = BackupState()
)

class ExplorerViewModel : ViewModel() {

    private val localRepository = TeleDriveApplication.instance.localRepository
    private val channelRepository = TeleDriveApplication.instance.channelRepository
    private val transferManager = TeleDriveApplication.instance.transferManager
    private val preferences = TeleDriveApplication.instance.preferences

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    private var loadFolderJob: Job? = null
    private var mediaFlowJob: Job? = null
    private var allFilesFlowJob: Job? = null
    private var unifiedRefreshJob: Job? = null
    private var searchDebounceJob: Job? = null

    init {
        val initialStorageChatId = preferences.getCachedStorageChatId()
        val initialStorageChatTitle = preferences.getCachedStorageChatTitle()
        val initialSavedId = TeleDriveApplication.instance.tdLibManager.cachedSavedMessagesChatId
        val initialChatId = when {
            initialStorageChatId != 0L -> initialStorageChatId
            initialSavedId != 0L -> initialSavedId
            else -> com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
        }
        val initialTitle = when {
            initialStorageChatTitle.isNotBlank() -> initialStorageChatTitle
            initialChatId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID -> "Personal Storage (Deep 007h)"
            else -> "Saved Messages"
        }

        _uiState.update {
            it.copy(
                storageChatTitle = initialTitle,
                savedMessagesChatId = initialSavedId,
                storageSource = StorageSource.SAVED_MESSAGES,
                activeChatId = initialChatId
            )
        }

        // 1. Immediately subscribe to local Room DB media and files for active chat
        listenToMediaAndFiles(initialChatId)
        loadFolder("/", initialChatId)

        // 2. Immediately load device albums and unified media without network delay
        loadDeviceAlbums()
        refreshUnifiedMedia(immediate = true)

        // 3. Immediately trigger initial background sync from Telegram
        viewModelScope.launch {
            localRepository.syncFromTelegram(initialChatId)
        }

        // 4. React to changes in storageChatId from Settings
        viewModelScope.launch {
            preferences.storageChatId.collect { storedChatId ->
                val currentTarget = if (storedChatId != null && storedChatId != 0L) storedChatId else preferences.getCachedSavedMessagesChatId()
                if (currentTarget != 0L && currentTarget != _uiState.value.activeChatId) {
                    val title = preferences.getCachedStorageChatTitle().ifBlank { "Telegram Storage" }
                    _uiState.update {
                        it.copy(
                            activeChatId = currentTarget,
                            storageChatTitle = title
                        )
                    }
                    listenToMediaAndFiles(currentTarget)
                    loadFolder(_uiState.value.currentPath, currentTarget)
                    loadDeviceAlbums()
                    refreshUnifiedMedia(immediate = true)
                    localRepository.syncFromTelegram(currentTarget)
                }
            }
        }

        viewModelScope.launch {
            preferences.storageChatTitle.collect { title ->
                if (title.isNotBlank()) {
                    _uiState.update { it.copy(storageChatTitle = title) }
                }
            }
        }

        viewModelScope.launch {
            val savedViewMode = preferences.viewMode.first()
            val mode = if (savedViewMode == "list") ViewMode.LIST else ViewMode.GRID
            _uiState.update { it.copy(viewMode = mode) }

            // Observe TDLib auth state and sync storage chat
            TeleDriveApplication.instance.tdLibManager.authState.collect { authState ->
                if (authState is com.teledrive.app.telegram.TdLibAuthState.Ready) {
                    try {
                        val savedId = channelRepository.getSavedMessagesChatId()
                        if (savedId != 0L) {
                            preferences.setSavedMessagesChatId(savedId)
                            TeleDriveApplication.instance.tdLibManager.cachedSavedMessagesChatId = savedId
                        }

                        val configuredChatId = preferences.getCachedStorageChatId()
                        val configuredChatTitle = preferences.getCachedStorageChatTitle()
                        val effectiveChatId = if (configuredChatId != 0L) configuredChatId else savedId
                        val effectiveChatTitle = if (configuredChatTitle.isNotBlank()) configuredChatTitle else "Saved Messages"

                        _uiState.update {
                            it.copy(
                                savedMessagesChatId = savedId,
                                storageSource = StorageSource.SAVED_MESSAGES,
                                activeChatId = effectiveChatId,
                                storageChatTitle = effectiveChatTitle
                            )
                        }
                        listenToMediaAndFiles(effectiveChatId)
                        loadFolder(_uiState.value.currentPath, effectiveChatId)
                        loadDeviceAlbums()
                        localRepository.syncFromTelegram(effectiveChatId)
                    } catch (e: Exception) {
                        com.teledrive.app.core.AppLogger.w("ExplorerVM", "Storage chat sync failed: ${e.message}")
                    }
                }
            }
        }
        viewModelScope.launch {
            TeleDriveApplication.instance.peopleRepository.peopleClusters.collect { clusters ->
                _uiState.update { it.copy(peopleClusters = clusters) }
            }
        }

        viewModelScope.launch {
            TeleDriveApplication.instance.peopleRepository.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanningPeople = scanning) }
            }
        }

        viewModelScope.launch {
            var completionJob: Job? = null
            var wasActive = false
            var lastFinishedFiles = 0

            TeleDriveApplication.instance.backupRepository.observeActiveSession().collect { session ->
                if (session != null && (session.status == "RUNNING" || session.status == "PAUSED")) {
                    completionJob?.cancel()
                    completionJob = null
                    wasActive = true
                    lastFinishedFiles = session.totalFiles

                    val totalCount = session.totalFiles
                    val completedCount = session.completedFiles
                    val pendingCount = (totalCount - completedCount - session.failedFiles).coerceAtLeast(0)
                    val rawProgress = if (session.totalBytes > 0L) {
                        (session.transferredBytes.toDouble() / session.totalBytes.toDouble()).toFloat()
                    } else if (totalCount > 0) {
                        (completedCount.toFloat() / totalCount.toFloat())
                    } else 0f

                    val progress = rawProgress.coerceIn(0f, 1f)
                    val subText = if (totalCount > 1) {
                        "${completedCount + 1} of $totalCount items"
                    } else {
                        if (session.totalBytes > 0L) "${(progress * 100).toInt()}%" else "1 item"
                    }

                    _uiState.update {
                        it.copy(
                            backupState = BackupState(
                                isBackingUp = true,
                                isJustCompleted = false,
                                progress = progress,
                                pendingCount = pendingCount,
                                totalCount = totalCount,
                                statusText = "Backing up…",
                                subText = subText
                            )
                        )
                    }
                } else if (wasActive) {
                    wasActive = false
                    val totalCount = lastFinishedFiles
                    _uiState.update {
                        it.copy(
                            backupState = BackupState(
                                isBackingUp = false,
                                isJustCompleted = true,
                                progress = 1f,
                                pendingCount = 0,
                                totalCount = totalCount,
                                statusText = "Backup complete",
                                subText = if (totalCount <= 1) "All items backed up" else "$totalCount items backed up"
                            )
                        )
                    }

                    completionJob?.cancel()
                    completionJob = viewModelScope.launch {
                        delay(3500)
                        _uiState.update { it.copy(backupState = BackupState()) }
                    }
                }
            }
        }

        viewModelScope.launch {
            val sessionTransferIds = mutableSetOf<Long>()
            var completionJob: Job? = null
            var wasBackingUp = false

            transferManager.getAllTransfers().collect { allTransfers ->
                // If a formal backup session is active, ignore transfer manager UI overrides
                if (_uiState.value.backupState.isBackingUp) return@collect

                val uploadTransfers = allTransfers.filter { it.type == "UPLOAD" && it.backupSessionId == null }
                val activeUploads = uploadTransfers.filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }

                if (activeUploads.isNotEmpty()) {
                    completionJob?.cancel()
                    completionJob = null
                    wasBackingUp = true

                    for (t in activeUploads) {
                        sessionTransferIds.add(t.transferId)
                    }

                    val sessionTransfers = uploadTransfers.filter { it.transferId in sessionTransferIds }
                    val totalCount = sessionTransfers.size
                    val completedCount = sessionTransfers.count { it.status == "COMPLETED" }
                    val activeCount = activeUploads.size

                    val totalBytes = sessionTransfers.sumOf { it.fileSize }
                    val transferredBytes = sessionTransfers.sumOf {
                        if (it.status == "COMPLETED") it.fileSize else it.transferredBytes
                    }

                    val rawProgress = if (totalBytes > 0L) {
                        (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat()
                    } else if (totalCount > 0) {
                        (completedCount.toFloat() / totalCount.toFloat())
                    } else 0f

                    val progress = rawProgress.coerceIn(0f, 1f)

                    val subText = if (totalCount > 1) {
                        "${completedCount + 1} of $totalCount items"
                    } else {
                        if (totalBytes > 0L) "${(progress * 100).toInt()}%" else "1 item"
                    }

                    _uiState.update {
                        it.copy(
                            backupState = BackupState(
                                isBackingUp = true,
                                isJustCompleted = false,
                                progress = progress,
                                pendingCount = activeCount,
                                totalCount = totalCount,
                                statusText = "Uploading…",
                                subText = subText
                            )
                        )
                    }
                } else if (wasBackingUp && sessionTransferIds.isNotEmpty()) {
                    val sessionTransfers = uploadTransfers.filter { it.transferId in sessionTransferIds }
                    val totalCount = sessionTransfers.size
                    val completedCount = sessionTransfers.count { it.status == "COMPLETED" }

                    wasBackingUp = false

                    if (completedCount > 0) {
                        _uiState.update {
                            it.copy(
                                backupState = BackupState(
                                    isBackingUp = false,
                                    isJustCompleted = true,
                                    progress = 1f,
                                    pendingCount = 0,
                                    totalCount = totalCount,
                                    statusText = "Upload complete",
                                    subText = if (totalCount == 1) "1 item" else "$totalCount items"
                                )
                            )
                        }

                        completionJob?.cancel()
                        completionJob = viewModelScope.launch {
                            delay(3500)
                            sessionTransferIds.clear()
                            _uiState.update {
                                it.copy(backupState = BackupState())
                            }
                        }
                    } else {
                        sessionTransferIds.clear()
                        _uiState.update {
                            it.copy(backupState = BackupState())
                        }
                    }
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun listenToMediaAndFiles(chatId: Long) {
        // Cancel any existing jobs to prevent leaks
        mediaFlowJob?.cancel()
        allFilesFlowJob?.cancel()

        mediaFlowJob = viewModelScope.launch {
            localRepository.getAllMedia(chatId)
                .debounce(150L)
                .flowOn(kotlinx.coroutines.Dispatchers.IO)
                .collect { mediaList ->
                    _uiState.update { it.copy(allMedia = mediaList) }
                    try {
                        val albums = TeleDriveApplication.instance.deviceMediaRepository.getDeviceAlbums(forceRefresh = false, cloudFiles = mediaList)
                        _uiState.update { it.copy(deviceAlbums = albums) }
                    } catch (_: Exception) {}
                    refreshUnifiedMedia()
                    // Fire-and-forget on isolated BackendDispatcher:
                    // Prevents ML face detection and inference from stealing CPU from UI or ImageLoader
                    viewModelScope.launch(com.teledrive.app.core.AppDispatchers.Backend) {
                        try {
                            TeleDriveApplication.instance.peopleRepository.scanCloudMedia(mediaList)
                        } catch (_: Exception) {}
                    }
                }
        }

        allFilesFlowJob = viewModelScope.launch {
            localRepository.getAllFiles(chatId)
                .debounce(150L)
                .flowOn(kotlinx.coroutines.Dispatchers.IO)
                .collect { fileList ->
                    _uiState.update { it.copy(allCloudFiles = fileList) }
                    refreshUnifiedMedia()
                }
        }
    }

    fun refreshUnifiedMedia(immediate: Boolean = false) {
        // Single-flight + debounced: 10 sync batches in 2s previously built 10
        // unified lists (each O(n) + Main regroup + full grid recompose).
        // On initial launch (or when immediate=true), run immediately with 0ms delay.
        unifiedRefreshJob?.cancel()
        unifiedRefreshJob = viewModelScope.launch(com.teledrive.app.core.AppDispatchers.Backend) {
            try {
                if (!immediate && _uiState.value.unifiedMedia.isNotEmpty()) {
                    try { kotlinx.coroutines.delay(200) } catch (_: Exception) { return@launch }
                }
                val cloudMedia = _uiState.value.allMedia
                val unified = TeleDriveApplication.instance.deviceMediaRepository.buildUnifiedMedia(cloudMedia)
                val prev = _uiState.value.unifiedMedia
                if (prev.size != unified.size || prev != unified) {
                    _uiState.update { it.copy(unifiedMedia = unified, isLoading = false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun renamePerson(personId: String, newName: String) {
        viewModelScope.launch {
            TeleDriveApplication.instance.peopleRepository.renamePerson(personId, newName)
        }
    }

    fun rescanPeople() {
        viewModelScope.launch {
            TeleDriveApplication.instance.peopleRepository.rescanAll(_uiState.value.allMedia)
        }
    }

    fun triggerBackup() {
        viewModelScope.launch {
            val result = TeleDriveApplication.instance.backupRepository.startBackup(com.teledrive.app.data.db.entity.BackupTrigger.MANUAL)
            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "Backup failed to start"
                com.teledrive.app.core.AppLogger.w("ExplorerViewModel", "Manual backup trigger failed: $err")
            }
        }
    }

    fun syncCurrentSource() {
        viewModelScope.launch {
            var chatId = _uiState.value.activeChatId
            if (chatId == 0L) {
                chatId = preferences.getCachedStorageChatId()
            }
            if (chatId == 0L) {
                chatId = _uiState.value.savedMessagesChatId
            }
            if (chatId == 0L) {
                try {
                    val id = channelRepository.getSavedMessagesChatId()
                    if (id != 0L) {
                        chatId = id
                        _uiState.update { it.copy(savedMessagesChatId = id, activeChatId = id) }
                    }
                } catch (_: Exception) {}
            }
            if (chatId != 0L) {
                _uiState.update { it.copy(isRefreshing = true) }
                localRepository.syncFromTelegram(chatId)
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun selectStorageSource(source: StorageSource = StorageSource.SAVED_MESSAGES) {
        viewModelScope.launch {
            val targetChatId = if (_uiState.value.savedMessagesChatId != 0L) {
                _uiState.value.savedMessagesChatId
            } else {
                try {
                    val id = channelRepository.getSavedMessagesChatId()
                    _uiState.update { it.copy(savedMessagesChatId = id) }
                    id
                } catch (_: Exception) { 0L }
            }

            _uiState.update {
                it.copy(
                    storageSource = source,
                    activeChatId = targetChatId,
                    isLoading = true
                )
            }

            listenToMediaAndFiles(targetChatId)
            loadFolder("/", targetChatId)
            if (targetChatId != 0L) {
                localRepository.syncFromTelegram(targetChatId)
            }
        }
    }

    fun loadFolder(path: String, chatId: Long = _uiState.value.activeChatId) {
        val normalizedPath = if (path.isEmpty()) "/" else path
        val segments = buildSegments(normalizedPath)

        val isPathChange = normalizedPath != _uiState.value.currentPath
        val isChatChange = chatId != _uiState.value.activeChatId

        _uiState.update {
            it.copy(
                currentPath = normalizedPath,
                pathSegments = segments,
                isLoading = isPathChange || isChatChange,
                error = null,
                selectedFiles = if (isPathChange) emptySet() else it.selectedFiles,
                isSelectionMode = if (isPathChange) false else it.isSelectionMode
            )
        }

        // Cancel previous load job to prevent duplicate subscriptions
        loadFolderJob?.cancel()
        loadFolderJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val filesFlow = localRepository.getFilesInFolder(normalizedPath, chatId)
            val foldersFlow = localRepository.getFolders(normalizedPath, chatId)

            // Use a single-shot collect to avoid multiple subscriptions
            val combinedFlow = combine(filesFlow, foldersFlow) { files, folders ->
                Pair(files, folders)
            }.distinctUntilChanged { prev, next ->
                prev.first == next.first && prev.second == next.second
            }.flowOn(kotlinx.coroutines.Dispatchers.IO)

            combinedFlow.collect { (files, folders) ->
                // Filtering/sorting is O(n log n); keep it off the Main thread.
                val snapshot = _uiState.value
                val filteredFiles = filterFiles(files, snapshot.fileTypeFilter, snapshot.searchQuery)
                val sortedFiles = sortFiles(filteredFiles, snapshot.sortBy)
                val filteredFolders = if (snapshot.searchQuery.isNotEmpty()) {
                    folders.filter { it.folderName.contains(snapshot.searchQuery, ignoreCase = true) }
                } else folders

                _uiState.update { state ->
                    state.copy(
                        files = sortedFiles,
                        folders = filteredFolders,
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            }
        }
    }

    fun navigateToFolder(path: String) {
        loadFolder(path, _uiState.value.activeChatId)
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
        if (current == "/" || current.isEmpty()) return false
        val parent = current.substringBeforeLast('/')
        val target = if (parent.isEmpty()) "/" else parent
        loadFolder(target, _uiState.value.activeChatId)
        return true
    }

    fun refresh() {
        val chatId = _uiState.value.activeChatId
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            if (chatId != 0L) {
                localRepository.syncFromTelegram(chatId)
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun toggleViewMode() {
        val newMode = if (_uiState.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _uiState.update { it.copy(viewMode = newMode) }
        viewModelScope.launch {
            preferences.setViewMode(if (newMode == ViewMode.LIST) "list" else "grid")
        }
    }

    fun setSortBy(sortBy: SortBy) {
        _uiState.update { state ->
            state.copy(
                sortBy = sortBy,
                files = sortFiles(state.files, sortBy)
            )
        }
    }

    fun setFileTypeFilter(filter: FileTypeFilter) {
        _uiState.update { it.copy(fileTypeFilter = filter) }
        reapplyFilters()
    }

    fun setSearchQuery(query: String) {
        // Debounce keystrokes: the old code re-queried the whole DB on every
        // character (getAll().first() + filter on the calling thread), dropping
        // frames while typing in Files/Search.
        searchDebounceJob?.cancel()
        if (query == _uiState.value.searchQuery) return
        searchDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            _uiState.update { it.copy(searchQuery = query) }
            reapplyFilters()
        }
    }

    private fun reapplyFilters() {
        val state = _uiState.value
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            localRepository.getFilesInFolder(state.currentPath, state.activeChatId).first()
                .let { files ->
                    val filtered = filterFiles(files, state.fileTypeFilter, state.searchQuery)
                    val sorted = sortFiles(filtered, state.sortBy)
                    val folders = localRepository.getFolders(state.currentPath, state.activeChatId).first()
                    val filteredFolders = if (state.searchQuery.isNotEmpty()) {
                        folders.filter { it.folderName.contains(state.searchQuery, ignoreCase = true) }
                    } else folders
                    _uiState.update {
                        it.copy(
                            files = sorted,
                            folders = filteredFolders,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun toggleFileSelection(fileId: Long) {
        _uiState.update { state ->
            val updated = if (state.selectedFiles.contains(fileId)) {
                state.selectedFiles - fileId
            } else {
                state.selectedFiles + fileId
            }
            state.copy(
                selectedFiles = updated,
                isSelectionMode = updated.isNotEmpty()
            )
        }
    }

    fun selectAllFiles() {
        _uiState.update { state ->
            val allIds = state.files.map { it.fileId }.toSet()
            state.copy(
                selectedFiles = allIds,
                isSelectionMode = allIds.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(selectedFiles = emptySet(), isSelectionMode = false)
        }
    }

    fun uploadFiles(uris: List<Uri>) {
        val currentPath = _uiState.value.currentPath
        val distinctUris = uris.distinct()
        viewModelScope.launch {
            var targetChatId = _uiState.value.activeChatId
            if (targetChatId == 0L) {
                targetChatId = preferences.getCachedStorageChatId()
            }
            if (targetChatId == 0L) {
                targetChatId = _uiState.value.savedMessagesChatId
            }
            if (targetChatId == 0L) {
                targetChatId = channelRepository.getSavedMessagesChatId()
            }
            if (targetChatId == 0L) {
                targetChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
            }
            if (targetChatId != 0L) {
                for (uri in distinctUris) {
                    transferManager.enqueueUpload(uri, currentPath, targetChatId)
                }
            }
        }
    }

    fun downloadFile(file: FileEntity) {
        viewModelScope.launch {
            transferManager.enqueueDownload(
                virtualPath = file.virtualPath,
                fileName = file.fileName,
                fileSize = file.fileSize,
                chatId = file.telegramChatId,
                messageId = file.telegramMessageId
            )
        }
    }

    fun downloadSelectedFiles() {
        val selectedIds = _uiState.value.selectedFiles
        val selectedFileList = _uiState.value.files.filter { selectedIds.contains(it.fileId) }
        viewModelScope.launch {
            for (file in selectedFileList) {
                transferManager.enqueueDownload(
                    virtualPath = file.virtualPath,
                    fileName = file.fileName,
                    fileSize = file.fileSize,
                    chatId = file.telegramChatId,
                    messageId = file.telegramMessageId
                )
            }
            clearSelection()
        }
    }

    fun deleteSelectedFiles() {
        val selectedIds = _uiState.value.selectedFiles
        val selectedFileList = _uiState.value.files.filter { selectedIds.contains(it.fileId) }
        viewModelScope.launch {
            for (file in selectedFileList) {
                localRepository.deleteFile(file)
            }
            clearSelection()
        }
    }

    fun deleteFile(file: FileEntity) {
        viewModelScope.launch {
            localRepository.deleteFile(file)
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            localRepository.deleteFolder(folder)
            loadFolder(_uiState.value.currentPath, _uiState.value.activeChatId)
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        val currentPath = _uiState.value.currentPath
        val chatId = _uiState.value.activeChatId
        viewModelScope.launch {
            localRepository.createFolder(name.trim(), currentPath, chatId)
            loadFolder(currentPath, chatId)
        }
    }

    fun renameFile(fileId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            localRepository.renameFile(fileId, newName.trim())
            loadFolder(_uiState.value.currentPath, _uiState.value.activeChatId)
        }
    }

    private fun buildSegments(path: String): List<PathSegment> {
        if (path == "/" || path.isEmpty()) {
            return listOf(PathSegment("/", "Home"))
        }
        val segments = mutableListOf(PathSegment("/", "Home"))
        val parts = path.trim('/').split('/')
        var accumulated = ""
        for (part in parts) {
            accumulated += "/$part"
            segments.add(PathSegment(accumulated, part))
        }
        return segments
    }

    private fun filterFiles(files: List<FileEntity>, filter: FileTypeFilter, query: String): List<FileEntity> {
        var result = files
        if (query.isNotEmpty()) {
            result = result.filter { it.fileName.contains(query, ignoreCase = true) }
        }
        return when (filter) {
            FileTypeFilter.ALL -> result
            FileTypeFilter.IMAGES -> result.filter { it.mimeType.startsWith("image/") }
            FileTypeFilter.VIDEOS -> result.filter { it.mimeType.startsWith("video/") }
            FileTypeFilter.AUDIO -> result.filter { it.mimeType.startsWith("audio/") }
            FileTypeFilter.DOCUMENTS -> result.filter {
                it.mimeType.startsWith("application/") || it.mimeType.startsWith("text/")
            }
            FileTypeFilter.ARCHIVES -> result.filter {
                it.mimeType.contains("zip") || it.mimeType.contains("tar") ||
                it.mimeType.contains("rar") || it.mimeType.contains("7z") ||
                it.mimeType.contains("compressed")
            }
        }
    }

    private fun sortFiles(files: List<FileEntity>, sortBy: SortBy): List<FileEntity> {
        return when (sortBy) {
            SortBy.NAME_ASC -> files.sortedBy { it.fileName.lowercase() }
            SortBy.NAME_DESC -> files.sortedByDescending { it.fileName.lowercase() }
            SortBy.DATE_ASC -> files.sortedBy { it.uploadTimestamp }
            SortBy.DATE_DESC -> files.sortedByDescending { it.uploadTimestamp }
            SortBy.SIZE_ASC -> files.sortedBy { it.fileSize }
            SortBy.SIZE_DESC -> files.sortedByDescending { it.fileSize }
        }
    }

    fun loadDeviceAlbums(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAlbums = true) }
            try {
                val cloudMedia = _uiState.value.allMedia
                val albums = TeleDriveApplication.instance.deviceMediaRepository.getDeviceAlbums(
                    forceRefresh,
                    cloudFiles = if (cloudMedia.isNotEmpty()) cloudMedia else null
                )
                _uiState.update { it.copy(deviceAlbums = albums, isLoadingAlbums = false) }
                refreshUnifiedMedia(immediate = false)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingAlbums = false) }
            }
        }
    }

    fun uploadLocalMediaItems(items: List<com.teledrive.app.data.repository.LocalMediaItem>, targetVirtualPath: String = "/") {
        viewModelScope.launch {
            var targetChatId = _uiState.value.activeChatId
            if (targetChatId == 0L) {
                targetChatId = preferences.getCachedStorageChatId()
            }
            if (targetChatId == 0L) {
                targetChatId = _uiState.value.savedMessagesChatId
            }
            if (targetChatId == 0L) {
                targetChatId = channelRepository.getSavedMessagesChatId()
            }
            if (targetChatId == 0L) {
                targetChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
            }
            if (targetChatId != 0L) {
                for (item in items) {
                    transferManager.enqueueUpload(item.contentUri, targetVirtualPath, targetChatId)
                }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                TeleDriveApplication.instance.tdLibManager.logout()
            } catch (e: Exception) {}
            try {
                val db = TeleDriveApplication.instance.database
                db.fileDao().clearAll()
                db.folderDao().clearAll()
                db.transferDao().clearAll()
                TeleDriveApplication.instance.thumbnailCacheManager.clearAll()
            } catch (_: Exception) {}
            try {
                preferences.clear()
            } catch (_: Exception) {}
            onLoggedOut()
        }
    }

    fun moveToTrash(items: List<com.teledrive.app.data.repository.UnifiedMediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            TeleDriveApplication.instance.trashManager.moveToTrash(items)
            TeleDriveApplication.instance.deviceMediaRepository.invalidateCache()
            loadDeviceAlbums(forceRefresh = true)
            refreshUnifiedMedia()
        }
    }

    fun restoreFromTrash(items: List<com.teledrive.app.data.repository.UnifiedMediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            TeleDriveApplication.instance.trashManager.restore(items)
            TeleDriveApplication.instance.deviceMediaRepository.invalidateCache()
            loadDeviceAlbums(forceRefresh = true)
            refreshUnifiedMedia()
        }
    }

    fun permanentlyDeleteTrash(items: List<com.teledrive.app.data.repository.UnifiedMediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            TeleDriveApplication.instance.trashManager.deletePermanently(items)
            TeleDriveApplication.instance.deviceMediaRepository.invalidateCache()
            loadDeviceAlbums(forceRefresh = true)
            refreshUnifiedMedia()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            TeleDriveApplication.instance.trashManager.emptyTrash()
            TeleDriveApplication.instance.deviceMediaRepository.invalidateCache()
            loadDeviceAlbums(forceRefresh = true)
            refreshUnifiedMedia()
        }
    }
}
