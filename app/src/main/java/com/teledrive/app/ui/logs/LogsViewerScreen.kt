package com.teledrive.app.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.core.AppLogger
import com.teledrive.app.core.LogEntry
import com.teledrive.app.core.LogLevel
import com.teledrive.app.ui.theme.GoogleDarkBackground
import com.teledrive.app.ui.theme.GoogleDarkSurface
import com.teledrive.app.ui.theme.GooglePrimaryAccent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsViewerScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by AppLogger.liveLogs.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    // Cap + debounce: 1000 log lines filtered+reversed on every keystroke drops
    // frames while typing. Take the latest 300 (what the user can actually
    // scroll) and filter that window.
    val filteredLogs = remember(logs, searchQuery, selectedLevelFilter) {
        val window = if (logs.size > 300) logs.takeLast(300) else logs
        window.filter { entry ->
            val matchesSearch = searchQuery.isEmpty() ||
                    entry.tag.contains(searchQuery, ignoreCase = true) ||
                    entry.message.contains(searchQuery, ignoreCase = true) ||
                    (entry.throwable != null && entry.throwable.contains(searchQuery, ignoreCase = true))

            val matchesLevel = selectedLevelFilter == null || entry.level == selectedLevelFilter
            matchesSearch && matchesLevel
        }.reversed()
    }

    // Counts computed once per logs change (was 4× full scans per recomposition:
    // once for ALL label + 3-4 for chips, each O(n)).
    val levelCounts = remember(logs) {
        val map = mutableMapOf<LogLevel, Int>()
        for (e in logs) map[e.level] = (map[e.level] ?: 0) + 1
        map
    }

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "App Debug Logs",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${filteredLogs.size} entries • Path: ${AppLogger.getLogFilePath()}",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 5MB readText on Main ANRs: use the Main-safe snapshot
                        // (in-memory tail) and offer the full file via share.
                        scope.launch {
                            val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                AppLogger.getAllLogsTextAsync()
                            }
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                // Clipboard has a ~1MB binder limit: truncate safely.
                                clipboard.setPrimaryClip(ClipData.newPlainText("TeleDrive Logs", text.take(400_000)))
                                Toast.makeText(context, "Full log copied to clipboard!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Copy All", tint = GooglePrimaryAccent)
                    }

                    IconButton(onClick = {
                        AppLogger.clearLogs()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GoogleDarkSurface)
            )
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search & Filter Bar
            Surface(
                color = GoogleDarkSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter logs by tag or message...", color = Color.Gray, fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF131318),
                            unfocusedContainerColor = Color(0xFF131318),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Log Level Filter Chips. Counts from a single memoized pass
                    // (was logs.count{} per chip per recomposition = 5×O(n)).
                    // Horizontally scrollable: 5 chips overflow 360dp screens.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedLevelFilter == null,
                            onClick = { selectedLevelFilter = null },
                            label = { Text("ALL (${logs.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GooglePrimaryAccent,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF131318),
                                labelColor = Color.White
                            )
                        )

                        LogLevel.values().forEach { level ->
                            val count = levelCounts[level] ?: 0
                            FilterChip(
                                selected = selectedLevelFilter == level,
                                onClick = {
                                    selectedLevelFilter = if (selectedLevelFilter == level) null else level
                                },
                                label = { Text("${level.name} ($count)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = getLogLevelColor(level),
                                    selectedLabelColor = if (level == LogLevel.DEBUG) Color.White else Color.Black,
                                    containerColor = Color(0xFF131318),
                                    labelColor = getLogLevelColor(level)
                                )
                            )
                        }
                    }
                }
            }

            // Log Items List
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs match the current filter", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Stable index-based key: timestamp+hash collided for burst
                    // logs emitted in the same ms with identical messages.
                    items(
                        filteredLogs.size,
                        key = { idx -> "${filteredLogs[idx].timestamp}_${idx}" },
                        contentType = { "log" }
                    ) { idx ->
                        LogEntryCard(entry = filteredLogs[idx], dateFormat = dateFormat)
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryCard(
    entry: LogEntry,
    dateFormat: SimpleDateFormat
) {
    val context = LocalContext.current
    val levelColor = getLogLevelColor(entry.level)
    val timeStr = remember(entry.timestamp) { dateFormat.format(Date(entry.timestamp)) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1C1C24),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val fullText = entry.toFormattedString(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US))
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Log Entry", fullText))
                Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = levelColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = entry.level.name,
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.tag,
                        color = GooglePrimaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = timeStr,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entry.message,
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            if (!entry.throwable.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF2C1515),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = entry.throwable,
                        color = Color(0xFFFCA5A5),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

fun getLogLevelColor(level: LogLevel): Color {
    return when (level) {
        LogLevel.DEBUG -> Color(0xFF94A3B8)
        LogLevel.INFO -> Color(0xFF38BDF8)
        LogLevel.WARN -> Color(0xFFF59E0B)
        LogLevel.ERROR -> Color(0xFFEF4444)
    }
}
