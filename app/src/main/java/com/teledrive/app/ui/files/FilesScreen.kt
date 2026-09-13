package com.teledrive.app.ui.files

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.core.FileUtils
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.ui.components.TelegramThumbnail
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

// Telegram Palette
private val TelegramDarkBg = Color(0xFF0E1621)
private val TelegramBubbleBg = Color(0xFF2B5278)
private val TelegramDateText = Color(0xFFE2E8F0)
private val TelegramSubText = Color(0xFF8DB7D8)
private val TelegramCheckBlue = Color(0xFF50B7F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    files: List<FileEntity>,
    onFileClick: (FileEntity) -> Unit,
    onDownloadClick: (FileEntity) -> Unit,
    onDeleteFile: (FileEntity) -> Unit = {},
    onUploadFiles: (List<Uri>) -> Unit = {},
    onSyncClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    var fileToDelete by remember { mutableStateOf<FileEntity?>(null) }
    var selectedFileMenu by remember { mutableStateOf<FileEntity?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onUploadFiles(uris)
            Toast.makeText(context, "Uploading ${uris.size} file(s) to Telegram Cloud...", Toast.LENGTH_SHORT).show()
        }
    }

    // Deduplicate and filter files. Sorted DESC (newest first) to match the
    // Photos feed; the old ASC order put the oldest files on top (bug).
    val distinctFiles = remember(files, searchQuery) {
        val list = if (searchQuery.isEmpty()) files
        else files.filter { it.fileName.contains(searchQuery, ignoreCase = true) }

        list.distinctBy {
            if (it.telegramMessageId != 0L) "${it.telegramChatId}_${it.telegramMessageId}"
            else if (it.fileId != 0L) "file_${it.fileId}"
            else "${it.fileName}_${it.fileSize}"
        }.sortedByDescending { it.uploadTimestamp }
    }

    // Group files by formatted date (e.g. "March 1", "June 16", "July 12")
    // SimpleDateFormat is not thread-safe: confine to this remember block.
    val groupedByDate = remember(distinctFiles) {
        val dateFormat = SimpleDateFormat("MMMM d", Locale.getDefault())
        distinctFiles.groupBy {
            dateFormat.format(Date(if (it.uploadTimestamp > 0) it.uploadTimestamp else System.currentTimeMillis()))
        }
    }

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search files...", color = Color.Gray, fontSize = 15.sp) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                text = "Saved Messages",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${distinctFiles.size} cloud files",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            searchQuery = ""
                            isSearchActive = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                        IconButton(onClick = onSyncClick) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync", tint = Color(0xFF38BDF8))
                        }
                        IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF17212B))
            )
        },
        containerColor = TelegramDarkBg
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TelegramDarkBg)
        ) {
            if (groupedByDate.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF17212B),
                            modifier = Modifier.size(68.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    tint = TelegramCheckBlue,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Files in Saved Messages",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Upload files using the + button to see them here.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedByDate.forEach { (dateHeader, filesInDate) ->
                        // 1. Centered Date Badge (e.g. "March 1", "June 16")
                        item(key = "date_$dateHeader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dateHeader,
                                    color = TelegramDateText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // 2. Group files into bubbles with item layout.
                        // Stable per-file keys: the old chunk key (firstId_size)
                        // shifted whenever a new message arrived, recomposing all
                        // bubbles and jumping scroll position.
                        val chunks = filesInDate.chunked(5)
                        items(
                            chunks,
                            key = { chunk -> chunk.joinToString("|") { "${it.telegramChatId}_${it.telegramMessageId}_${it.fileId}" } },
                            contentType = { "bubble" }
                        ) { chunkFiles ->
                            TelegramGroupedFileBubble(
                                items = chunkFiles,
                                onItemClick = onFileClick,
                                onDownloadClick = onDownloadClick,
                                onLongClick = { selectedFileMenu = it },
                                onMenuClick = { selectedFileMenu = it }
                            )
                        }
                    }
                }
            }

            // Action Bottom Sheet for Selected File.
            // Hoist sheetState: remembering it inside `let{}` recreates it on
            // every open (animation jank + state loss).
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            selectedFileMenu?.let { menuFile ->
                ModalBottomSheet(
                    onDismissRequest = { selectedFileMenu = null },
                    sheetState = sheetState,
                    containerColor = Color(0xFF1E293B)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = menuFile.fileName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatTelegramFileSizeAndExt(menuFile.fileSize, menuFile.fileName, menuFile.mimeType),
                            color = TelegramSubText,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Open / Preview Option
                        FileOptionMenuItem(
                            icon = Icons.Default.Visibility,
                            title = "Preview / Open",
                            onClick = {
                                val target = menuFile
                                selectedFileMenu = null
                                onFileClick(target)
                            }
                        )

                        // Download Option
                        FileOptionMenuItem(
                            icon = Icons.Default.Download,
                            title = "Download to Device",
                            onClick = {
                                val target = menuFile
                                selectedFileMenu = null
                                onDownloadClick(target)
                            }
                        )

                        // Share Option
                        FileOptionMenuItem(
                            icon = Icons.Default.Share,
                            title = "Share File",
                            onClick = {
                                val target = menuFile
                                selectedFileMenu = null
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = target.mimeType
                                    putExtra(Intent.EXTRA_SUBJECT, target.fileName)
                                    putExtra(Intent.EXTRA_TEXT, "Telegram file: ${target.fileName}")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share ${target.fileName}"))
                            }
                        )

                        // Delete Option (Red)
                        FileOptionMenuItem(
                            icon = Icons.Default.Delete,
                            title = "Delete from Telegram Cloud",
                            titleColor = Color(0xFFEF4444),
                            iconColor = Color(0xFFEF4444),
                            onClick = {
                                val target = menuFile
                                selectedFileMenu = null
                                fileToDelete = target
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }

            // Delete Confirmation Dialog
            fileToDelete?.let { targetFile ->
                AlertDialog(
                    onDismissRequest = { fileToDelete = null },
                    containerColor = Color(0xFF1E293B),
                    title = {
                        Text("Delete from Telegram Cloud?", color = Color.White, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "Are you sure you want to permanently delete \"${targetFile.fileName}\" from your Telegram Saved Messages?",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteFile(targetFile)
                                fileToDelete = null
                                Toast.makeText(context, "Deleted \"${targetFile.fileName}\" from Telegram", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text("Delete", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { fileToDelete = null }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FileOptionMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    titleColor: Color = Color.White,
    iconColor: Color = Color(0xFF94A3B8),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── TELEGRAM FILE BUBBLE ADAPTING THE ITEM LAYOUT ───
@Composable
fun TelegramGroupedFileBubble(
    items: List<FileEntity>,
    onItemClick: (FileEntity) -> Unit,
    onDownloadClick: (FileEntity) -> Unit,
    onLongClick: (FileEntity) -> Unit = {},
    onMenuClick: (FileEntity) -> Unit = {}
) {
    val lastItem = items.last()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(lastItem.uploadTimestamp) {
        timeFormat.format(Date(if (lastItem.uploadTimestamp > 0) lastItem.uploadTimestamp else System.currentTimeMillis()))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = TelegramBubbleBg,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp, start = 10.dp, end = 10.dp)
            ) {
                items.forEachIndexed { index, file ->
                    TelegramFileItemRow(
                        file = file,
                        onClick = { onItemClick(file) },
                        onDownload = { onDownloadClick(file) },
                        onLongClick = { onLongClick(file) },
                        onMenuClick = { onMenuClick(file) }
                    )
                    if (index < items.size - 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                // Bottom Right Timestamp and Double Blue Checkmarks
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        color = TelegramSubText,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = "Delivered",
                        tint = TelegramCheckBlue,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ─── INDIVIDUAL FILE ITEM ROW (MATCHING USER SCREENSHOTS) ───
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TelegramFileItemRow(
    file: FileEntity,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit = {},
    onMenuClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isImage = file.mimeType.startsWith("image/") || file.fileName.endsWith(".png", true) || file.fileName.endsWith(".jpg", true)
    val iconBgColor = getTelegramFileColor(file.fileName, file.mimeType)
    val formattedSizeAndExt = formatTelegramFileSizeAndExt(file.fileSize, file.fileName, file.mimeType)
    // combinedClickable expects () -> Unit; the caller's (FileEntity)->Unit was
    // already bound at the call site (onLongClick = { onLongClick(file) }).
    val onLongPress: () -> Unit = { onLongClick() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Main Clickable Area (Icon + Title/Size)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = {
                        // Single stat, single dispatcher hop. The old code did
                        // two withContext(IO) switches per tap (latency + churn).
                        scope.launch {
                            val exists = withContext(Dispatchers.IO) {
                                try {
                                    val downloadDir = FileUtils.getDownloadDir(context)
                                    File(downloadDir, file.fileName).exists()
                                } catch (_: Exception) { false }
                            }
                            if (exists) {
                                try {
                                    val downloadDir = FileUtils.getDownloadDir(context)
                                    FileUtils.openFile(context, File(downloadDir, file.fileName).absolutePath, file.fileName)
                                } catch (_: Exception) {
                                    onClick()
                                }
                            } else {
                                onClick()
                            }
                        }
                    },
                    onLongClick = onLongPress
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Circular Icon
            if (isImage) {
                // Circular Thumbnail for Image
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF374151))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    TelegramThumbnail(
                        file = file,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Circular Colored Action Pill with Down Arrow
                Surface(
                    shape = CircleShape,
                    color = iconBgColor,
                    modifier = Modifier
                        .size(46.dp)
                        .clickable(onClick = onDownload)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Title & Size + Extension Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formattedSizeAndExt,
                    color = TelegramSubText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = TelegramSubText.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─── FILE COLOR CALCULATOR BY EXTENSION ───
fun getTelegramFileColor(fileName: String, mimeType: String): Color {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext == "apk" || mimeType.contains("vnd.android.package-archive") -> Color(0xFF5BB760) // Green
        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") || mimeType.contains("zip") || mimeType.contains("compressed") -> Color(0xFFF09A37) // Orange
        ext == "pdf" || mimeType.contains("pdf") -> Color(0xFFEF5350) // Coral / Red
        ext in listOf("pem", "key", "crt", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "json", "xml", "md", "cfg", "conf", "sh", "py", "java", "kt") -> Color(0xFF42A5F5) // Blue
        mimeType.startsWith("audio/") || ext in listOf("mp3", "flac", "ogg", "wav", "m4a", "aac") -> Color(0xFFAB47BC) // Purple
        mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "avi", "mov", "webm") -> Color(0xFF26C6DA) // Cyan
        mimeType.startsWith("image/") || ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> Color(0xFF78909C) // Gray-blue
        else -> Color(0xFF42A5F5)
    }
}

// ─── FILE SIZE & EXTENSION FORMATTER (MATCHING e.g. "74.54 KiB ZIP", "53.50 MiB APK") ───
fun formatTelegramFileSizeAndExt(fileSize: Long, fileName: String, mimeType: String): String {
    val ext = fileName.substringAfterLast('.', "").uppercase().ifEmpty {
        when {
            mimeType.startsWith("image/") -> "PNG"
            mimeType.startsWith("video/") -> "MP4"
            mimeType.startsWith("audio/") -> "MP3"
            mimeType.contains("pdf") -> "PDF"
            mimeType.contains("zip") -> "ZIP"
            mimeType.contains("apk") -> "APK"
            else -> "FILE"
        }
    }
    val sizeStr = if (fileSize <= 0) "0 B" else {
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        val digitGroups = (log10(fileSize.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = fileSize / 1024.0.pow(digitGroups.toDouble())
        String.format(Locale.US, "%.2f %s", value, units[digitGroups])
    }
    return "$sizeStr $ext"
}
