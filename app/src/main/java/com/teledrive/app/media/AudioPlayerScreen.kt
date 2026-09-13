package com.teledrive.app.media

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.toFormattedSize
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(fileId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = TeleDriveApplication.instance
    val fileDao = app.database.fileDao()
    val tdLibManager = app.tdLibManager

    var fileEntity by remember(fileId) { mutableStateOf<FileEntity?>(null) }
    var localPath by remember(fileId) { mutableStateOf<String?>(null) }
    var isLoading by remember(fileId) { mutableStateOf(true) }

    // Key player on fileId so switching tracks releases the old codec instead
    // of reusing one player across files (stale timeline + leaks).
    val exoPlayer = remember(fileId) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }
    DisposableEffect(fileId) {
        onDispose {
            try { exoPlayer.pause() } catch (_: Exception) {}
            try { exoPlayer.release() } catch (_: Exception) {}
        }
    }

    var isPlaying by remember(fileId) { mutableStateOf(false) }
    var currentPosition by remember(fileId) { mutableLongStateOf(0L) }
    var duration by remember(fileId) { mutableLongStateOf(0L) }

    LaunchedEffect(fileId) {
        // Single download path: the old code called downloadFile() twice AND
        // collected fileUpdates with takeWhile (which excludes the completion
        // event), so completion was missed and the second downloadFile stalled.
        val found: FileEntity? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { fileDao.getById(fileId) } catch (_: Exception) { null }
        }
        fileEntity = found
        if (found == null) {
            isLoading = false
            return@LaunchedEffect
        }
        val hit: String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (found.telegramFileId != 0) {
                try {
                    val tdFile = tdLibManager.getFile(found.telegramFileId)
                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotEmpty() && java.io.File(tdFile.local.path).exists()) {
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
                priority = 1
            )
            if (path.isNotEmpty() && java.io.File(path).exists()) {
                localPath = path
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(localPath) {
        localPath?.let { path ->
            try {
                // Uri.fromFile (not Uri.parse): parse() drops the file scheme and
                // ExoPlayer fails to resolve bare absolute paths.
                val mediaItem = MediaItem.fromUri(Uri.fromFile(java.io.File(path)))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        try {
            while (true) {
                // 500ms polling recomposes the slider/time row only (state is
                // read below in a scoped section); listener cleanup on dispose.
                currentPosition = try { exoPlayer.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }
                delay(500L)
            }
        } finally {
            try { exoPlayer.removeListener(listener) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = fileEntity?.fileName ?: "Unknown Audio",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = fileEntity?.fileSize?.toFormattedSize() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(48.dp))

                AudioProgressBar(
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = { newPos ->
                        exoPlayer.seekTo(newPos)
                        currentPosition = newPos
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { exoPlayer.seekTo((currentPosition - 10000).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Rewind 10s", modifier = Modifier.size(48.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(onClick = { exoPlayer.seekTo((currentPosition + 10000).coerceAtMost(duration)) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward 10s", modifier = Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

/**
 * Extracted composable so that reads of [currentPosition] (updated every 500ms)
 * only recompose this slider + time row, instead of the entire AudioPlayerScreen.
 */
@Composable
private fun AudioProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    Column {
        Slider(
            value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { percent ->
                val newPos = (percent * duration).toLong()
                onSeek(newPos)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(currentPosition), style = MaterialTheme.typography.bodySmall)
            Text(text = formatTime(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}
