package com.teledrive.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.Constants
import com.teledrive.app.core.FileUtils
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.telegram.TelegramBotApiEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(fileId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val fileDao = app.database.fileDao()
    val tdLibManager = app.tdLibManager

    var fileEntity by remember(fileId) { mutableStateOf<FileEntity?>(null) }
    var localPath by remember(fileId) { mutableStateOf<String?>(null) }
    var isLoading by remember(fileId) { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(fileId) {
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
                priority = 32
            )
            if (path.isNotEmpty() && File(path).exists()) {
                localPath = path
                isLoading = false
                return@LaunchedEffect
            }
        } catch (_: Exception) {}

        // Fallback: Bot API download
        try {
            val botToken = app.preferences.getBotTokenSync() ?: Constants.DEFAULT_BOT_TOKEN
            val botFileId = TelegramBotApiEngine.getPersistedBotFileId(context, found.telegramMessageId)
            if (botFileId != null && botToken.isNotBlank()) {
                val dest = File(context.cacheDir, found.fileName)
                val botResult = TelegramBotApiEngine.downloadFile(botToken, botFileId, dest)
                if (botResult.isSuccess && dest.exists()) {
                    localPath = dest.absolutePath
                    isLoading = false
                    return@LaunchedEffect
                }
            }
        } catch (_: Exception) {}

        isLoading = false
    }

    var scale by remember(fileId) { mutableFloatStateOf(1f) }
    var offsetX by remember(fileId) { mutableFloatStateOf(0f) }
    var offsetY by remember(fileId) { mutableFloatStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileEntity?.fileName ?: "Image Preview",
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
                .pointerInput(fileId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX = (offsetX + pan.x).coerceIn(-800f, 800f)
                            offsetY = (offsetY + pan.y).coerceIn(-800f, 800f)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        ) {
            if (isLoading && localPath == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            } else if (localPath != null) {
                AsyncImage(
                    model = remember(localPath) { File(localPath!!) },
                    contentDescription = fileEntity?.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "Failed to load image preview",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
