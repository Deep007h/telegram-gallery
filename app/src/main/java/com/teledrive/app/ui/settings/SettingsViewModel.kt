package com.teledrive.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.repository.StorageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userDisplayName: String = "Telegram User",
    val phoneNumber: String = "",
    val username: String = "",
    val profilePhotoPath: String? = null,
    val themeMode: String = "dark",
    val storageStats: StorageStats = StorageStats(0, 0L, 0, 0, 0, 0, 0),
    val appVersion: String = "1.1.1",
    val storageChatId: Long = 0L,
    val storageChatTitle: String = "Saved Messages",
    val availableChats: List<com.teledrive.app.telegram.TdLibManager.TelegramChatSummary> = emptyList(),
    val isLoadingChats: Boolean = false,
    val isSyncing: Boolean = false,
    val loginType: String = "phone",
    val isBotMode: Boolean = false
)

class SettingsViewModel : ViewModel() {

    private val tdLibManager = TeleDriveApplication.instance.tdLibManager
    private val localRepository = TeleDriveApplication.instance.localRepository
    private val preferences = TeleDriveApplication.instance.preferences

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val savedTheme = preferences.themeMode.first()
            val customPhoto = preferences.customProfilePhotoPath.first()
            val savedName = preferences.userDisplayName.first()
            val isLocalGallery = preferences.localGalleryMode.first()
            val telegramPhoto = preferences.telegramProfilePhotoPath.first()
            val loginType = preferences.loginType.first()
            val botToken = preferences.botToken.first()
            val isUserLoggedIn = (tdLibManager.authState.value is com.teledrive.app.telegram.TdLibAuthState.Ready) || (loginType == "web")
            val isBot = (loginType == "bot" && !isUserLoggedIn) || (!isUserLoggedIn && botToken.isNotBlank() && loginType.isBlank())

            val effectiveInitialPhoto = when {
                customPhoto.isNotBlank() && !customPhoto.contains("profile_photo_") && !customPhoto.contains("telegram_avatar_") && java.io.File(customPhoto).exists() -> customPhoto
                telegramPhoto.isNotBlank() && java.io.File(telegramPhoto).exists() -> telegramPhoto
                else -> null
            }

            var storedChatId = preferences.storageChatId.first() ?: preferences.getCachedStorageChatId()
            var storedChatTitle = preferences.storageChatTitle.first().ifBlank { preferences.getCachedStorageChatTitle() }

            if (storedChatId == 0L && !isBot) {
                try {
                    val savedId = tdLibManager.getSavedMessagesChatId()
                    if (savedId != 0L) {
                        storedChatId = savedId
                        storedChatTitle = "Saved Messages"
                        preferences.setStorageChatId(savedId)
                        preferences.setStorageChatTitle("Saved Messages")
                    }
                } catch (_: Exception) {}
            }

            if (storedChatId == 0L && isBot && botToken.isNotBlank()) {
                val botChatIdStr = preferences.botChatId.first().ifBlank { preferences.getCachedBotChatId() }
                if (botChatIdStr.isNotBlank() && botChatIdStr.toLongOrNull() != null) {
                    storedChatId = botChatIdStr.toLong()
                    storedChatTitle = "Personal Chat ($storedChatId)"
                } else {
                    val discovered = tdLibManager.fetchBotUpdatesChats(botToken)
                    val firstChat = discovered.firstOrNull()
                    if (firstChat != null) {
                        storedChatId = firstChat.id
                        storedChatTitle = firstChat.title
                        preferences.setBotChatId(firstChat.id.toString())
                        preferences.setStorageChatId(firstChat.id)
                        preferences.setStorageChatTitle(firstChat.title)
                        preferences.addLinkedChat(firstChat)
                    }
                }
            }

            val effectiveChatId = when {
                storedChatId != 0L -> storedChatId
                tdLibManager.cachedSavedMessagesChatId != 0L -> tdLibManager.cachedSavedMessagesChatId
                else -> com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
            }
            val effectiveChatTitle = if (storedChatTitle.isNotBlank()) storedChatTitle else (if (isBot) "Storage Chat" else "Saved Messages")

