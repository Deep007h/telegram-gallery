package com.teledrive.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teledrive.app.core.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teledrive_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        private val STORAGE_CHANNEL_ID = longPreferencesKey("storage_channel_id")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val VIEW_MODE = stringPreferencesKey("view_mode")
        private val SORT_BY = stringPreferencesKey("sort_by")
        private val API_ID = intPreferencesKey("telegram_api_id")
        private val API_HASH = stringPreferencesKey("telegram_api_hash")
        private val OTA_UPDATE_URL = stringPreferencesKey("ota_update_url")
        private val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        private val BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        private val BOT_CHAT_ID = stringPreferencesKey("telegram_bot_chat_id")
        private val LOGIN_TYPE = stringPreferencesKey("telegram_login_type")
        private val USER_DISPLAY_NAME = stringPreferencesKey("telegram_user_display_name")
        private val LOCAL_GALLERY_MODE = booleanPreferencesKey("local_gallery_mode")
        private val CUSTOM_PROFILE_PHOTO_PATH = stringPreferencesKey("custom_profile_photo_path")
        private val TELEGRAM_PROFILE_PHOTO_PATH = stringPreferencesKey("telegram_profile_photo_path")
        private val SAVED_MESSAGES_CHAT_ID = longPreferencesKey("saved_messages_chat_id")
        private val STORAGE_CHAT_ID = longPreferencesKey("selected_storage_chat_id")
        private val STORAGE_CHAT_TITLE = stringPreferencesKey("selected_storage_chat_title")
        private val LINKED_CHATS_JSON = stringPreferencesKey("linked_storage_chats_json")
        private val CUSTOM_API_CONFIGURED = booleanPreferencesKey("custom_api_configured")
    }

    fun getCachedTelegramProfilePhotoPath(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_telegram_profile_photo_path", "") ?: ""
    }

    fun setCachedTelegramProfilePhotoPath(path: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_telegram_profile_photo_path", path).apply()
    }

    val telegramProfilePhotoPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TELEGRAM_PROFILE_PHOTO_PATH] ?: ""
    }

    suspend fun setTelegramProfilePhotoPath(path: String) {
        setCachedTelegramProfilePhotoPath(path)
        context.dataStore.edit { prefs ->
            prefs[TELEGRAM_PROFILE_PHOTO_PATH] = path
        }
    }

    fun getCachedSavedMessagesChatId(): Long {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getLong("fast_saved_messages_chat_id", 0L)
    }

    fun setCachedSavedMessagesChatId(id: Long) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putLong("fast_saved_messages_chat_id", id).apply()
    }

    val savedMessagesChatId: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[SAVED_MESSAGES_CHAT_ID]
    }

    suspend fun setSavedMessagesChatId(id: Long) {
        setCachedSavedMessagesChatId(id)
        context.dataStore.edit { prefs ->
            prefs[SAVED_MESSAGES_CHAT_ID] = id
        }
    }

    fun getCachedStorageChatId(): Long {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getLong("fast_selected_storage_chat_id", 0L)
    }

    fun setCachedStorageChatId(id: Long) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putLong("fast_selected_storage_chat_id", id).apply()
    }

    val storageChatId: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[STORAGE_CHAT_ID]
    }

    suspend fun setStorageChatId(id: Long) {
        setCachedStorageChatId(id)
        context.dataStore.edit { prefs ->
            prefs[STORAGE_CHAT_ID] = id
        }
    }

    fun getCachedStorageChatTitle(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_selected_storage_chat_title", "") ?: ""
    }

    fun setCachedStorageChatTitle(title: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_selected_storage_chat_title", title).apply()
    }

    val storageChatTitle: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[STORAGE_CHAT_TITLE] ?: ""
    }

    suspend fun setStorageChatTitle(title: String) {
        setCachedStorageChatTitle(title)
        context.dataStore.edit { prefs ->
            prefs[STORAGE_CHAT_TITLE] = title
        }
    }

    fun getCachedBotToken(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_bot_token", "") ?: ""
    }

    fun getBotTokenSync(): String = getCachedBotToken()

    fun setCachedBotToken(token: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_bot_token", token).apply()
    }

    val botToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BOT_TOKEN] ?: ""
    }

    suspend fun setBotToken(token: String) {
        setCachedBotToken(token)
        context.dataStore.edit { prefs ->
            prefs[BOT_TOKEN] = token
        }
    }

    fun getCachedBotChatId(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_bot_chat_id", "") ?: ""
    }

    fun setCachedBotChatId(chatId: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_bot_chat_id", chatId).apply()
    }

    val botChatId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BOT_CHAT_ID] ?: ""
    }

    suspend fun setBotChatId(chatId: String) {
        setCachedBotChatId(chatId)
        context.dataStore.edit { prefs ->
            prefs[BOT_CHAT_ID] = chatId
        }
    }

    fun getCachedLoginType(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_login_type", "phone") ?: "phone"
    }

    fun setCachedLoginType(type: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_login_type", type).apply()
    }

    val loginType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LOGIN_TYPE] ?: "phone"
    }

    suspend fun setLoginType(type: String) {
        setCachedLoginType(type)
        context.dataStore.edit { prefs ->
            prefs[LOGIN_TYPE] = type
        }
    }

    fun getCachedLinkedChatsJson(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_linked_chats_json", "[]") ?: "[]"
    }

    fun setCachedLinkedChatsJson(json: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_linked_chats_json", json).apply()
    }

    val linkedChatsJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LINKED_CHATS_JSON] ?: "[]"
    }

    suspend fun setLinkedChatsJson(json: String) {
        setCachedLinkedChatsJson(json)
        context.dataStore.edit { prefs ->
            prefs[LINKED_CHATS_JSON] = json
        }
    }

    fun getLinkedChats(): List<com.teledrive.app.telegram.TdLibManager.TelegramChatSummary> {
        val json = getCachedLinkedChatsJson()
        if (json.isBlank() || json == "[]") return emptyList()
        val list = mutableListOf<com.teledrive.app.telegram.TdLibManager.TelegramChatSummary>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id")
                if (id != 0L) {
                    list.add(
                        com.teledrive.app.telegram.TdLibManager.TelegramChatSummary(
                            id = id,
                            title = obj.optString("title", "Chat $id"),
                            typeDescription = obj.optString("type", "Telegram Chat"),
                            isSavedMessages = false,
                            username = obj.optString("username").ifBlank { null }
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    suspend fun addLinkedChat(chat: com.teledrive.app.telegram.TdLibManager.TelegramChatSummary) {
        val current = getLinkedChats().toMutableList()
        current.removeAll { it.id == chat.id }
        current.add(0, chat)
        val array = org.json.JSONArray()
        for (c in current) {
            val obj = org.json.JSONObject()
            obj.put("id", c.id)
            obj.put("title", c.title)
            obj.put("type", c.typeDescription)
            c.username?.let { obj.put("username", it) }
            array.put(obj)
        }
        setLinkedChatsJson(array.toString())
    }

    fun getCachedUserDisplayName(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_user_display_name", "") ?: ""
    }

    fun setCachedUserDisplayName(name: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_user_display_name", name).apply()
    }

    val userDisplayName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USER_DISPLAY_NAME] ?: ""
    }

    suspend fun setUserDisplayName(name: String) {
        setCachedUserDisplayName(name)
        context.dataStore.edit { prefs ->
            prefs[USER_DISPLAY_NAME] = name
        }
    }

    val localGalleryMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LOCAL_GALLERY_MODE] ?: false
    }

    suspend fun setLocalGalleryMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LOCAL_GALLERY_MODE] = enabled
        }
    }

    val customProfilePhotoPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_PROFILE_PHOTO_PATH] ?: ""
    }

    suspend fun setCustomProfilePhotoPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_PROFILE_PHOTO_PATH] = path
        }
    }

    val storageChannelId: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[STORAGE_CHANNEL_ID]
    }

    suspend fun setStorageChannelId(id: Long) {
        context.dataStore.edit { prefs ->
            prefs[STORAGE_CHANNEL_ID] = id
        }
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    val viewMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[VIEW_MODE] ?: "grid"
    }

    suspend fun setViewMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[VIEW_MODE] = mode
        }
    }

    val sortBy: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SORT_BY] ?: "name_asc"
    }

    suspend fun setSortBy(sort: String) {
        context.dataStore.edit { prefs ->
            prefs[SORT_BY] = sort
        }
    }

    fun getCachedApiId(): Int {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getInt("fast_api_id", 0)
    }

    fun setCachedApiId(id: Int) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putInt("fast_api_id", id).apply()
    }

    val apiId: Flow<Int> = context.dataStore.data.map { prefs ->
        val cached = getCachedApiId()
        if (cached > 0) cached else (prefs[API_ID] ?: Constants.API_ID)
    }

    suspend fun setApiId(id: Int) {
        setCachedApiId(id)
        context.dataStore.edit { prefs ->
            prefs[API_ID] = id
        }
    }

    fun getCachedApiHash(): String {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        return sp.getString("fast_api_hash", "") ?: ""
    }

    fun setCachedApiHash(hash: String) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("fast_api_hash", hash).apply()
    }

    val apiHash: Flow<String> = context.dataStore.data.map { prefs ->
        val cached = getCachedApiHash()
        if (cached.isNotBlank()) cached else (prefs[API_HASH] ?: Constants.API_HASH)
    }

    suspend fun setApiHash(hash: String) {
        setCachedApiHash(hash)
        context.dataStore.edit { prefs ->
            prefs[API_HASH] = hash
        }
    }

    fun isCustomApiConfigured(): Boolean {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        val configured = sp.getBoolean("fast_custom_api_configured", false)
        val id = getCachedApiId()
        val hash = getCachedApiHash()
        return configured || (id > 0 && hash.isNotBlank() && id != Constants.API_ID)
    }

    fun setCachedCustomApiConfigured(configured: Boolean) {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("fast_custom_api_configured", configured).apply()
    }

    val customApiConfigured: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_API_CONFIGURED] ?: isCustomApiConfigured()
    }

    suspend fun setCustomApiConfigured(configured: Boolean) {
        setCachedCustomApiConfigured(configured)
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_API_CONFIGURED] = configured
        }
    }

    val otaUpdateUrl: Flow<String> = context.dataStore.data.map { prefs ->
        val saved = prefs[OTA_UPDATE_URL]
        if (saved.isNullOrBlank() || saved.contains("teledrive-org")) {
            Constants.DEFAULT_OTA_UPDATE_URL
        } else {
            saved
        }
    }

    suspend fun setOtaUpdateUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[OTA_UPDATE_URL] = url
        }
    }

    val autoCheckUpdates: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_CHECK_UPDATES] ?: true
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_CHECK_UPDATES] = enabled
        }
    }

    val lastUpdateCheckTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_UPDATE_CHECK_TIME] ?: 0L
    }

    suspend fun setLastUpdateCheckTime(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_UPDATE_CHECK_TIME] = timestamp
        }
    }

    suspend fun clear() {
        val sp = context.getSharedPreferences("teledrive_fast_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().apply()
        context.dataStore.edit { it.clear() }
    }
}
