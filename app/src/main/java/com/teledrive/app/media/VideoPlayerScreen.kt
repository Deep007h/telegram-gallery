package com.teledrive.app.media

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.teledrive.app.core.Constants
import com.teledrive.app.core.FileUtils
import com.teledrive.app.telegram.TelegramBotApiEngine
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(fileId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val fileDao = app.database.fileDao()
    val tdLibManager = app.tdLibManager

    var fileEntity by remember(fileId) { mutableStateOf<FileEntity?>(null) }
    var localPath by remember(fileId) { mutableStateOf<String?>(null) }
    var streamUri by remember(fileId) { mutableStateOf<Uri?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember(fileId) { mutableStateOf(true) }
    var downloadProgress by remember(fileId) { mutableStateOf(0f) }
    var statusText by remember(fileId) { mutableStateOf("Streaming video...") }

    // Key on fileId so navigating between videos releases the old codec.
    val exoPlayer = remember(fileId) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(fileId) {
        onDispose {
            try { exoPlayer.pause() } catch (_: Exception) {}
            try { exoPlayer.release() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(fileId) {
        // Room + disk probes off-main; single download path via rehydrate.
        val found: FileEntity? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { fileDao.getById(fileId) } catch (_: Exception) { null }
        }
        fileEntity = found
        if (found == null) {
            isLoading = false
            return@LaunchedEffect
        }
        val hit: String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cached = File(context.cacheDir, found.fileName)
            if (cached.exists() && cached.length() > 0) return@withContext cached.absolutePath
            try {
                val downloaded = File(FileUtils.getDownloadDir(context), found.fileName)
                if (downloaded.exists() && downloaded.length() > 0) return@withContext downloaded.absolutePath
            } catch (_: Exception) {}
            if (found.telegramFileId != 0) {
                try {
                    val tdFile = tdLibManager.getFile(found.telegramFileId)
                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && File(tdFile.local.path).exists()) {
                        return@withContext tdFile.local.path
                    }
                } catch (_: Exception) {}
            }
            null
        }
        if (hit != null) {
            localPath = hit
            isLoading = false
            return@LaunchedEffect
        }

        // Check if Bot API stream URL is available for instant progressive playback
        val botToken = app.preferences.getBotTokenSync() ?: Constants.DEFAULT_BOT_TOKEN
        val botFileId = TelegramBotApiEngine.getPersistedBotFileId(context, found.telegramMessageId)
        if (botFileId != null && botToken.isNotBlank()) {
            val liveUrl = TelegramBotApiEngine.getStreamUrl(botToken, botFileId)
            if (liveUrl != null) {
                streamUri = Uri.parse(liveUrl)
                isLoading = false
            }
        }

        if (found.telegramFileId == 0 && found.telegramMessageId == 0L) {
            isLoading = false
            return@LaunchedEffect
        }
        try {
            val chatId = if (found.telegramChatId != 0L) found.telegramChatId else tdLibManager.getSavedMessagesChatId()
            val path = tdLibManager.rehydrateAndDownloadFile(
                chatId = chatId,
                messageId = found.telegramMessageId,
                preferredFileId = found.telegramFileId,
                priority = 32,
                onProgress = { downloaded, total ->
                    if (total > 0) {
                        val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        downloadProgress = progress
                        statusText = "Streaming ${(progress * 100).toInt()}% • ${FileUtils.formatFileSize(downloaded)} / ${FileUtils.formatFileSize(total)}"
                    } else {
                        statusText = "Streaming ${FileUtils.formatFileSize(downloaded)}..."
                    }
                }
            )
            if (path.isNotEmpty() && File(path).exists()) {
                localPath = path
                isLoading = false
                return@LaunchedEffect
            }
        } catch (_: Exception) {
        }

        // Fallback to Bot API download if available
        if (streamUri == null && botFileId != null && botToken.isNotBlank()) {
            try {
                statusText = "Buffering via Telegram Bot API..."
                val dest = File(context.cacheDir, found.fileName)
                val botResult = TelegramBotApiEngine.downloadFile(
                    botToken = botToken,
                    fileId = botFileId,
                    destFile = dest,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            downloadProgress = progress
                            statusText = "Buffering ${(progress * 100).toInt()}% • ${FileUtils.formatFileSize(downloaded)} / ${FileUtils.formatFileSize(total)}"
                        }
                    }
                )
                if (botResult.isSuccess && dest.exists()) {
                    localPath = dest.absolutePath
                    isLoading = false
                    return@LaunchedEffect
                }
            } catch (_: Exception) {}
        }

        isLoading = false
    }

    LaunchedEffect(localPath, streamUri) {
        val targetUri = when {
            localPath != null -> Uri.fromFile(File(localPath!!))
            streamUri != null -> streamUri
            else -> null
        }
        targetUri?.let { uri ->
            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileEntity?.fileName ?: "Video Player",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        fileEntity?.let {
                            coroutineScope.launch {
                                app.transferManager.enqueueDownload(
                                    virtualPath = it.virtualPath,
                                    fileName = it.fileName,
                                    fileSize = it.fileSize,
                                    chatId = it.telegramChatId,
                                    messageId = it.telegramMessageId
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            if (isLoading && localPath == null && streamUri == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = statusText, color = Color.White)
                }
            } else if (localPath != null || streamUri != null) {
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Failed to load video",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