            _uiState.update {
                it.copy(
                    themeMode = savedTheme,
                    userDisplayName = savedName.ifBlank { if (isLocalGallery) "Local Gallery" else (if (isBot) "Storage Bot User" else "Telegram User") },
                    profilePhotoPath = effectiveInitialPhoto,
                    phoneNumber = if (isLocalGallery) "Device Local Storage" else (if (isBot) "Connected via Bot API" else "Telegram Cloud"),
                    storageChatId = effectiveChatId,
                    storageChatTitle = effectiveChatTitle,
                    loginType = loginType,
                    isBotMode = isBot
                )
            }

            try {
                val user = tdLibManager.getMeUser()
                val name = listOf(user.firstName, user.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                    .ifBlank {
                        user.usernames?.activeUsernames?.firstOrNull()?.removePrefix("@")
                            ?: "Telegram User"
                    }
                val phone = user.phoneNumber.ifBlank { "Connected via Telegram" }
                val uname = user.usernames?.activeUsernames?.firstOrNull() ?: ""

                val downloaded = tdLibManager.fetchAndDownloadTelegramProfilePhoto(TeleDriveApplication.instance)
                val photoPath = if (downloaded != null && downloaded.exists()) {
                    val path = downloaded.absolutePath
                    preferences.setTelegramProfilePhotoPath(path)
                    path
                } else {
                    _uiState.value.profilePhotoPath
                }

                _uiState.update {
                    it.copy(
                        userDisplayName = if (savedName.isNotBlank()) savedName else name,
                        phoneNumber = phone,
                        username = uname,
                        profilePhotoPath = photoPath
                    )
                }
            } catch (e: Exception) {
                val isLocal = preferences.localGalleryMode.first()
                _uiState.update {
                    it.copy(
                        phoneNumber = if (isLocal) "Device Local Storage" else "Telegram Cloud"
                    )
                }
            }

            localRepository.getStorageStats().collect { stats ->
                _uiState.update { it.copy(storageStats = stats) }
            }
        }

        viewModelScope.launch {
            tdLibManager.myProfilePhotoPath.collect { path ->
                if (!path.isNullOrBlank() && java.io.File(path).exists()) {
                    _uiState.update { it.copy(profilePhotoPath = path) }
                }
            }
        }

        viewModelScope.launch {
            preferences.telegramProfilePhotoPath.collect { path ->
                if (path.isNotBlank() && java.io.File(path).exists()) {
                    val customPhoto = preferences.customProfilePhotoPath.first()
                    if (customPhoto.isBlank() || !java.io.File(customPhoto).exists()) {
                        _uiState.update { it.copy(profilePhotoPath = path) }
                    }
                }
            }
        }

