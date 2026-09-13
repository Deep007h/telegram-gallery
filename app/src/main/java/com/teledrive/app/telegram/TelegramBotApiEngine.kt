package com.teledrive.app.telegram

import com.teledrive.app.core.AppLogger
import com.teledrive.app.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class BotMessageResult(
    val messageId: Long,
    val fileId: String,
    val thumbnailFileId: String?,
    val fileSize: Long,
    val fileName: String,
    val mimeType: String,
    val timestamp: Long
)

data class BotChatInfo(
    val id: Long,
    val title: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val type: String,
    val photoFileId: String?
)

data class BotMediaItem(
    val messageId: Long,
    val chatId: Long,
    val date: Long,
    val caption: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val fileId: String,
    val thumbFileId: String?
)

object TelegramBotApiEngine {
    private const val TAG = "BotApiEngine"
    private const val API_BASE = "https://api.telegram.org"

    /**
     * Cache mapping messageId -> Telegram file_id (for fast lookups during download/preview).
     */
    val messageToFileIdMap = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * Upload a file to Telegram using Bot API sendDocument with streaming progress.
     */
    suspend fun uploadDocument(
        botToken: String,
        chatId: Long,
        file: File,
        caption: String,
        fileName: String,
        mimeType: String,
        onProgress: suspend (bytesUploaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<BotMessageResult> = withContext(Dispatchers.IO) {
        val token = botToken.ifBlank { Constants.DEFAULT_BOT_TOKEN }
        val targetChatId = if (chatId != 0L) chatId else Constants.DEFAULT_USER_CHAT_ID

        AppLogger.i(TAG, "Starting Bot API upload: ${file.name} (${file.length()} bytes) to chatId=$targetChatId")

        var connection: HttpURLConnection? = null
        try {
            val boundary = "TeleDriveBoundary" + System.currentTimeMillis()
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$API_BASE/bot$token/sendDocument")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val totalFileSize = file.length()
            val charset = StandardCharsets.UTF_8

            connection.setChunkedStreamingMode(64 * 1024) // 64 KB chunks

            val outputStream = BufferedOutputStream(connection.outputStream, 64 * 1024)
            val writer = PrintWriter(OutputStreamWriter(outputStream, charset), true)

            // Form Field: chat_id
            writer.append("$twoHyphens$boundary$lineEnd")
            writer.append("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd")
            writer.append("$targetChatId$lineEnd")
            writer.flush()

            // Form Field: caption
            if (caption.isNotBlank()) {
                writer.append("$twoHyphens$boundary$lineEnd")
                writer.append("Content-Disposition: form-data; name=\"caption\"$lineEnd$lineEnd")
                writer.append("$caption$lineEnd")
                writer.flush()
            }

            // Form Field: document
            val safeFileName = fileName.ifBlank { file.name }
            val safeMime = mimeType.ifBlank { "application/octet-stream" }
            writer.append("$twoHyphens$boundary$lineEnd")
            writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"$safeFileName\"$lineEnd")
            writer.append("Content-Type: $safeMime$lineEnd$lineEnd")
            writer.flush()

            // Stream file contents with live progress
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalBytesSent = 0L

            FileInputStream(file).use { fis ->
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    onProgress(totalBytesSent, totalFileSize)
                }
                outputStream.flush()
            }

            // Multipart footer
            writer.append(lineEnd)
            writer.append("$twoHyphens$boundary$twoHyphens$lineEnd")
            writer.flush()
            outputStream.flush()

            val responseCode = connection.responseCode
            val responseStream = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseText = responseStream.bufferedReader().use { it.readText() }
            AppLogger.i(TAG, "Bot API response ($responseCode): ${responseText.take(300)}")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(responseText)
                if (json.optBoolean("ok", false)) {
                    val result = json.getJSONObject("result")
                    val msgId = result.getLong("message_id")
                    val date = result.optLong("date", System.currentTimeMillis() / 1000L)

                    var fileId = ""
                    var thumbId: String? = null
                    var fSize = totalFileSize

                    val doc = result.optJSONObject("document")
                    val photos = result.optJSONArray("photo")
                    val video = result.optJSONObject("video")

                    if (doc != null) {
                        fileId = doc.getString("file_id")
                        fSize = doc.optLong("file_size", totalFileSize)
                        thumbId = doc.optJSONObject("thumbnail")?.optString("file_id")
                            ?: doc.optJSONObject("thumb")?.optString("file_id")
                    } else if (video != null) {
                        fileId = video.getString("file_id")
                        fSize = video.optLong("file_size", totalFileSize)
                        thumbId = video.optJSONObject("thumbnail")?.optString("file_id")
                    } else if (photos != null && photos.length() > 0) {
                        val largest = photos.getJSONObject(photos.length() - 1)
                        fileId = largest.getString("file_id")
                        fSize = largest.optLong("file_size", totalFileSize)
                    }

                    if (fileId.isNotBlank() && msgId != 0L) {
                        messageToFileIdMap[msgId] = fileId
                    }
                    onProgress(totalFileSize, totalFileSize)
                    Result.success(
                        BotMessageResult(
                            messageId = msgId,
                            fileId = fileId,
                            thumbnailFileId = thumbId,
                            fileSize = fSize,
                            fileName = safeFileName,
                            mimeType = safeMime,
                            timestamp = date * 1000L
                        )
                    )
                } else {
                    val desc = json.optString("description", "Unknown Telegram Bot API error")
                    Result.failure(Exception("Bot API error: $desc"))
                }
            } else {
                Result.failure(Exception("HTTP $responseCode from Telegram Bot API: $responseText"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Upload failed with exception: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Download a file using Telegram Bot API getFile and direct file URL stream.
     */
    suspend fun downloadFile(
        botToken: String,
        fileId: String,
        destFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val token = botToken.ifBlank { Constants.DEFAULT_BOT_TOKEN }
        AppLogger.i(TAG, "Resolving file download for fileId=$fileId")

        var connection: HttpURLConnection? = null
        try {
            // 1. Get file path
            val getFileUrl = URL("$API_BASE/bot$token/getFile?file_id=${URLEncoder.encode(fileId, "UTF-8")}")
            val getFileConn = (getFileUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            if (getFileConn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("getFile returned HTTP ${getFileConn.responseCode}"))
            }

            val getFileJson = JSONObject(getFileConn.inputStream.bufferedReader().use { it.readText() })
            if (!getFileJson.optBoolean("ok", false)) {
                return@withContext Result.failure(Exception("getFile failed: ${getFileJson.optString("description")}"))
            }

            val result = getFileJson.getJSONObject("result")
            val filePath = result.getString("file_path")
            val totalSize = result.optLong("file_size", 0L)

            // 2. Download from https://api.telegram.org/file/bot<token>/<file_path>
            val downloadUrl = URL("$API_BASE/file/bot$token/$filePath")
            connection = (downloadUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Download failed HTTP ${connection.responseCode}"))
            }

            val effectiveTotal = if (totalSize > 0L) totalSize else connection.contentLengthLong

            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.downloading_${System.currentTimeMillis()}")

            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        onProgress(totalRead, effectiveTotal)
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
                AppLogger.i(TAG, "Downloaded file successfully to ${destFile.absolutePath} (${destFile.length()} bytes)")
                Result.success(destFile)
            } else {
                tempFile.delete()
                Result.failure(Exception("Downloaded file was empty"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Query chat info from Bot API getChat.
     */
    suspend fun fetchChatInfo(botToken: String, chatId: Long): Result<BotChatInfo> = withContext(Dispatchers.IO) {
        val token = botToken.ifBlank { Constants.DEFAULT_BOT_TOKEN }
        try {
            val url = URL("$API_BASE/bot$token/getChat?chat_id=$chatId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("ok", false)) {
                    val res = json.getJSONObject("result")
                    val id = res.getLong("id")
                    val title = res.optString("title").ifBlank {
                        val fn = res.optString("first_name", "")
                        val ln = res.optString("last_name", "")
                        "$fn $ln".trim().ifBlank { "Personal Chat ($id)" }
                    }
                    val username = res.optString("username").ifBlank { null }
                    val fn = res.optString("first_name").ifBlank { null }
                    val ln = res.optString("last_name").ifBlank { null }
                    val type = res.optString("type", "private")
                    val photoObj = res.optJSONObject("photo")
                    val photoId = photoObj?.optString("big_file_id") ?: photoObj?.optString("small_file_id")

                    Result.success(
                        BotChatInfo(
                            id = id,
                            title = title,
                            username = username,
                            firstName = fn,
                            lastName = ln,
                            type = type,
                            photoFileId = photoId
                        )
                    )
                } else {
                    Result.failure(Exception(json.optString("description", "getChat failed")))
                }
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download profile photo directly by file_id.
     */
    suspend fun downloadProfilePhoto(botToken: String, photoFileId: String, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        downloadFile(botToken, photoFileId, destFile)
    }

    /**
     * Fetch recent updates (messages, channel posts) from Bot API getUpdates.
     */
    suspend fun getRecentUpdates(botToken: String, offset: Long = 0L): Result<List<BotMediaItem>> = withContext(Dispatchers.IO) {
        val token = botToken.ifBlank { Constants.DEFAULT_BOT_TOKEN }
        try {
            val offsetParam = if (offset > 0L) "&offset=$offset" else ""
            val url = URL("$API_BASE/bot$token/getUpdates?limit=100$offsetParam")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("ok", false)) {
                    val array = json.optJSONArray("result") ?: JSONArray()
                    val items = mutableListOf<BotMediaItem>()

                    for (i in 0 until array.length()) {
                        val update = array.optJSONObject(i) ?: continue
                        val message = update.optJSONObject("message")
                            ?: update.optJSONObject("channel_post")
                            ?: update.optJSONObject("edited_message")
                            ?: update.optJSONObject("edited_channel_post")
                            ?: continue

                        val chat = message.optJSONObject("chat") ?: continue
                        val chatId = chat.optLong("id")
                        val messageId = message.optLong("message_id")
                        val date = message.optLong("date", System.currentTimeMillis() / 1000L)
                        val caption = message.optString("caption", "")

                        val doc = message.optJSONObject("document")
                        val video = message.optJSONObject("video")
                        val photos = message.optJSONArray("photo")

                        var fileName: String? = null
                        var mimeType = "application/octet-stream"
                        var fileSize = 0L
                        var fileId = ""
                        var thumbId: String? = null

                        if (doc != null) {
                            fileName = doc.optString("file_name", "Document_$messageId")
                            mimeType = doc.optString("mime_type", "application/octet-stream")
                            fileSize = doc.optLong("file_size", 0L)
                            fileId = doc.optString("file_id", "")
                            thumbId = doc.optJSONObject("thumbnail")?.optString("file_id")
                        } else if (video != null) {
                            fileName = video.optString("file_name", "Video_$messageId.mp4")
                            mimeType = video.optString("mime_type", "video/mp4")
                            fileSize = video.optLong("file_size", 0L)
                            fileId = video.optString("file_id", "")
                            thumbId = video.optJSONObject("thumbnail")?.optString("file_id")
                        } else if (photos != null && photos.length() > 0) {
                            val smallest = photos.optJSONObject(0)
                            thumbId = smallest?.optString("file_id")
                            val largest = photos.optJSONObject(photos.length() - 1)
                            fileName = "Photo_$messageId.jpg"
                            mimeType = "image/jpeg"
                            fileSize = largest?.optLong("file_size", 0L) ?: 0L
                            fileId = largest?.optString("file_id", "") ?: ""
                        }

                        if (fileName != null && fileId.isNotBlank()) {
                            messageToFileIdMap[messageId] = fileId
                            items.add(
                                BotMediaItem(
                                    messageId = messageId,
                                    chatId = chatId,
                                    date = date * 1000L,
                                    caption = caption,
                                    fileName = fileName,
                                    fileSize = fileSize,
                                    mimeType = mimeType,
                                    fileId = fileId,
                                    thumbFileId = thumbId
                                )
                            )
                        }
                    }
                    Result.success(items)
                } else {
                    Result.failure(Exception(json.optString("description", "getUpdates failed")))
                }
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
