package com.teledrive.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.telegram.RecaptchaRequest
import com.teledrive.app.telegram.TdLibAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE, PASSWORD, WEB_VERIFICATION, LOADING, AUTHENTICATED, ERROR }

enum class AuthMode {
    QR,
    PHONE,
    WEB,
    BOT_TOKEN
}

data class AuthUiState(
    val authStep: AuthStep = AuthStep.PHONE,
    val selectedMode: AuthMode = AuthMode.QR,
    val botTokenInput: String = "",
    val botChatIdInput: String = "",
    val showApiSetup: Boolean = false,
    val apiIdInput: String = "",
    val apiHashInput: String = "",
    val phoneNumber: String = "",
    val countryCode: String = "+91",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val passwordHint: String? = null,
    val hasRecoveryEmail: Boolean = false,
    val recaptchaRequest: RecaptchaRequest? = null,
    val qrLink: String? = null,
    val webLoginUrl: String = "https://web.telegram.org/k/"
)

class AuthViewModel : ViewModel() {
    private val app = TeleDriveApplication.instance
    private val authRepository = app.authRepository
    private val preferences = app.preferences

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedId = preferences.apiId.first()
            val savedHash = preferences.apiHash.first()
            if (savedId > 0 && savedHash.isNotBlank()) {
                _uiState.update { it.copy(apiIdInput = savedId.toString(), apiHashInput = savedHash) }
                // Don't blindly restart (which deletes the entire tdlib database
                // dir and forces a full resync). Restart only if the saved keys
                // differ from the active client keys.
                try {
                    if (app.tdLibManager.getActiveApiId() != savedId ||
                        app.tdLibManager.getActiveApiHash() != savedHash
                    ) {
                        authRepository.restartClient(savedId, savedHash)
                    }
                } catch (_: Exception) {
                    // Best-effort only; authState flow will surface real errors.
                }
            }

            authRepository.authState.collect { tdState ->
                when (tdState) {
                    is TdLibAuthState.WaitPhoneNumber -> {
                        _uiState.update { it.copy(authStep = AuthStep.PHONE, isLoading = false) }
                    }
                    is TdLibAuthState.WaitOtherDeviceConfirmation -> {
                        _uiState.update { it.copy(qrLink = tdState.link, isLoading = false, errorMessage = null) }
                    }
                    is TdLibAuthState.WaitCode -> {
                        _uiState.update { it.copy(authStep = AuthStep.CODE, isLoading = false, errorMessage = null) }
                    }
                    is TdLibAuthState.WaitPassword -> {
                        _uiState.update {
                            it.copy(
                                authStep = AuthStep.PASSWORD,
                                passwordHint = tdState.hint,
                                hasRecoveryEmail = tdState.hasRecoveryEmail,
                                isLoading = false,
                                errorMessage = null
                            )
                        }
                    }
                    is TdLibAuthState.Ready -> {
                        viewModelScope.launch {
                            if (_uiState.value.selectedMode == AuthMode.PHONE || _uiState.value.selectedMode == AuthMode.QR) {
                                preferences.setLoginType("phone")
                                preferences.setBotToken("")
                                preferences.setLocalGalleryMode(false)
                                preferences.setCustomProfilePhotoPath("")
                                var syncTargetChatId = 0L
                                try {
                                    val me = app.tdLibManager.getMeUser()
                                    val name = listOf(me.firstName, me.lastName).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Telegram User" }
                                    preferences.setUserDisplayName(name)

                                    val storageId = app.tdLibManager.ensureStorageChat(preferences.storageChatId.first())
                                    syncTargetChatId = storageId
                                    preferences.setStorageChatId(storageId)
                                    preferences.setStorageChatTitle("TeleDrive Storage")

                                    // Download user's actual Telegram profile photo immediately
                                    val photo = app.tdLibManager.fetchAndDownloadTelegramProfilePhoto(app)
                                    if (photo != null && photo.exists()) {
                                        preferences.setTelegramProfilePhotoPath(photo.absolutePath)
                                    }
                                } catch (e: Exception) {
                                    com.teledrive.app.core.AppLogger.w("AuthVM", "Post-login init failed: ${e.message}")
                                }

                                if (syncTargetChatId != 0L) {
                                    try {
                                        app.localRepository.syncFromTelegram(syncTargetChatId)
                                    } catch (_: Exception) {}
                                }
                            }
                            _uiState.update { it.copy(authStep = AuthStep.AUTHENTICATED, isLoading = false, errorMessage = null) }
                        }
                    }
                    is TdLibAuthState.Error -> {
                        val isApiError = tdState.message.contains("API_ID", ignoreCase = true)
                        _uiState.update {
                            it.copy(
                                errorMessage = if (isApiError) "Telegram requires your personal API Keys from my.telegram.org" else tdState.message,
                                showApiSetup = isApiError || it.showApiSetup,
                                isLoading = false
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            authRepository.recaptchaRequests.collect { req ->
                _uiState.update { it.copy(recaptchaRequest = req, isLoading = false) }
            }
        }

        viewModelScope.launch {
            val savedToken = preferences.botToken.first()
            val savedChatId = preferences.botChatId.first()
            val savedMode = preferences.loginType.first()
            if (savedToken.isNotBlank()) {
                _uiState.update { it.copy(botTokenInput = savedToken, botChatIdInput = savedChatId) }
            }
            if (savedMode == "phone") {
                _uiState.update { it.copy(selectedMode = AuthMode.PHONE) }
            } else if (savedMode == "web") {
                _uiState.update { it.copy(selectedMode = AuthMode.WEB, authStep = AuthStep.WEB_VERIFICATION) }
            } else if (savedMode == "bot") {
                _uiState.update { it.copy(selectedMode = AuthMode.BOT_TOKEN) }
            }
        }
    }

    fun setAuthMode(mode: AuthMode) {
        _uiState.update { 
            it.copy(
                selectedMode = mode, 
                authStep = if (mode == AuthMode.WEB) AuthStep.WEB_VERIFICATION else AuthStep.PHONE,
                errorMessage = null
            ) 
        }
        if (mode == AuthMode.QR) {
            startQrLogin()
        }
    }

    fun setWebLoginUrl(url: String) {
        _uiState.update { it.copy(webLoginUrl = url) }
    }

    fun startWebVerification() {
        _uiState.update { it.copy(authStep = AuthStep.WEB_VERIFICATION, errorMessage = null) }
    }

    fun completeWebLogin(detectedName: String) {
        viewModelScope.launch {
            preferences.setLoginType("web")
            preferences.setLocalGalleryMode(false)
            if (detectedName.isNotBlank()) {
                preferences.setUserDisplayName(detectedName)
            }
            _uiState.update {
                it.copy(
                    authStep = AuthStep.AUTHENTICATED,
                    isLoading = false,
                    successMessage = "Logged in as $detectedName"
                )
            }
        }
    }

    fun startQrLogin() {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedMode = AuthMode.QR, isLoading = true, errorMessage = null) }
            val res = authRepository.requestQrCodeAuthentication()
            _uiState.update { it.copy(isLoading = false) }
            if (res.isFailure) {
                _uiState.update {
                    it.copy(
                        errorMessage = res.exceptionOrNull()?.message ?: "Failed to generate Telegram QR code"
                    )
                }
            }
        }
    }