        viewModelScope.launch {
            preferences.userDisplayName.collect { name ->
                if (name.isNotBlank() && name != "undefined" && name != "null") {
                    _uiState.update { it.copy(userDisplayName = name) }
                }
            }
        }
    }

    fun setCustomProfilePhoto(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.filesDir.listFiles { f -> f.name.startsWith("custom_avatar") }?.forEach { it.delete() }
                val file = java.io.File(context.filesDir, "custom_avatar_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                if (file.exists() && file.length() > 0) {
                    val path = file.absolutePath
                    preferences.setCustomProfilePhotoPath(path)
                    _uiState.update { it.copy(profilePhotoPath = path) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeCustomProfilePhoto(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.filesDir.listFiles { f -> f.name.startsWith("custom_avatar") }?.forEach { it.delete() }
                preferences.setCustomProfilePhotoPath("")
                val telegramPhoto = preferences.telegramProfilePhotoPath.first()
                val resetPath = if (telegramPhoto.isNotBlank() && java.io.File(telegramPhoto).exists()) {
                    telegramPhoto
                } else {
                    val downloaded = tdLibManager.fetchAndDownloadTelegramProfilePhoto(context)
                    downloaded?.absolutePath
                }
                _uiState.update { it.copy(profilePhotoPath = resetPath) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            _uiState.update { it.copy(userDisplayName = trimmed) }
            viewModelScope.launch {
                preferences.setUserDisplayName(trimmed)
            }
        }
    }

    fun setThemeMode(mode: String) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    private val otaUpdateManager = TeleDriveApplication.instance.otaUpdateManager
    val otaUpdateState: StateFlow<com.teledrive.app.core.ota.OtaUpdateState> = otaUpdateManager.updateState
    val otaUpdateUrl: Flow<String> = preferences.otaUpdateUrl
    val autoCheckUpdates: Flow<Boolean> = preferences.autoCheckUpdates

    val currentVersionName: String get() = otaUpdateManager.currentVersionName
    val currentVersionCode: Int get() = otaUpdateManager.currentVersionCode

    fun checkForUpdates() {
        otaUpdateManager.checkForUpdates(force = true)
    }

    fun startDownload(info: com.teledrive.app.core.ota.UpdateInfo) {
        otaUpdateManager.startDownload(info)
    }

    fun cancelDownload() {
        otaUpdateManager.cancelDownload()
    }

    fun dismissUpdate() {
        otaUpdateManager.dismiss()
    }

    fun installUpdate(apkFile: java.io.File) {
        otaUpdateManager.promptInstall(apkFile)
    }

    fun setOtaUpdateUrl(url: String) {
        viewModelScope.launch {
            preferences.setOtaUpdateUrl(url.trim())
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoCheckUpdates(enabled)
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            try {
                tdLibManager.logout()
            } catch (ignored: Exception) {}
            // Clear local caches so the next account never sees the previous
            // user's file index (privacy leak in the old flow which kept Room).
            try {
                val db = TeleDriveApplication.instance.database
                db.fileDao().clearAll()
                db.folderDao().clearAll()
                db.transferDao().clearAll()
                TeleDriveApplication.instance.thumbnailCacheManager.clearAll()
            } catch (_: Exception) {}
            try {
                preferences.clear()
            } catch (ignored: Exception) {}
            onLoggedOut()
        }
    }

    fun loadAvailableChats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingChats = true) }
            try {
                val chats = tdLibManager.getAvailableStorageChats()
                _uiState.update { it.copy(availableChats = chats, isLoadingChats = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingChats = false) }
            }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchChats(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            try {
                val serverChats = tdLibManager.searchTelegramChats(trimmed)
                if (serverChats.isNotEmpty()) {
                    _uiState.update { current ->
                        val existingIds = current.availableChats.map { it.id }.toSet()
                        val newChats = serverChats.filter { it.id !in existingIds }
                        if (newChats.isNotEmpty()) {
                            current.copy(availableChats = current.availableChats + newChats)
                        } else {
                            current
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun linkTelegramChat(input: String, onResult: (Boolean, String) -> Unit) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            onResult(false, "Please enter a valid link, @username, or chat ID")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingChats = true) }
            try {
                val chat = tdLibManager.resolveTelegramChat(trimmed)
                if (chat != null) {
                    preferences.setStorageChatId(chat.id)
                    preferences.setStorageChatTitle(chat.title)
                    preferences.addLinkedChat(chat)
                    _uiState.update {
                        it.copy(
                            storageChatId = chat.id,
                            storageChatTitle = chat.title,
                            availableChats = listOf(chat) + it.availableChats.filter { c -> c.id != chat.id },
                            isLoadingChats = false,
                            isSyncing = true
                        )
                    }
                    try {
                        localRepository.syncFromTelegram(chat.id)
                    } catch (_: Exception) {}
                    _uiState.update { it.copy(isSyncing = false) }
                    onResult(true, "Linked to \"${chat.title}\"! Sync started.")
                } else {
                    _uiState.update { it.copy(isLoadingChats = false) }
                    val isBot = _uiState.value.isBotMode
                    onResult(false, if (isBot) "Could not find chat. For channels or groups with bots, please make sure the bot is added as an Administrator." else "Could not find chat. Please check that the link, @username, or chat ID is correct.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingChats = false) }
                onResult(false, "Error linking chat: ${e.message}")
            }
        }
    }

    fun selectStorageChat(chat: com.teledrive.app.telegram.TdLibManager.TelegramChatSummary) {
        viewModelScope.launch {
            preferences.setStorageChatId(chat.id)
            preferences.setStorageChatTitle(chat.title)
            _uiState.update {
                it.copy(
                    storageChatId = chat.id,
                    storageChatTitle = chat.title,
                    isSyncing = true
                )
            }
            try {
                localRepository.syncFromTelegram(chat.id)
            } catch (_: Exception) {}
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun triggerSyncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val chatId = _uiState.value.storageChatId
            try {
                localRepository.syncFromTelegram(chatId)
            } catch (_: Exception) {}
            _uiState.update { it.copy(isSyncing = false) }
        }
    }
}
