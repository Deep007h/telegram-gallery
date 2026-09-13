package com.teledrive.app.telegram

import android.content.Context
import android.os.Build
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<TdLibAuthState>(TdLibAuthState.Initial)
    val authState: StateFlow<TdLibAuthState> = _authState.asStateFlow()
    val isReady: Boolean
        get() = _authState.value is TdLibAuthState.Ready

    private val _connectionState = MutableStateFlow<TdLibConnectionState>(TdLibConnectionState.WaitingForNetwork)
    val connectionState: StateFlow<TdLibConnectionState> = _connectionState.asStateFlow()

    private val _fileUpdates = MutableSharedFlow<TdFileUpdate>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val fileUpdates: SharedFlow<TdFileUpdate> = _fileUpdates.asSharedFlow()

    private val _newMessages = MutableSharedFlow<TdMessageInfo>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val newMessages: SharedFlow<TdMessageInfo> = _newMessages.asSharedFlow()

    private val _deletedMessages = MutableSharedFlow<Pair<Long, LongArray>>(extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val deletedMessages: SharedFlow<Pair<Long, LongArray>> = _deletedMessages.asSharedFlow()

    private val _recaptchaRequests = MutableSharedFlow<RecaptchaRequest>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val recaptchaRequests: SharedFlow<RecaptchaRequest> = _recaptchaRequests.asSharedFlow()

    private val _myUser = MutableStateFlow<TdApi.User?>(null)
    val myUser: StateFlow<TdApi.User?> = _myUser.asStateFlow()

    private val _myProfilePhotoPath = MutableStateFlow<String?>(null)
    val myProfilePhotoPath: StateFlow<String?> = _myProfilePhotoPath.asStateFlow()

    fun setCachedProfilePhotoPath(path: String) {
        if (_myProfilePhotoPath.value.isNullOrBlank() && path.isNotBlank() && File(path).exists()) {
            _myProfilePhotoPath.value = path
        }
    }

    private var client: Client? = null
    private var appContext: Context? = null
    private var activeApiId: Int = Constants.DEFAULT_API_ID
    private var activeApiHash: String = Constants.DEFAULT_API_HASH

    /** Sets the API credentials to use before or during TDLib initialization */
    fun setApiCredentials(apiId: Int, apiHash: String) {
        if (apiId > 0 && apiHash.isNotBlank()) {
            activeApiId = apiId
            activeApiHash = apiHash
        }
    }

    /** Public read of the keys the live client was started with (for change checks). */
    fun getActiveApiId(): Int = activeApiId
    fun getActiveApiHash(): String = activeApiHash

    val knownChats = ConcurrentHashMap<Long, TdApi.Chat>()

    private inner class UpdateHandler : Client.ResultHandler {
        override fun onResult(obj: TdApi.Object?) {
            when (obj) {
                is TdApi.UpdateAuthorizationState -> {
                    handleAuthState(obj.authorizationState)
                }
                is TdApi.UpdateConnectionState -> {
                    _connectionState.value = mapConnectionState(obj.state)
                }
                is TdApi.UpdateNewChat -> {
                    knownChats[obj.chat.id] = obj.chat
                }
                is TdApi.UpdateChatTitle -> {
                    val c = knownChats[obj.chatId]
                    if (c != null) {
                        c.title = obj.title
                    }
                }
                is TdApi.UpdateUser -> {
                    if (_myUser.value == null || obj.user.id == _myUser.value?.id) {
                        _myUser.value = obj.user
                        appContext?.let { ctx ->
                            scope.launch {
                                fetchAndDownloadTelegramProfilePhoto(ctx)
                            }
                        }
                    }
                }
                is TdApi.UpdateUserFullInfo -> {
                    if (_myUser.value == null || obj.userId == _myUser.value?.id) {
                        appContext?.let { ctx ->
                            scope.launch {
                                fetchAndDownloadTelegramProfilePhoto(ctx)
                            }
                        }
                    }
                }
                is TdApi.UpdateFile -> {
                    handleFileUpdate(obj.file)
                }
                is TdApi.UpdateNewMessage -> {
                    val msgInfo = parseMessageInfo(obj.message)
                    if (msgInfo != null) {
                        com.teledrive.app.core.AppLogger.logTdLib("NewMessage", "Received real-time message: file=${msgInfo.documentFileName}, mime=${msgInfo.documentMimeType}")
                        _newMessages.tryEmit(msgInfo)
                    }
                }
                is TdApi.UpdateApplicationRecaptchaVerificationRequired -> {
                    com.teledrive.app.core.AppLogger.logTdLib("Recaptcha", "Application recaptcha required: id=${obj.verificationId}, action=${obj.action}")
                    _recaptchaRequests.tryEmit(RecaptchaRequest(obj.verificationId, obj.action, obj.recaptchaKeyId))
                    scope.launch {
                        try {
                            val app = appContext as? android.app.Application ?: return@launch
                            val res = RecaptchaHelper.getVerificationToken(app, obj.recaptchaKeyId, obj.action)
                            if (res.isSuccess) {
                                val token = res.getOrThrow()
                                com.teledrive.app.core.AppLogger.logTdLib("Recaptcha", "Auto-solved recaptcha token, submitting to TDLib...")
                                setApplicationVerificationToken(obj.verificationId, token)
                            } else {
                                com.teledrive.app.core.AppLogger.w("Recaptcha", "Auto-solve failed: ${res.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            com.teledrive.app.core.AppLogger.w("Recaptcha", "Auto-solve exception: ${e.message}")
                        }
                    }
                }
                is TdApi.UpdateApplicationVerificationRequired -> {
                    com.teledrive.app.core.AppLogger.logTdLib("Verification", "Application verification required: id=${obj.verificationId}, nonce=${obj.nonce}")
                    scope.launch {
                        try {
                            setApplicationVerificationToken(obj.verificationId, "")
                        } catch (e: Exception) {
                            com.teledrive.app.core.AppLogger.w("Verification", "Fallback verification token failed: ${e.message}")
                        }
                    }
                }
                is TdApi.UpdateDeleteMessages -> {
                    com.teledrive.app.core.AppLogger.logTdLib("DeleteMessages", "Received real-time delete event: chatId=${obj.chatId}, count=${obj.messageIds.size}")
                    _deletedMessages.tryEmit(Pair(obj.chatId, obj.messageIds))
                }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        val newState = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                applyParameters()
                TdLibAuthState.WaitTdlibParameters
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> TdLibAuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                TdLibAuthState.WaitOtherDeviceConfirmation(state.link)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                TdLibAuthState.WaitCode()
            }
            is TdApi.AuthorizationStateWaitPassword -> TdLibAuthState.WaitPassword()
            is TdApi.AuthorizationStateReady -> {
                client?.send(TdApi.LoadChats(null, 20)) { result ->
                    com.teledrive.app.core.AppLogger.logTdLib("LoadChats", "Initial chat list loaded: ${result::class.simpleName}")
                }
                appContext?.let { ctx ->
                    scope.launch {
                        fetchAndDownloadTelegramProfilePhoto(ctx)
                    }
                }
                TdLibAuthState.Ready
            }
            is TdApi.AuthorizationStateLoggingOut -> TdLibAuthState.LoggingOut
            is TdApi.AuthorizationStateClosing -> TdLibAuthState.Closing
            is TdApi.AuthorizationStateClosed -> TdLibAuthState.Closed
            else -> TdLibAuthState.Initial
        }
        com.teledrive.app.core.AppLogger.logTdLib("AuthState", "State changed to: ${newState::class.simpleName}")
        _authState.value = newState
    }

    private fun applyParameters() {
        val context = appContext ?: return
        val filesDir = context.filesDir
        val tdlibDir = File(filesDir, "tdlib").apply { mkdirs() }
        val databaseDir = File(tdlibDir, "database").apply { mkdirs() }
        val filesDirTd = File(tdlibDir, "files").apply { mkdirs() }

        val parameters = TdApi.SetTdlibParameters().apply {
            databaseDirectory = databaseDir.absolutePath
            filesDirectory = filesDirTd.absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = activeApiId
            apiHash = activeApiHash
            systemLanguageCode = java.util.Locale.getDefault().toLanguageTag()
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(60)
            systemVersion = "Android ${android.os.Build.VERSION.RELEASE}".take(60)
            applicationVersion = "1.1.4"
        }

        com.teledrive.app.core.AppLogger.logTdLib("Init", "Setting TDLib parameters for device: ${parameters.deviceModel}")
        client?.send(parameters) { result ->
            if (result is TdApi.Error) {
                com.teledrive.app.core.AppLogger.e("TDLib", "SetTdlibParameters error: ${result.message}")
                if (!result.message.contains("unexpected", ignoreCase = true)) {
                    _authState.value = TdLibAuthState.Error(result.message)
                }
            }
        }
    }

    private fun mapConnectionState(state: TdApi.ConnectionState): TdLibConnectionState {
        return when (state) {
            is TdApi.ConnectionStateConnectingToProxy -> TdLibConnectionState.ConnectingToProxy
            is TdApi.ConnectionStateConnecting -> TdLibConnectionState.Connecting
            is TdApi.ConnectionStateUpdating -> TdLibConnectionState.Updating
            is TdApi.ConnectionStateReady -> TdLibConnectionState.Ready
            else -> TdLibConnectionState.WaitingForNetwork
        }
    }

    private fun handleFileUpdate(file: TdApi.File) {
        val update = TdFileUpdate(
            fileId = file.id,
            size = file.size,
            expectedSize = file.expectedSize,
            downloadedSize = file.local.downloadedSize,
            uploadedSize = file.remote.uploadedSize,
            isDownloadingCompleted = file.local.isDownloadingCompleted,
            isUploadingCompleted = file.remote.isUploadingCompleted,
            localPath = file.local.path
        )
        if (file.local.isDownloadingCompleted) {
            com.teledrive.app.core.AppLogger.logTransfer("FileCompleted", file.id, "", "Downloaded ${file.local.downloadedSize} bytes to ${file.local.path}")
        }
        _fileUpdates.tryEmit(update)
    }

    suspend fun sendRequest(function: TdApi.Function<*>): TdApi.Object {
        return withTimeout(45_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    client?.send(function) { result ->
                        if (result is TdApi.Error) {
                            com.teledrive.app.core.AppLogger.e("TDLib", "Error on ${function::class.simpleName}: code=${result.code}, msg=${result.message}")
                            if (cont.isActive) cont.resumeWithException(TdLibException(result.code, result.message))
                        } else {
                            if (cont.isActive) cont.resume(result)
                        }
                    } ?: run {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("TDLib client is not initialized"))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (client != null) return
        appContext = context.applicationContext

        try {
            client = Client.create(UpdateHandler(), null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun restartClient(apiId: Int, apiHash: String) {
        if (apiId > 0 && apiHash.isNotBlank()) {
            activeApiId = apiId
            activeApiHash = apiHash
        }

        try {
            client?.send(TdApi.Close(), null)
            withTimeout(3000) {
                authState.filter { it is TdLibAuthState.Closed }.first()
            }
        } catch (ignored: Exception) {}
        client = null

        val context = appContext ?: return
        val tdlibDir = File(context.filesDir, "tdlib")
        if (tdlibDir.exists()) {
            tdlibDir.deleteRecursively()
        }

        client = Client.create(UpdateHandler(), null, null)

        try {
            withTimeout(8000) {
                authState.filter { it is TdLibAuthState.WaitPhoneNumber || it is TdLibAuthState.Ready }.first()
            }
        } catch (ignored: Exception) {}
    }

    suspend fun setPhoneNumber(phone: String) {
        if (client == null) {
            appContext?.let { initialize(it) }
        }

        if (_authState.value !is TdLibAuthState.WaitPhoneNumber && _authState.value !is TdLibAuthState.Ready && _authState.value !is TdLibAuthState.WaitCode) {
            try {
                withTimeout(15000) {
                    authState.filter {
                        it is TdLibAuthState.WaitPhoneNumber ||
                        it is TdLibAuthState.Ready ||
                        it is TdLibAuthState.WaitCode
                    }.first()
                }
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("TDLib", "Wait for WaitPhoneNumber timed out, current state: ${_authState.value}")
            }
        }

        if (_authState.value is TdLibAuthState.WaitCode) {
            com.teledrive.app.core.AppLogger.i("TDLib", "Already in WaitCode state, skipping SetAuthenticationPhoneNumber")
            return
        }

        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
            allowSmsRetrieverApi = true
        }
        sendRequest(TdApi.SetAuthenticationPhoneNumber(phone, settings))
    }

    suspend fun checkAuthenticationBotToken(botToken: String) {
        if (client == null) {
            appContext?.let { initialize(it) }
        }
        if (_authState.value !is TdLibAuthState.WaitPhoneNumber && _authState.value !is TdLibAuthState.Ready) {
            try {
                withTimeout(10000) {
                    authState.filter { it is TdLibAuthState.WaitPhoneNumber || it is TdLibAuthState.Ready }.first()
                }
            } catch (ignored: Exception) {}
        }
        sendRequest(TdApi.CheckAuthenticationBotToken(botToken))
    }

    suspend fun requestQrCodeAuthentication() {
        if (client == null) {
            appContext?.let { initialize(it) }
        }
        if (_authState.value !is TdLibAuthState.WaitPhoneNumber &&
            _authState.value !is TdLibAuthState.WaitOtherDeviceConfirmation &&
            _authState.value !is TdLibAuthState.Ready) {
            try {
                withTimeout(10000) {
                    authState.filter {
                        it is TdLibAuthState.WaitPhoneNumber ||
                        it is TdLibAuthState.WaitOtherDeviceConfirmation ||
                        it is TdLibAuthState.Ready
                    }.first()
                }
            } catch (ignored: Exception) {}
        }
        sendRequest(TdApi.RequestQrCodeAuthentication(longArrayOf()))
    }


    suspend fun setApplicationVerificationToken(verificationId: Long, token: String) {
        sendRequest(TdApi.SetApplicationVerificationToken(verificationId, token))
    }

    suspend fun submitAuthCode(code: String) {
        sendRequest(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun submit2FAPassword(password: String) {
        sendRequest(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun logout() {
        sendRequest(TdApi.LogOut())
    }

    suspend fun createPrivateChannel(title: String, description: String): TdChatInfo {
        val chat = sendRequest(TdApi.CreateNewSupergroupChat(title, false, true, description, null, 0, false)) as TdApi.Chat
        return TdChatInfo(
            chatId = chat.id,
            title = chat.title,
            type = "channel"
        )
    }

    suspend fun restartAuthentication() {
        try {
            client?.send(TdApi.Close(), null)
            withTimeout(3000) {
                authState.filter { it is TdLibAuthState.Closed }.first()
            }
        } catch (ignored: Exception) {}
        client = null
        val context = appContext ?: return
        val tdlibDir = File(context.filesDir, "tdlib")
        if (tdlibDir.exists()) {
            tdlibDir.deleteRecursively()
        }
        client = Client.create(UpdateHandler(), null, null)
    }

    suspend fun ensureStorageChat(knownChatId: Long? = null): Long {
        if (knownChatId != null && knownChatId != 0L) {
            try {
                val chat = sendRequest(TdApi.GetChat(knownChatId)) as TdApi.Chat
                return chat.id
            } catch (_: Exception) {}
        }
        try {
            val searchRes = sendRequest(TdApi.SearchChatsOnServer("TeleDrive", null, 10)) as? TdApi.Chats
            if (searchRes != null && searchRes.chatIds.isNotEmpty()) {
                for (id in searchRes.chatIds) {
                    try {
                        val c = sendRequest(TdApi.GetChat(id)) as TdApi.Chat
                        if (c.title.contains("TeleDrive", ignoreCase = true)) {
                            return c.id
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        try {
            val smId = getSavedMessagesChatId()
            if (smId != 0L) return smId
        } catch (_: Exception) {}

        try {
            val created = createPrivateChannel("TeleDrive Storage", "TeleDrive Cloud Storage Channel")
            if (created.chatId != 0L) return created.chatId
        } catch (_: Exception) {}

        return Constants.DEFAULT_USER_CHAT_ID
    }

    suspend fun getChat(chatId: Long): TdChatInfo {
        val chat = sendRequest(TdApi.GetChat(chatId)) as TdApi.Chat
        val type = when (chat.type) {
            is TdApi.ChatTypePrivate -> "private"
            is TdApi.ChatTypeSupergroup -> if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "channel" else "supergroup"
            else -> "group"
        }
        return TdChatInfo(
            chatId = chat.id,
            title = chat.title,
            type = type
        )
    }

    data class TelegramChatSummary(
        val id: Long,
        val title: String,
        val typeDescription: String,
        val isSavedMessages: Boolean = false,
        val username: String? = null
    )

    suspend fun fetchBotChatInfo(botToken: String, chatIdOrUsername: String): TelegramChatSummary? = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatIdOrUsername.isBlank()) return@withContext null
        try {
            val cleanParam = if (chatIdOrUsername.startsWith("@") || chatIdOrUsername.toLongOrNull() != null) {
                chatIdOrUsername
            } else if (!chatIdOrUsername.contains("/")) {
                "@$chatIdOrUsername"
            } else {
                chatIdOrUsername
            }
            val encodedParam = java.net.URLEncoder.encode(cleanParam, "UTF-8")
            val url = java.net.URL("https://api.telegram.org/bot$botToken/getChat?chat_id=$encodedParam")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) {
                    val res = json.optJSONObject("result") ?: return@withContext null
                    val id = res.optLong("id")
                    val type = res.optString("type", "chat")
                    val rawTitle = res.optString("title", "")
                    val firstName = res.optString("first_name", "")
                    val lastName = res.optString("last_name", "")
                    val username = res.optString("username", "")
                    val title = when {
                        rawTitle.isNotBlank() -> rawTitle
                        firstName.isNotBlank() -> listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                        username.isNotBlank() -> "@$username"
                        else -> "Chat $id"
                    }
                    val typeDesc = when (type.lowercase()) {
                        "channel" -> "Channel"
                        "supergroup" -> "Supergroup"
                        "group" -> "Group"
                        "private" -> "Personal Chat"
                        else -> "Telegram Chat"
                    }
                    return@withContext TelegramChatSummary(
                        id = id,
                        title = title,
                        typeDescription = typeDesc,
                        isSavedMessages = false,
                        username = username.ifBlank { null }
                    )
                }
            }
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("TDLib", "fetchBotChatInfo failed for $chatIdOrUsername: ${e.message}")
        }
        null
    }

    suspend fun fetchBotUpdatesChats(botToken: String): List<TelegramChatSummary> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext emptyList()
        val results = mutableListOf<TelegramChatSummary>()
        try {
            val url = java.net.URL("https://api.telegram.org/bot$botToken/getUpdates")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) {
                    val array = json.optJSONArray("result") ?: JSONArray()
                    val seenIds = HashSet<Long>()
                    for (i in 0 until array.length()) {
                        val update = array.optJSONObject(i) ?: continue
                        val chatObj = update.optJSONObject("message")?.optJSONObject("chat")
                            ?: update.optJSONObject("channel_post")?.optJSONObject("chat")
                            ?: update.optJSONObject("my_chat_member")?.optJSONObject("chat")
                            ?: update.optJSONObject("edited_message")?.optJSONObject("chat")
                            ?: continue
                        val id = chatObj.optLong("id")
                        if (id == 0L || !seenIds.add(id)) continue

                        val type = chatObj.optString("type", "chat")
                        val rawTitle = chatObj.optString("title", "")
                        val firstName = chatObj.optString("first_name", "")
                        val lastName = chatObj.optString("last_name", "")
                        val username = chatObj.optString("username", "")
                        val title = when {
                            rawTitle.isNotBlank() -> rawTitle
                            firstName.isNotBlank() -> listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                            username.isNotBlank() -> "@$username"
                            else -> "Chat $id"
                        }
                        val typeDesc = when (type.lowercase()) {
                            "channel" -> "Channel"
                            "supergroup" -> "Supergroup"
                            "group" -> "Group"
                            "private" -> "Personal Chat"
                            else -> "Telegram Chat"
                        }
                        results.add(
                            TelegramChatSummary(
                                id = id,
                                title = title,
                                typeDescription = typeDesc,
                                isSavedMessages = false,
                                username = username.ifBlank { null }
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("TDLib", "fetchBotUpdatesChats failed: ${e.message}")
        }
        results
    }

    private suspend fun loadAllChatsForList(chatList: TdApi.ChatList, maxBatches: Int = 15) {
        for (i in 0 until maxBatches) {
            try {
                sendRequest(TdApi.LoadChats(chatList, 100))
            } catch (e: Exception) {
                // TDLib returns error 404 when all chats in the list have been loaded
                break
            }
        }
    }

    fun mapChatToSummary(chat: TdApi.Chat, savedMessagesId: Long = 0L): TelegramChatSummary {
        val isSaved = (chat.id == savedMessagesId) || (savedMessagesId == 0L && chat.title.contains("Saved Messages", ignoreCase = true))
        val typeDesc = when {
            isSaved -> "Personal Cloud (Saved Messages)"
            chat.type is TdApi.ChatTypeSupergroup -> if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "Channel" else "Supergroup"
            chat.type is TdApi.ChatTypeBasicGroup -> "Group"
            chat.type is TdApi.ChatTypePrivate -> "Private Chat"
            chat.type is TdApi.ChatTypeSecret -> "Secret Chat"
            else -> "Chat"
        }
        return TelegramChatSummary(
            id = chat.id,
            title = if (isSaved) "Saved Messages" else chat.title.ifBlank { "Chat ${chat.id}" },
            typeDescription = typeDesc,
            isSavedMessages = isSaved
        )
    }

    suspend fun getAvailableStorageChats(): List<TelegramChatSummary> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TelegramChatSummary>()
        val seenIds = HashSet<Long>()

        val prefs = com.teledrive.app.TeleDriveApplication.instance.preferences
        val botToken = prefs.getCachedBotToken().ifBlank { prefs.botToken.first() }
        val loginType = prefs.getCachedLoginType().ifBlank { prefs.loginType.first() }
        val isUserLoggedIn = _authState.value is TdLibAuthState.Ready
        val isBotMode = (loginType == "bot" && !isUserLoggedIn) || (!isUserLoggedIn && botToken.isNotBlank() && loginType.isBlank()) || (loginType == "web" && !isUserLoggedIn)

        // 1. Add current storage chat from preferences if valid
        val currentStorageChatId = prefs.getCachedStorageChatId()
        val currentStorageChatTitle = prefs.getCachedStorageChatTitle()
        if (currentStorageChatId != 0L && seenIds.add(currentStorageChatId)) {
            val isSaved = currentStorageChatTitle.contains("Saved Messages", ignoreCase = true)
            list.add(
                TelegramChatSummary(
                    id = currentStorageChatId,
                    title = currentStorageChatTitle.ifBlank { "Active Storage ($currentStorageChatId)" },
                    typeDescription = if (isSaved) "Personal Cloud (Saved Messages)" else "Current Storage Destination",
                    isSavedMessages = isSaved
                )
            )
        }

        // Always ensure default personal storage chat is available
        val defaultChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
        if (seenIds.add(defaultChatId)) {
            val userDisplay = prefs.getCachedUserDisplayName().ifBlank { "Deep 007h" }
            list.add(
                TelegramChatSummary(
                    id = defaultChatId,
                    title = "Personal Storage ($userDisplay)",
                    typeDescription = "Personal Cloud Storage",
                    isSavedMessages = true
                )
            )
        }

        // 2. Load any user-saved linked chats from JSON prefs
        try {
            for (chat in prefs.getLinkedChats()) {
                if (seenIds.add(chat.id)) {
                    list.add(chat)
                }
            }
        } catch (_: Exception) {}

        if (isBotMode) {
            // --- BOT MODE ---
            // 3a. If botChatId preference is set, resolve and add it
            val botChatIdStr = prefs.getCachedBotChatId().ifBlank { prefs.botChatId.first() }
            val botChatId = botChatIdStr.toLongOrNull() ?: 0L
            if (botChatId != 0L && seenIds.add(botChatId)) {
                val info = fetchBotChatInfo(botToken, botChatIdStr)
                list.add(
                    info ?: TelegramChatSummary(
                        id = botChatId,
                        title = "Personal Chat ($botChatId)",
                        typeDescription = "Personal Chat"
                    )
                )
            }

            // 3b. Fetch updates to discover user and channel chats the bot was added to
            val updateChats = fetchBotUpdatesChats(botToken)
            for (chat in updateChats) {
                if (seenIds.add(chat.id)) {
                    list.add(chat)
                }
            }

            // 3c. Include any chats from TDLib's knownChats
            for ((id, chat) in knownChats) {
                if (seenIds.add(id)) {
                    list.add(mapChatToSummary(chat))
                }
            }
        } else {
            // --- USER / MTPROTO MODE ---
            // 3a. Always include Saved Messages at the top
            var savedId = 0L
            try {
                savedId = getSavedMessagesChatId()
                if (savedId != 0L && seenIds.add(savedId)) {
                    list.add(
                        0,
                        TelegramChatSummary(
                            id = savedId,
                            title = "Saved Messages",
                            typeDescription = "Personal Cloud (Saved Messages)",
                            isSavedMessages = true
                        )
                    )
                }
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("TDLib", "Failed to get saved messages: ${e.message}")
            }

            // 3b. Pre-load ALL chats from TDLib in batches until all loaded (error 404)
            loadAllChatsForList(TdApi.ChatListMain(), maxBatches = 15)
            loadAllChatsForList(TdApi.ChatListArchive(), maxBatches = 10)

            // 3c. Query chat IDs from TDLib
            val mainChatIds = try {
                (sendRequest(TdApi.GetChats(TdApi.ChatListMain(), 2000)) as? TdApi.Chats)?.chatIds ?: LongArray(0)
            } catch (_: Exception) { LongArray(0) }

            val archiveChatIds = try {
                (sendRequest(TdApi.GetChats(TdApi.ChatListArchive(), 2000)) as? TdApi.Chats)?.chatIds ?: LongArray(0)
            } catch (_: Exception) { LongArray(0) }

            val createdPublicChatIds = try {
                (sendRequest(TdApi.GetCreatedPublicChats(TdApi.PublicChatTypeHasUsername())) as? TdApi.Chats)?.chatIds ?: LongArray(0)
            } catch (_: Exception) { LongArray(0) }

            // 3d. Gather all unique chat IDs
            val allChatIds = LinkedHashSet<Long>()
            for (id in mainChatIds) allChatIds.add(id)
            for (id in archiveChatIds) allChatIds.add(id)
            for (id in createdPublicChatIds) allChatIds.add(id)
            for (id in knownChats.keys) allChatIds.add(id)

            // 3e. Fetch any missing Chat objects concurrently in chunks
            val missingChatIds = allChatIds.filter { !knownChats.containsKey(it) }
            if (missingChatIds.isNotEmpty()) {
                missingChatIds.chunked(25).forEach { chunk ->
                    coroutineScope {
                        chunk.map { id ->
                            async(Dispatchers.IO) {
                                try {
                                    val chat = sendRequest(TdApi.GetChat(id)) as? TdApi.Chat
                                    if (chat != null) {
                                        knownChats[chat.id] = chat
                                    }
                                } catch (_: Exception) {}
                            }
                        }.awaitAll()
                    }
                }
            }

            // 3f. Add all chats to the result list in order
            for (chatId in allChatIds) {
                if (!seenIds.add(chatId)) continue
                val chat = knownChats[chatId] ?: continue
                list.add(mapChatToSummary(chat, savedMessagesId = savedId))
            }

            // 3g. Also include any remaining knownChats not in the lists
            for ((id, chat) in knownChats) {
                if (seenIds.add(id)) {
                    list.add(mapChatToSummary(chat, savedMessagesId = savedId))
                }
            }
        }

        list
    }

    suspend fun searchTelegramChats(query: String): List<TelegramChatSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val results = mutableListOf<TelegramChatSummary>()
        val seen = HashSet<Long>()

        // 1. Search local & known chats
        try {
            val chats = sendRequest(TdApi.SearchChats(trimmed, null, 30)) as? TdApi.Chats
            if (chats != null) {
                for (chatId in chats.chatIds) {
                    if (seen.add(chatId)) {
                        val chat = knownChats[chatId] ?: (sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat)
                        if (chat != null) {
                            knownChats[chat.id] = chat
                            results.add(mapChatToSummary(chat))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Search on server
        try {
            val serverChats = sendRequest(TdApi.SearchChatsOnServer(trimmed, null, 30)) as? TdApi.Chats
            if (serverChats != null) {
                for (chatId in serverChats.chatIds) {
                    if (seen.add(chatId)) {
                        val chat = knownChats[chatId] ?: (sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat)
                        if (chat != null) {
                            knownChats[chat.id] = chat
                            results.add(mapChatToSummary(chat))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Search public chats
        try {
            val publicChats = sendRequest(TdApi.SearchPublicChats(trimmed, null)) as? TdApi.Chats
            if (publicChats != null) {
                for (chatId in publicChats.chatIds) {
                    if (seen.add(chatId)) {
                        val chat = knownChats[chatId] ?: (sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat)
                        if (chat != null) {
                            knownChats[chat.id] = chat
                            results.add(mapChatToSummary(chat))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun resolveTelegramChat(rawInput: String): TelegramChatSummary? = withContext(Dispatchers.IO) {
        var input = rawInput.trim().trim('"', '\'')
        if (input.isBlank()) return@withContext null

        val prefs = com.teledrive.app.TeleDriveApplication.instance.preferences
        val botToken = prefs.getCachedBotToken().ifBlank { prefs.botToken.first() }
        val loginType = prefs.getCachedLoginType().ifBlank { prefs.loginType.first() }
        val isUserLoggedIn = _authState.value is TdLibAuthState.Ready
        val isBotMode = (loginType == "bot" && !isUserLoggedIn) || (!isUserLoggedIn && botToken.isNotBlank() && loginType.isBlank()) || (loginType == "web" && !isUserLoggedIn)

        // 1. Handle Telegram Web URLs: e.g. https://web.telegram.org/a/#-1001234567890 or #@channel or ?tgaddr=...
        if (input.contains("web.telegram.org")) {
            if (input.contains("tgaddr=")) {
                try {
                    val rawTgAddr = input.substringAfter("tgaddr=").substringBefore("&")
                    input = java.net.URLDecoder.decode(rawTgAddr, "UTF-8")
                } catch (_: Exception) {}
            } else {
                val hashPart = input.substringAfter("#", "").trimStart('/', '#', '?')
                if (hashPart.isNotBlank()) {
                    input = hashPart
                }
            }
        }

        // 2. Handle internal / private channel links: e.g. https://t.me/c/2456789012/123 or t.me/c/2456789012 or c/2456789012
        val cMatch = Regex("""(?:t\.me/c/|/c/|^c/)(\d+)""").find(input)
        if (cMatch != null) {
            val channelDigits = cMatch.groupValues[1]
            val supergroupId = "-100$channelDigits".toLongOrNull()
            if (supergroupId != null) {
                input = supergroupId.toString()
            }
        }

        // Strip standard URL and protocol prefixes
        input = input.removePrefix("https://").removePrefix("http://")
        input = input.removePrefix("t.me/").removePrefix("telegram.me/")
        input = input.removePrefix("tg://resolve?domain=")
        input = input.removePrefix("tg://join?invite=")

        val isInviteLink = input.startsWith("+") || input.startsWith("joinchat/") || rawInput.contains("joinchat/") || rawInput.contains("t.me/+")

        // Case 1: Numeric Chat ID (e.g. -1001234567890 or 8353217839)
        val numericId = input.toLongOrNull()
        if (numericId != null) {
            if (isBotMode && botToken.isNotBlank()) {
                val botChat = fetchBotChatInfo(botToken, numericId.toString())
                if (botChat != null) return@withContext botChat
                // If it's a positive number without -100 prefix, try with -100 prefix for supergroups
                if (numericId > 0) {
                    val superChat = fetchBotChatInfo(botToken, "-100$numericId")
                    if (superChat != null) return@withContext superChat
                }
            }
            if (isUserLoggedIn) {
                try {
                    val chat = sendRequest(TdApi.GetChat(numericId)) as TdApi.Chat
                    knownChats[chat.id] = chat
                    val typeDesc = when (val t = chat.type) {
                        is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Supergroup"
                        is TdApi.ChatTypeBasicGroup -> "Group"
                        is TdApi.ChatTypePrivate -> "Private Chat"
                        else -> "Chat"
                    }
                    return@withContext TelegramChatSummary(
                        id = chat.id,
                        title = chat.title.ifBlank { "Chat ${chat.id}" },
                        typeDescription = typeDesc
                    )
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("TDLib", "GetChat failed for $numericId: ${e.message}")
                }
            } else {
                return@withContext TelegramChatSummary(
                    id = numericId,
                    title = if (numericId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID) "Personal Storage (Deep 007h)" else "Storage Chat ($numericId)",
                    typeDescription = "Telegram Storage Destination",
                    isSavedMessages = (numericId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID)
                )
            }
        }

        // Case 2: Username / Public Handle (e.g. @channelname or channelname)
        val cleanUsername = input.removePrefix("@").substringBefore("/").substringBefore("?").trim()
        if (cleanUsername.isNotBlank() && !isInviteLink && numericId == null) {
            if (isBotMode && botToken.isNotBlank()) {
                val botChat = fetchBotChatInfo(botToken, "@$cleanUsername")
                if (botChat != null) return@withContext botChat
            }
            if (isUserLoggedIn) {
                try {
                    val chat = sendRequest(TdApi.SearchPublicChat(cleanUsername)) as TdApi.Chat
                    knownChats[chat.id] = chat
                    val typeDesc = when (val t = chat.type) {
                        is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Supergroup"
                        is TdApi.ChatTypeBasicGroup -> "Group"
                        is TdApi.ChatTypePrivate -> "Private Chat"
                        else -> "Chat"
                    }
                    return@withContext TelegramChatSummary(
                        id = chat.id,
                        title = chat.title.ifBlank { "@$cleanUsername" },
                        typeDescription = typeDesc,
                        username = cleanUsername
                    )
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("TDLib", "SearchPublicChat failed for $cleanUsername: ${e.message}")
                }
            }
        }

        // Case 3: Invite Link
        if (isInviteLink) {
            val fullInvite = if (rawInput.startsWith("http") || rawInput.startsWith("tg://")) rawInput.trim() else "https://t.me/$input"
            try {
                val inviteInfo = sendRequest(TdApi.CheckChatInviteLink(fullInvite)) as TdApi.ChatInviteLinkInfo
                var targetChatId = inviteInfo.chatId
                if (targetChatId == 0L) {
                    try {
                        val joinRes = sendRequest(TdApi.JoinChatByInviteLink(fullInvite))
                        if (joinRes is TdApi.ChatJoinResultSuccess) {
                            targetChatId = joinRes.chatId
                        }
                    } catch (e: Exception) {
                        com.teledrive.app.core.AppLogger.w("TDLib", "JoinChatByInviteLink: ${e.message}")
                    }
                }
                val typeDesc = when (inviteInfo.type) {
                    is TdApi.InviteLinkChatTypeSupergroup -> "Supergroup"
                    is TdApi.InviteLinkChatTypeChannel -> "Channel"
                    is TdApi.InviteLinkChatTypeBasicGroup -> "Group"
                    else -> "Chat"
                }
                return@withContext TelegramChatSummary(
                    id = if (targetChatId != 0L) targetChatId else inviteInfo.chatId,
                    title = inviteInfo.title.ifBlank { "Telegram Linked Chat" },
                    typeDescription = typeDesc
                )
            } catch (e: Exception) {
                com.teledrive.app.core.AppLogger.w("TDLib", "CheckChatInviteLink failed for $fullInvite: ${e.message}")
            }
        }

        null
    }


    suspend fun getMessage(chatId: Long, messageId: Long): TdMessageInfo? {
        return try {
            val msg = sendRequest(TdApi.GetMessage(chatId, messageId)) as TdApi.Message
            parseMessageInfo(msg)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): List<TdMessageInfo> {
        val messages = sendRequest(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)) as TdApi.Messages
        return messages.messages.mapNotNull { message ->
            parseMessageInfo(message)
        }
    }

    data class ChatHistoryBatch(
        val parsedItems: List<TdMessageInfo>,
        val lastRawMessageId: Long,
        val totalRawCount: Int
    )

    suspend fun getChatHistoryFull(chatId: Long, fromMessageId: Long, limit: Int = 100): ChatHistoryBatch {
        val messages = sendRequest(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)) as TdApi.Messages
        val rawList = messages.messages.toList()
        val parsed = rawList.mapNotNull { parseMessageInfo(it) }
        val lastId = rawList.lastOrNull()?.id ?: 0L
        return ChatHistoryBatch(
            parsedItems = parsed,
            lastRawMessageId = lastId,
            totalRawCount = rawList.size
        )
    }

    suspend fun sendFile(chatId: Long, filePath: String, caption: String): TdMessageInfo {
        val inputFile = TdApi.InputFileLocal(filePath)
        val inputDoc = TdApi.InputDocument(inputFile, null, false)
        val inputMessageContent = TdApi.InputMessageDocument(
            inputDoc,
            TdApi.FormattedText(caption, emptyArray())
        )
        val message = sendRequest(TdApi.SendMessage(chatId, null, null, null, null, inputMessageContent)) as TdApi.Message
        return parseMessageInfo(message) ?: throw Exception("Failed to parse sent message info")
    }

    suspend fun getFile(fileId: Int): TdApi.File {
        return sendRequest(TdApi.GetFile(fileId)) as TdApi.File
    }

    suspend fun startDownload(fileId: Int, priority: Int = 1): TdApi.File {
        return sendRequest(TdApi.DownloadFile(fileId, priority, 0, 0, false)) as TdApi.File
    }

    suspend fun downloadFile(
        fileId: Int,
        priority: Int = 1,
        timeoutMs: Long = 45_000L,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        try {
            val file = getFile(fileId)
            if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
                onProgress?.invoke(file.size, file.size)
                return file.local.path
            }
        } catch (ignored: Exception) {}

        // Subscribe to completion events BEFORE initiating the download
        // to avoid missing fast completions (race condition fix).
        return try {
            withTimeout(timeoutMs) {
                var progressJob: kotlinx.coroutines.Job? = null
                if (onProgress != null) {
                    progressJob = launch {
                        fileUpdates.collect { update ->
                            if (update.fileId == fileId) {
                                val total = if (update.expectedSize > 0) update.expectedSize else update.size
                                onProgress(update.downloadedSize, total)
                            }
                        }
                    }
                }

                val completionDeferred = async {
                    fileUpdates.first { it.fileId == fileId && it.isDownloadingCompleted && it.localPath.isNotEmpty() }.localPath
                }

                // Now send the download request
                try {
                    val res = sendRequest(TdApi.DownloadFile(fileId, priority, 0, 0, false))
                    if (res is TdApi.File && res.local.isDownloadingCompleted && res.local.path.isNotEmpty() && File(res.local.path).exists()) {
                        progressJob?.cancel()
                        completionDeferred.cancel()
                        onProgress?.invoke(res.size, res.size)
                        return@withTimeout res.local.path
                    }
                } catch (e: Exception) {
                    progressJob?.cancel()
                    completionDeferred.cancel()
                    return@withTimeout ""
                }

                // Wait for the flow to deliver the completion event
                val path = completionDeferred.await()
                progressJob?.cancel()
                onProgress?.let {
                    try {
                        val finalFile = getFile(fileId)
                        it(finalFile.size, finalFile.size)
                    } catch (_: Exception) {}
                }
                path
            }
        } catch (e: Exception) {
            try {
                val file = getFile(fileId)
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
                    file.local.path
                } else ""
            } catch (ex: Exception) {
                ""
            }
        }
    }

    suspend fun cancelDownload(fileId: Int) {
        sendRequest(TdApi.CancelDownloadFile(fileId, false))
    }

    suspend fun deleteMessages(chatId: Long, messageIds: LongArray) {
        sendRequest(TdApi.DeleteMessages(chatId, messageIds, true))
    }

    suspend fun editMessageCaption(chatId: Long, messageId: Long, newCaption: String) {
        sendRequest(
            TdApi.EditMessageCaption(
                chatId,
                messageId,
                null,
                TdApi.FormattedText(newCaption, emptyArray()),
                false
            )
        )
    }

    suspend fun getMe(): Pair<String, String?> {
        val user = sendRequest(TdApi.GetMe()) as TdApi.User
        return Pair(user.phoneNumber, user.usernames?.activeUsernames?.firstOrNull())
    }

    suspend fun getMeUser(): TdApi.User {
        return sendRequest(TdApi.GetMe()) as TdApi.User
    }

    suspend fun getMyProfilePhotoFileId(): Int? {
        return try {
            val me = getMeUser()
            val photo = me.profilePhoto
            if (photo != null) {
                val file = photo.big ?: photo.small
                if (file != null && file.id != 0) return file.id
            }
            // Fallback: query GetUserProfilePhotos
            val chatPhotos = sendRequest(TdApi.GetUserProfilePhotos(me.id, 0, 1)) as? TdApi.ChatPhotos
            val firstPhoto = chatPhotos?.photos?.firstOrNull()
            if (firstPhoto != null) {
                val bestSize = firstPhoto.sizes?.maxByOrNull { it.width } ?: firstPhoto.sizes?.firstOrNull()
                if (bestSize != null && bestSize.photo.id != 0) return bestSize.photo.id
            }
            // Fallback 2: GetUserFullInfo
            val fullInfo = sendRequest(TdApi.GetUserFullInfo(me.id)) as? TdApi.UserFullInfo
            val fullPhoto = fullInfo?.photo ?: fullInfo?.personalPhoto ?: fullInfo?.publicPhoto
            val fullSize = fullPhoto?.sizes?.maxByOrNull { it.width } ?: fullPhoto?.sizes?.firstOrNull()
            fullSize?.photo?.id?.takeIf { it != 0 }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchAndDownloadTelegramProfilePhoto(context: Context): File? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val me = getMeUser()
            _myUser.value = me

            val candidateFileIds = mutableListOf<Int>()

            // 1. Check direct profilePhoto on User (big high-res first, then small)
            me.profilePhoto?.let { photo ->
                photo.big?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
                photo.small?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
            }

            // 2. Query GetUserProfilePhotos if candidateFileIds is empty
            if (candidateFileIds.isEmpty()) {
                try {
                    val chatPhotos = sendRequest(TdApi.GetUserProfilePhotos(me.id, 0, 5)) as? TdApi.ChatPhotos
                    chatPhotos?.photos?.forEach { photo ->
                        photo.sizes?.maxByOrNull { it.width }?.photo?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
                        photo.sizes?.firstOrNull()?.photo?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
                    }
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("TDLib", "GetUserProfilePhotos failed: ${e.message}")
                }
            }

            // 3. Query GetUserFullInfo if still empty
            if (candidateFileIds.isEmpty()) {
                try {
                    val fullInfo = sendRequest(TdApi.GetUserFullInfo(me.id)) as? TdApi.UserFullInfo
                    val chatPhoto = fullInfo?.photo ?: fullInfo?.personalPhoto ?: fullInfo?.publicPhoto
                    chatPhoto?.sizes?.maxByOrNull { it.width }?.photo?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
                    chatPhoto?.sizes?.firstOrNull()?.photo?.id?.takeIf { it != 0 }?.let { candidateFileIds.add(it) }
                } catch (e: Exception) {
                    com.teledrive.app.core.AppLogger.w("TDLib", "GetUserFullInfo failed: ${e.message}")
                }
            }

            // Minithumbnail embedded in memory as immediate fallback
            val minithumb = me.profilePhoto?.minithumbnail
            val fallbackMiniFile = if (minithumb?.data != null && minithumb.data.isNotEmpty()) {
                try {
                    val miniFile = File(context.filesDir, "telegram_avatar_mini_${me.id}.jpg")
                    miniFile.writeBytes(minithumb.data)
                    miniFile
                } catch (_: Exception) { null }
            } else null

            if (candidateFileIds.isEmpty()) {
                com.teledrive.app.core.AppLogger.i("TDLib", "User has no Telegram profile photo")
                if (fallbackMiniFile != null && fallbackMiniFile.exists()) {
                    _myProfilePhotoPath.value = fallbackMiniFile.absolutePath
                    try {
                        com.teledrive.app.TeleDriveApplication.instance.preferences.setTelegramProfilePhotoPath(fallbackMiniFile.absolutePath)
                    } catch (_: Exception) {}
                    return@withContext fallbackMiniFile
                }
                _myProfilePhotoPath.value = null
                return@withContext null
            }

            val distinctIds = candidateFileIds.distinct()
            com.teledrive.app.core.AppLogger.i("TDLib", "Attempting download of Telegram profile photo from candidate IDs: $distinctIds")

            for (fileId in distinctIds) {
                val downloaded = downloadProfilePhoto(fileId, context)
                if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
                    val avatarFile = File(context.filesDir, "telegram_avatar_${me.id}.jpg")
                    try {
                        downloaded.copyTo(avatarFile, overwrite = true)
                    } catch (_: Exception) {}

                    val finalFile = if (avatarFile.exists() && avatarFile.length() > 0) avatarFile else downloaded
                    val path = finalFile.absolutePath
                    _myProfilePhotoPath.value = path
                    // Persist to DataStore so it survives app restarts
                    try {
                        com.teledrive.app.TeleDriveApplication.instance.preferences.setTelegramProfilePhotoPath(path)
                    } catch (_: Exception) {}
                    com.teledrive.app.core.AppLogger.i("TDLib", "Telegram profile photo ready at $path (${finalFile.length()} bytes)")
                    return@withContext finalFile
                }
            }

            if (fallbackMiniFile != null && fallbackMiniFile.exists()) {
                _myProfilePhotoPath.value = fallbackMiniFile.absolutePath
                try {
                    com.teledrive.app.TeleDriveApplication.instance.preferences.setTelegramProfilePhotoPath(fallbackMiniFile.absolutePath)
                } catch (_: Exception) {}
                return@withContext fallbackMiniFile
            }

            null
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.e("TDLib", "Failed to fetch/download Telegram profile photo: ${e.message}", e)
            null
        }
    }

    suspend fun downloadProfilePhoto(fileId: Int, context: Context): File? {
        val persistentFile = File(context.filesDir, "profile_photo_$fileId.jpg")
        if (persistentFile.exists() && persistentFile.length() > 0) {
            return persistentFile
        }
        val cacheFile = File(context.cacheDir, "profile_photo_$fileId.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                cacheFile.copyTo(persistentFile, overwrite = true)
                return persistentFile
            } catch (_: Exception) {
                return cacheFile
            }
        }

        // Check if TDLib local cache already has it completed
        try {
            val tdFile = getFile(fileId)
            if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                val src = File(tdFile.local.path)
                src.copyTo(persistentFile, overwrite = true)
                return persistentFile
            }
        } catch (_: Exception) {}

        // Try synchronous download with priority 1 (highest)
        try {
            val res = sendRequest(TdApi.DownloadFile(fileId, 1, 0, 0, true))
            if (res is TdApi.File && res.local.isDownloadingCompleted && res.local.path.isNotEmpty() && File(res.local.path).exists()) {
                val src = File(res.local.path)
                src.copyTo(persistentFile, overwrite = true)
                return persistentFile
            }
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("TDLib", "Sync DownloadFile error for fileId=$fileId: ${e.message}")
        }

        return try {
            val path = downloadFile(fileId, 1)
            if (path.isNotBlank() && File(path).exists()) {
                val src = File(path)
                src.copyTo(persistentFile, overwrite = true)
                persistentFile
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @Volatile
    var cachedSavedMessagesChatId: Long = 0L
    private val savedMessagesMutex = Mutex()

    suspend fun getSavedMessagesChatId(): Long {
        if (cachedSavedMessagesChatId != 0L) return cachedSavedMessagesChatId
        return savedMessagesMutex.withLock {
            // Double-check after acquiring lock
            if (cachedSavedMessagesChatId != 0L) return@withLock cachedSavedMessagesChatId
            val me = getMeUser()
            val privateChat = sendRequest(TdApi.CreatePrivateChat(me.id, false)) as TdApi.Chat
            cachedSavedMessagesChatId = privateChat.id
            privateChat.id
        }
    }

    suspend fun getMessageInfo(chatId: Long, messageId: Long): TdMessageInfo? {
        return try {
            val message = sendRequest(TdApi.GetMessage(chatId, messageId)) as TdApi.Message
            parseMessageInfo(message)
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("TDLib", "getMessage failed for chatId=$chatId, msgId=$messageId: ${e.message}")
            null
        }
    }

    suspend fun rehydrateAndDownloadFile(
        chatId: Long,
        messageId: Long,
        preferredFileId: Int,
        priority: Int = 32,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        var activeFileId = preferredFileId

        // 1. Verify if preferredFileId is valid in the current TDLib session
        if (activeFileId != 0) {
            try {
                val tdFile = getFile(activeFileId)
                if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                    onProgress?.invoke(tdFile.size, tdFile.size)
                    return tdFile.local.path
                }
                if (!tdFile.local.canBeDownloaded) {
                    activeFileId = 0
                }
            } catch (e: Exception) {
                // preferredFileId is invalid or from a previous session! Reset to 0 immediately!
                activeFileId = 0
            }
        }

        // 2. If stale or 0, rehydrate fresh file ID from Telegram message
        if (activeFileId == 0 && chatId != 0L && messageId != 0L) {
            try {
                val info = getMessageInfo(chatId, messageId)
                if (info != null && info.documentFileId != 0) {
                    activeFileId = info.documentFileId
                    try {
                        val app = TeleDriveApplication.instance
                        val fileEntity = app.database.fileDao().getByMessageId(chatId, messageId)
                        if (fileEntity != null) {
                            app.database.fileDao().updateFileIds(fileEntity.fileId, info.documentFileId, info.thumbnailFileId)
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // 3. Initiate and wait for download with verified activeFileId
        if (activeFileId != 0) {
            try {
                startDownload(activeFileId, priority)
            } catch (_: Exception) {}

            try {
                val path = downloadFile(activeFileId, priority, timeoutMs = 45_000L, onProgress = onProgress)
                if (path.isNotEmpty() && File(path).exists()) {
                    return path
                }
            } catch (_: Exception) {}
        }
        return ""
    }

    suspend fun openChat(chatId: Long) {
        try {
            sendRequest(TdApi.OpenChat(chatId))
            com.teledrive.app.core.AppLogger.logTdLib("OpenChat", "Opened chat stream for chatId=$chatId")
        } catch (e: Exception) {
            com.teledrive.app.core.AppLogger.w("TDLib", "Failed to openChat $chatId: ${e.message}")
        }
    }

    suspend fun closeChat(chatId: Long) {
        try {
            sendRequest(TdApi.CloseChat(chatId))
        } catch (ignored: Exception) {}
    }

    private fun parseMessageInfo(message: TdApi.Message): TdMessageInfo? {
        when (val content = message.content) {
            is TdApi.MessageDocument -> {
                val doc = content.document
                val thumbId = doc.thumbnail?.file?.id
                val size = if (doc.document.size > 0) doc.document.size else doc.document.expectedSize.toLong()
                return TdMessageInfo(
                    messageId = message.id,
                    chatId = message.chatId,
                    date = message.date,
                    caption = content.caption.text,
                    documentFileId = doc.document.id,
                    documentFileName = doc.fileName.ifBlank { "Document_${message.date}_${message.id}" },
                    documentSize = size,
                    documentMimeType = doc.mimeType.ifBlank { "application/octet-stream" },
                    thumbnailFileId = thumbId
                )
            }
            is TdApi.MessagePhoto -> {
                val photo = content.photo
                val bestSize = photo.sizes.maxByOrNull { it.width * it.height } ?: photo.sizes.lastOrNull()
                val thumbSize = photo.sizes.minByOrNull { it.width * it.height } ?: photo.sizes.firstOrNull()
                if (bestSize != null) {
                    val size = if (bestSize.photo.size > 0) bestSize.photo.size else bestSize.photo.expectedSize.toLong()
                    return TdMessageInfo(
                        messageId = message.id,
                        chatId = message.chatId,
                        date = message.date,
                        caption = content.caption.text,
                        documentFileId = bestSize.photo.id,
                        documentFileName = "Photo_${message.date}_${message.id}.jpg",
                        documentSize = size,
                        documentMimeType = "image/jpeg",
                        thumbnailFileId = thumbSize?.photo?.id
                    )
                }
            }
            is TdApi.MessageVideo -> {
                val video = content.video
                val thumbId = video.thumbnail?.file?.id
                val size = if (video.video.size > 0) video.video.size else video.video.expectedSize.toLong()
                return TdMessageInfo(
                    messageId = message.id,
                    chatId = message.chatId,
                    date = message.date,
                    caption = content.caption.text,
                    documentFileId = video.video.id,
                    documentFileName = video.fileName.ifBlank { "Video_${message.date}_${message.id}.mp4" },
                    documentSize = size,
                    documentMimeType = video.mimeType.ifBlank { "video/mp4" },
                    thumbnailFileId = thumbId
                )
            }
            is TdApi.MessageVideoNote -> {
                val vn = content.videoNote
                val thumbId = vn.thumbnail?.file?.id
                val size = if (vn.video.size > 0) vn.video.size else vn.video.expectedSize.toLong()
                return TdMessageInfo(
                    messageId = message.id,
                    chatId = message.chatId,
                    date = message.date,
                    caption = "",
                    documentFileId = vn.video.id,
                    documentFileName = "VideoNote_${message.date}_${message.id}.mp4",
                    documentSize = size,
                    documentMimeType = "video/mp4",
                    thumbnailFileId = thumbId
                )
            }
            is TdApi.MessageAudio -> {
                val audio = content.audio
                val thumbId = audio.albumCoverThumbnail?.file?.id
                val size = if (audio.audio.size > 0) audio.audio.size else audio.audio.expectedSize.toLong()
                return TdMessageInfo(
                    messageId = message.id,
                    chatId = message.chatId,
                    date = message.date,
                    caption = content.caption.text,
                    documentFileId = audio.audio.id,
                    documentFileName = audio.fileName.ifBlank { "Audio_${message.date}_${message.id}.mp3" },
                    documentSize = size,
                    documentMimeType = audio.mimeType.ifBlank { "audio/mpeg" },
                    thumbnailFileId = thumbId
                )
            }
            is TdApi.MessageAnimation -> {
                val animation = content.animation
                val thumbId = animation.thumbnail?.file?.id
                val size = if (animation.animation.size > 0) animation.animation.size else animation.animation.expectedSize.toLong()
                return TdMessageInfo(
                    messageId = message.id,
                    chatId = message.chatId,
                    date = message.date,
                    caption = content.caption.text,
                    documentFileId = animation.animation.id,
                    documentFileName = animation.fileName.ifBlank { "Animation_${message.date}_${message.id}.gif" },
                    documentSize = size,
                    documentMimeType = animation.mimeType.ifBlank { "image/gif" },
                    thumbnailFileId = thumbId
                )
            }
        }
        return null
    }
}
