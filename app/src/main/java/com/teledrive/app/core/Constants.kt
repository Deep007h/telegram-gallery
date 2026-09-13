package com.teledrive.app.core

object Constants {
    const val STORAGE_CHANNEL_TITLE = "📁 TeleDrive Storage"
    const val STORAGE_CHANNEL_DESCRIPTION = "TeleDrive cloud storage — do not delete this channel"
    const val METADATA_VERSION = "v1"
    const val MAX_FILE_SIZE = 2_000_000_000L
    const val DOWNLOAD_DIR = "TeleDrive"
    const val NOTIFICATION_CHANNEL_ID = "teledrive_transfers"
    const val TRANSFER_NOTIFICATION_ID = 1001
    const val PREFS_KEY_STORAGE_CHANNEL_ID = "storage_channel_id"
    const val PREFS_KEY_THEME_MODE = "theme_mode"
    const val PREFS_KEY_VIEW_MODE = "view_mode"
    
    // Default Telegram MTProto API Credentials (public demo keys - users should provide their own from my.telegram.org)
    const val API_ID = 6
    const val API_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e"
    const val DEFAULT_API_ID = API_ID
    const val DEFAULT_API_HASH = API_HASH
    
    const val CHAT_HISTORY_LIMIT = 100
    const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    const val SEARCH_DEBOUNCE_MS = 300L
    const val DEFAULT_OTA_UPDATE_URL = "https://api.github.com/repos/Deep007h/TeleDrive/releases/latest"
    
    // Telegram Bot API credentials
    const val DEFAULT_BOT_TOKEN = "8851265745:AAHN4WyXEiY8x7j9CY8qnOhiwp2kMbLlo40"
    const val DEFAULT_USER_CHAT_ID = 8353217839L
}
