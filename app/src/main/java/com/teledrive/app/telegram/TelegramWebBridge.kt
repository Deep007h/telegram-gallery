package com.teledrive.app.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import com.teledrive.app.core.AppLogger
import com.teledrive.app.data.db.dao.FileDao
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * JavaScript interface bridge for Telegram Web (Web K / Web A) WebView.
 * Enables automatic extraction of:
 * 1. User profile (display name, phone, username, and actual avatar image saved as JPEG)
 * 2. User chats & channels (including "Telegram Gallery Storage" and "Saved Messages")
 * 3. Media & files (written into Room DB FileDao for Google Photos display)
 */
class TelegramWebBridge(
    private val context: Context,
    private val preferences: AppPreferences,
    private val fileDao: FileDao,
    private val scope: CoroutineScope,
    private val onLoginSuccess: (userName: String) -> Unit,
    private val onLoginDetected: (userName: String) -> Unit = {},
    private val onSyncProgress: (itemCount: Int) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onLoginDetected(name: String) {
        val clean = name.trim()
        if (clean.isBlank() || clean.equals("undefined", ignoreCase = true) || clean.equals("null", ignoreCase = true)) {
            return
        }
        AppLogger.i("TelegramWebBridge", "Active Telegram login detected: $clean")
        mainHandler.post {
            onLoginDetected(clean)
        }
    }

    @JavascriptInterface
    fun postLoginFailed(message: String) {
        val cleanMsg = message.trim().ifBlank { "Please finish logging in on Telegram Web first" }
        AppLogger.w("TelegramWebBridge", "Login validation failed: cleanMsg")
        mainHandler.post {
            android.widget.Toast.makeText(context, cleanMsg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun postUserProfile(name: String, phone: String, username: String, avatarBase64: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanName = name.trim().takeIf {
                    it.isNotBlank() && !it.equals("undefined", ignoreCase = true) && !it.equals("null", ignoreCase = true)
                } ?: "Deep 007h"
                AppLogger.i("TelegramWebBridge", "Received user profile: name=$cleanName, phone=$phone, username=$username, hasAvatar=${!avatarBase64.isNullOrBlank()}")

                preferences.setUserDisplayName(cleanName)
                preferences.setLoginType("web")
                preferences.setLocalGalleryMode(false)

                if (!avatarBase64.isNullOrBlank()) {
                    try {
                        val cleanBase64 = if (avatarBase64.contains(",")) {
                            avatarBase64.substringAfter(",")
                        } else {
                            avatarBase64
                        }
                        val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        if (imageBytes != null && imageBytes.isNotEmpty()) {
                            val avatarFile = File(context.filesDir, "telegram_avatar_web_${System.currentTimeMillis()}.jpg")
                            // Clean up old web avatars
                            context.filesDir.listFiles { f -> f.name.startsWith("telegram_avatar_web_") }?.forEach { it.delete() }
                            FileOutputStream(avatarFile).use { fos ->
                                fos.write(imageBytes)
                                fos.flush()
                            }
                            if (avatarFile.exists() && avatarFile.length() > 0) {
                                preferences.setTelegramProfilePhotoPath(avatarFile.absolutePath)
                                AppLogger.i("TelegramWebBridge", "Saved Telegram profile photo to ${avatarFile.absolutePath} (${avatarFile.length()} bytes)")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w("TelegramWebBridge", "Failed to save avatar image: ${e.message}")
                    }
                } else {
                    // Fallback to bundled Spider-Man avatar
                    try {
                        val avatarFile = File(context.filesDir, "telegram_avatar_web_spiderman.jpg")
                        if (!avatarFile.exists() || avatarFile.length() == 0L) {
                            val resId = context.resources.getIdentifier("telegram_avatar_default", "drawable", context.packageName)
                            if (resId != 0) {
                                context.resources.openRawResource(resId).use { input ->
                                    FileOutputStream(avatarFile).use { fos -> input.copyTo(fos) }
                                }
                            }
                        }
                        if (avatarFile.exists() && avatarFile.length() > 0) {
                            preferences.setTelegramProfilePhotoPath(avatarFile.absolutePath)
                            AppLogger.i("TelegramWebBridge", "Applied Spider-Man avatar: ${avatarFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w("TelegramWebBridge", "Failed to copy default avatar: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("TelegramWebBridge", "Error handling postUserProfile: ${e.message}", e)
            }
        }
    }

    @JavascriptInterface
    fun postChats(chatsJson: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (chatsJson.isBlank() || chatsJson == "[]") return@launch
                val array = JSONArray(chatsJson)
                AppLogger.i("TelegramWebBridge", "Parsing ${array.length()} chats from Telegram Web")

                var foundStorageChat = false
                var firstChatId: Long = 0L
                var firstChatTitle = ""
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val idRaw = obj.opt("id")?.toString() ?: ""
                    val isChannel = idRaw.startsWith("c") || idRaw.startsWith("-100")
                    val isGroup = idRaw.startsWith("-") && !idRaw.startsWith("-100")
                    val parsedId = idRaw.removePrefix("c").removePrefix("u").removePrefix("-").toLongOrNull() ?: (1000000000L + i)

                    val formattedId = when {
                        idRaw.startsWith("-100") -> idRaw.toLongOrNull() ?: (-1000000000000L - parsedId)
                        isChannel -> -1000000000000L - parsedId
                        isGroup -> -parsedId
                        else -> parsedId // User private chat: positive Long (e.g. 8353217839)
                    }
                    val type = obj.optString("type", if (isChannel) "channel" else "chat")
                    val username = obj.optString("username").ifBlank { null }

                    val summary = TdLibManager.TelegramChatSummary(
                        id = formattedId,
                        title = title.ifBlank { "Telegram Chat $parsedId" },
                        typeDescription = if (isChannel) "Channel" else "Private Chat",
                        isSavedMessages = title.contains("Saved Messages", ignoreCase = true),
                        username = username
                    )
                    preferences.addLinkedChat(summary)

                    if (firstChatId == 0L) {
                        firstChatId = formattedId
                        firstChatTitle = summary.title
                    }

                    // Auto-select "Telegram Gallery Storage", "Personal Storage", or "Saved Messages" as storage target
                    if (!foundStorageChat && (
                            title.contains("Telegram Gallery Storage", ignoreCase = true) ||
                            title.contains("Saved Messages", ignoreCase = true) ||
                            formattedId == com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
                        )) {
                        preferences.setStorageChatId(formattedId)
                        preferences.setStorageChatTitle(title)
                        foundStorageChat = true
                        AppLogger.i("TelegramWebBridge", "Auto-selected active storage chat: $title (ID: $formattedId)")
                    }
                }

                if (!foundStorageChat) {
                    val defaultChatId = com.teledrive.app.core.Constants.DEFAULT_USER_CHAT_ID
                    val defaultTitle = "Personal Storage (Deep 007h)"
                    preferences.setStorageChatId(defaultChatId)
                    preferences.setStorageChatTitle(defaultTitle)
                    preferences.addLinkedChat(
                        TdLibManager.TelegramChatSummary(
                            id = defaultChatId,
                            title = defaultTitle,
                            typeDescription = "Personal Chat",
                            isSavedMessages = false,
                            username = "deep009h"
                        )
                    )
                    AppLogger.i("TelegramWebBridge", "Set default storage chat: $defaultTitle ($defaultChatId)")
                }
            } catch (e: Exception) {
                AppLogger.e("TelegramWebBridge", "Error parsing chats: ${e.message}", e)
            }
        }
    }

    @JavascriptInterface
    fun postMessages(chatIdStr: String, mediaJson: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (mediaJson.isBlank() || mediaJson == "[]") return@launch
                val array = JSONArray(mediaJson)
                AppLogger.i("TelegramWebBridge", "Received ${array.length()} media items from Telegram Web")

                val chatId = chatIdStr.toLongOrNull() ?: preferences.getCachedStorageChatId().takeIf { it != 0L } ?: -1001234567890L
                val entities = mutableListOf<FileEntity>()

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val msgId = obj.optLong("messageId", (i + 1).toLong())
                    val fileName = obj.optString("fileName", "Media_$msgId.jpg")
                    val fileSize = obj.optLong("fileSize", 204800L)
                    val mimeType = obj.optString("mimeType", "image/jpeg")
                    val timestamp = obj.optLong("timestamp", System.currentTimeMillis() - (i * 60000L))
                    val thumbBase64 = obj.optString("thumbBase64", "")

                    val entity = FileEntity(
                        telegramMessageId = msgId,
                        telegramChatId = chatId,
                        virtualPath = "/$fileName",
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        telegramFileId = (msgId % 100000).toInt(),
                        thumbnailFileId = null,
                        uploadTimestamp = timestamp,
                        parentFolderId = null,
                        isSynced = true
                    )
                    entities.add(entity)

                    // If base64 thumbnail is sent, cache it
                    if (thumbBase64.isNotBlank()) {
                        try {
                            val cleanBase64 = if (thumbBase64.contains(",")) thumbBase64.substringAfter(",") else thumbBase64
                            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                            if (bytes != null && bytes.isNotEmpty()) {
                                val thumbFile = com.teledrive.app.core.FastThumbnailCacheManager.getThumbnailFile(context, msgId.toString())
                                thumbFile.writeBytes(bytes)
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (entities.isNotEmpty()) {
                    fileDao.upsertAll(entities)
                    com.teledrive.app.TeleDriveApplication.instance.thumbnailCacheManager.preloadThumbnails(entities)
                    AppLogger.i("TelegramWebBridge", "Successfully upserted ${entities.size} media entities into Room database")
                    mainHandler.post { onSyncProgress(entities.size) }
                }
            } catch (e: Exception) {
                AppLogger.e("TelegramWebBridge", "Error inserting messages: ${e.message}", e)
            }
        }
    }

    @JavascriptInterface
    fun postLoginSuccess(userName: String) {
        AppLogger.i("TelegramWebBridge", "Login success confirmed by Web UI: $userName")
        scope.launch(Dispatchers.IO) {
            preferences.setLoginType("web")
            preferences.setLocalGalleryMode(false)
            if (userName.isNotBlank()) {
                preferences.setUserDisplayName(userName)
            }
        }
        mainHandler.post {
            onLoginSuccess(userName)
        }
    }

    @JavascriptInterface
    fun log(tag: String, msg: String) {
        AppLogger.i("Web::$tag", msg)
    }
}
