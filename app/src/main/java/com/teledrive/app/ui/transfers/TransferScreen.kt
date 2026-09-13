package com.teledrive.app.ui.transfers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teledrive.app.core.FileUtils
import com.teledrive.app.core.getMimeType
import com.teledrive.app.core.toFormattedSize
import com.teledrive.app.data.db.entity.TransferEntity
import com.teledrive.app.ui.components.FileIcon
import java.io.File
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit,
    viewModel: TransferViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.activeTransfers.isEmpty() && uiState.completedTransfers.isEmpty() && uiState.failedTransfers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No transfers yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                    if (uiState.activeTransfers.isNotEmpty()) {
                        item { SectionHeader("Active Transfers") }
                        items(uiState.activeTransfers, key = { "active_${it.transferId}" }) { transfer ->
                            TransferItem(
                                transfer = transfer,
                                onAction = { viewModel.cancelTransfer(transfer.transferId) },
                                actionIcon = { Icon(Icons.Default.Cancel, "Cancel") }
                            )
                        }
                    }

                    if (uiState.failedTransfers.isNotEmpty()) {
                        item { SectionHeader("Failed Transfers") }
                        items(uiState.failedTransfers, key = { "failed_${it.transferId}" }) { transfer ->
                            TransferItem(
                                transfer = transfer,
                                onAction = { viewModel.retryTransfer(transfer.transferId) },
                                actionIcon = { Icon(Icons.Default.Refresh, "Retry") }
                            )
                        }
                    }

                    if (uiState.completedTransfers.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionHeader("Completed")
                                TextButton(onClick = { viewModel.clearCompleted() }) {
                                    Text("Clear all")
                                }
                            }
                        }
                    items(uiState.completedTransfers, key = { "comp_${it.transferId}" }, contentType = { "transfer" }) { transfer ->
                        // Existence is resolved inside TransferItem off-main;
                        // don't stat here per frame (was File.exists on composition).
                        TransferItem(
                            transfer = transfer,
                            onClick = {
                                FileUtils.openFile(context, transfer.localFilePath, transfer.fileName)
                            },
                            onAction = {
                                FileUtils.openFile(context, transfer.localFilePath, transfer.fileName)
                            },
                            actionIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open file",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun TransferItem(
    transfer: TransferEntity,
    onClick: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: @Composable (() -> Unit)? = null,
    showAction: Boolean = true
) {
    val progress = if (transfer.fileSize > 0) {
        (transfer.transferredBytes.toFloat() / transfer.fileSize).coerceIn(0f, 1f)
    } else 0f

    // File.exists() is disk I/O: resolve off-composition and cache per path.
    var fileExists by remember(transfer.localFilePath) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(transfer.localFilePath) {
        fileExists = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { File(transfer.localFilePath).exists() } catch (_: Exception) { false }
        }
    }
    val existsResolved = fileExists ?: false

    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }

    ListItem(
        modifier = modifier,
        headlineContent = {
            Text(
                text = transfer.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (transfer.status == "IN_PROGRESS") {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
                Text(
                    text = "${transfer.type} • ${transfer.status} • ${transfer.fileSize.toFormattedSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (transfer.status == "COMPLETED" && fileExists == false) {
                    Text(
                        text = "File not found locally",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        leadingContent = {
            FileIcon(
                mimeType = transfer.fileName.getMimeType(),
                modifier = Modifier.size(40.dp),
                tint = if (transfer.status == "COMPLETED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        },
        trailingContent = {
            if (showAction && onAction != null && actionIcon != null && (transfer.status != "COMPLETED" || existsResolved)) {
                IconButton(onClick = onAction) {
                    actionIcon()
                }
            }
        }
    )
}
