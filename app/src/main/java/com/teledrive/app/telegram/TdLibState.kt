package com.teledrive.app.telegram

sealed class TdLibAuthState {
    data object Initial : TdLibAuthState()
    data object WaitTdlibParameters : TdLibAuthState()
    data object WaitPhoneNumber : TdLibAuthState()
    data class WaitOtherDeviceConfirmation(val link: String) : TdLibAuthState()
    data class WaitCode(val codeType: String = "sms") : TdLibAuthState()
    data class WaitPassword(val hint: String = "", val hasRecoveryEmail: Boolean = false) : TdLibAuthState()
    data object Ready : TdLibAuthState()
    data object LoggingOut : TdLibAuthState()
    data object Closing : TdLibAuthState()
    data object Closed : TdLibAuthState()
    data class Error(val message: String) : TdLibAuthState()
}

sealed class TdLibConnectionState {
    data object WaitingForNetwork : TdLibConnectionState()
    data object Connecting : TdLibConnectionState()
    data object ConnectingToProxy : TdLibConnectionState()
    data object Updating : TdLibConnectionState()
    data object Ready : TdLibConnectionState()
}

data class TdFileUpdate(
    val fileId: Int,
    val size: Long,
    val expectedSize: Long,
    val downloadedSize: Long,
    val uploadedSize: Long,
    val isDownloadingCompleted: Boolean,
    val isUploadingCompleted: Boolean,
    val localPath: String
)

data class TdMessageInfo(
    val messageId: Long,
    val chatId: Long,
    val date: Int,
    val caption: String?,
    val documentFileName: String?,
    val documentMimeType: String?,
    val documentSize: Long,
    val documentFileId: Int,
    val thumbnailFileId: Int?
)

data class TdChatInfo(
    val chatId: Long,
    val title: String,
    val type: String // "private", "supergroup", "channel"
)

class TdLibException(val code: Int, override val message: String) : Exception(message)