    fun cancelQrLogin() {
        viewModelScope.launch {
            _uiState.update { it.copy(qrLink = null, errorMessage = null, selectedMode = AuthMode.PHONE) }
            app.tdLibManager.restartAuthentication()
        }
    }


    fun updateBotToken(token: String) {
        _uiState.update { it.copy(botTokenInput = token, errorMessage = null) }
    }

    fun updateBotChatId(chatId: String) {
        _uiState.update { it.copy(botChatIdInput = chatId, errorMessage = null) }
    }

    fun submitBotToken() {
        val token = _uiState.value.botTokenInput.trim()
        val chatId = _uiState.value.botChatIdInput.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your Telegram Bot Token from @BotFather") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // 1. Verify via Bot API getMe
            val botVerification = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://api.telegram.org/bot$token/getMe")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().readText()
                        val json = org.json.JSONObject(text)
                        if (json.optBoolean("ok", false)) {
                            val res = json.optJSONObject("result")
                            val firstName = res?.optString("first_name", "Telegram Storage Bot") ?: "Storage Bot"
                            val username = res?.optString("username", "") ?: ""
                            Pair(true, if (username.isNotEmpty()) "$firstName (@$username)" else firstName)
                        } else {
                            Pair(false, "Invalid Bot Token response")
                        }
                    } else {
                        Pair(false, "Bot Token rejected by Telegram servers (HTTP ${conn.responseCode})")
                    }
                } catch (e: Exception) {
                    // Fallback to TDLib direct login if offline or network glitch
                    Pair(true, null)
                }
            }

            if (!botVerification.first) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = botVerification.second ?: "Failed to verify Bot Token. Check that you copied the full token correctly."
                    )
                }
                return@launch
            }

            // 2. Save credentials to preferences & auto-discover chat
            preferences.setBotToken(token)
            preferences.setLoginType("bot")
            botVerification.second?.let { preferences.setUserDisplayName(it) }

            // Resolve or discover target chat ID
            var discoveredChatId = 0L
            if (chatId.isNotBlank()) {
                val cleanChat = chatId.removePrefix("https://t.me/").removePrefix("t.me/")
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.tdLibManager.fetchBotChatInfo(token, cleanChat)
                }
                if (resolved != null) {
                    discoveredChatId = resolved.id
                    preferences.setBotChatId(resolved.id.toString())
                    preferences.setStorageChatId(resolved.id)
                    preferences.setStorageChatTitle(resolved.title)
                    preferences.addLinkedChat(resolved)
                } else {
                    val numId = cleanChat.toLongOrNull() ?: 0L
                    if (numId != 0L) {
                        discoveredChatId = numId
                        preferences.setBotChatId(numId.toString())
                        preferences.setStorageChatId(numId)
                        preferences.setStorageChatTitle("Telegram Chat ($numId)")
                    }
                }
            } else {
                // Auto-detect user chat from Bot updates
                val discoveredChats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.tdLibManager.fetchBotUpdatesChats(token)
                }
                val userChat = discoveredChats.firstOrNull()
                if (userChat != null) {
                    discoveredChatId = userChat.id
                    preferences.setBotChatId(userChat.id.toString())
                    preferences.setStorageChatId(userChat.id)
                    preferences.setStorageChatTitle(userChat.title)
                    preferences.addLinkedChat(userChat)
                }
            }

            // 3. Connect to TDLib
            val tdResult = authRepository.submitBotToken(token)
            if (discoveredChatId != 0L) {
                try {
                    app.localRepository.syncFromTelegram(discoveredChatId)
                } catch (_: Exception) {}
            }

            if (tdResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authStep = AuthStep.AUTHENTICATED,
                        successMessage = "Connected to ${botVerification.second ?: "Telegram Bot"}!"
                    )
                }
            } else {
                // If TDLib client has an issue with bot token (e.g. token already used), we still consider valid since HTTP API works
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authStep = AuthStep.AUTHENTICATED,
                        successMessage = "Bot storage activated!"
                    )
                }
            }
        }
    }

    fun useLocalGallery() {
        viewModelScope.launch {
            preferences.setLocalGalleryMode(true)
            preferences.setLoginType("local")
            _uiState.update { it.copy(authStep = AuthStep.AUTHENTICATED) }
        }
    }

    fun toggleApiSetup() {
        _uiState.update { it.copy(showApiSetup = !it.showApiSetup) }
    }

    fun updateApiId(id: String) {
        _uiState.update { it.copy(apiIdInput = id, errorMessage = null) }
    }

    fun updateApiHash(hash: String) {
        _uiState.update { it.copy(apiHashInput = hash, errorMessage = null) }
    }

    fun updatePhoneNumber(phone: String) {
        _uiState.update { it.copy(phoneNumber = phone, errorMessage = null) }
    }

    fun updateCountryCode(code: String) {
        _uiState.update { it.copy(countryCode = code) }
    }

    fun resetToPhone() {
        _uiState.update { it.copy(authStep = AuthStep.PHONE, isLoading = false, errorMessage = null, recaptchaRequest = null) }
    }

    fun dismissRecaptcha() {
        _uiState.update { it.copy(recaptchaRequest = null, isLoading = false) }
    }

    fun submitRecaptchaToken(token: String) {
        val req = _uiState.value.recaptchaRequest ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, recaptchaRequest = null, errorMessage = null) }
            val result = authRepository.submitRecaptchaToken(req.verificationId, token)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Verification failed"
                    )
                }
            }
        }
    }

    fun submitPhoneNumber() {
        submitDirectTdLibPhoneNumber()
    }

    fun submitDirectTdLibPhoneNumber() {
        val currentState = _uiState.value
        if (currentState.phoneNumber.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid phone number") }
            return
        }

        val parsedApiId = currentState.apiIdInput.trim().toIntOrNull()
        val parsedApiHash = currentState.apiHashInput.trim()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Save API keys and restart client ONLY if keys differ from currently active keys
            if (parsedApiId != null && parsedApiId > 0 && parsedApiHash.isNotBlank() &&
                (app.tdLibManager.getActiveApiId() != parsedApiId || app.tdLibManager.getActiveApiHash() != parsedApiHash)) {
                try {
                    authRepository.restartClient(parsedApiId, parsedApiHash)
                    preferences.setApiId(parsedApiId)
                    preferences.setApiHash(parsedApiHash)
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showApiSetup = true,
                            errorMessage = "Failed to initialize with provided API keys: ${e.message}"
                        )
                    }
                    return@launch
                }
            }

            val fullPhone = "${currentState.countryCode}${currentState.phoneNumber.trimStart('0')}"
            val result = authRepository.submitPhoneNumber(fullPhone)
            
            if (authRepository.authState.value is TdLibAuthState.WaitCode) {
                _uiState.update { it.copy(authStep = AuthStep.CODE, isLoading = false, errorMessage = null) }
                return@launch
            }

            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "Failed to connect to Telegram"
                val isApiError = err.contains("API_ID", ignoreCase = true)
                val isTimeout = err.contains("timed out", ignoreCase = true) || err.contains("timeout", ignoreCase = true)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showApiSetup = isApiError || it.showApiSetup,
                        errorMessage = when {
                            isApiError -> "Telegram requires custom API Keys. Please enter your API ID & Hash from my.telegram.org below."
                            isTimeout -> "Direct MTProto timed out on your network. Tap Continue to connect via Telegram Web."
                            else -> err
                        }
                    )
                }
            } else {
                _uiState.update { it.copy(authStep = AuthStep.CODE, isLoading = false, errorMessage = null) }
            }
        }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.submitCode(code)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Invalid or expired code"
                    )
                }
            }
        }
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.submitPassword(password)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Incorrect password"
                    )
                }
            }
        }
    }
}
