package com.teledrive.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teledrive.app.core.ota.OtaUpdateState
import com.teledrive.app.ui.components.StorageStatsCard
import com.teledrive.app.ui.components.UpdateDialog
import com.teledrive.app.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToAuth: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsState()
    val otaState by viewModel.otaUpdateState.collectAsState()
    val otaUrl by viewModel.otaUpdateUrl.collectAsState(initial = "")
    val autoCheck by viewModel.autoCheckUpdates.collectAsState(initial = true)

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var customUrlInput by remember { mutableStateOf("") }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editNameInput by remember { mutableStateOf("") }
    var showSelectChatSheet by remember { mutableStateOf(false) }
    var chatSearchQuery by remember { mutableStateOf("") }
    var chatFilter by remember { mutableStateOf("all") }
    var showDirectLinkCard by remember { mutableStateOf(false) }
    var linkChatInput by remember { mutableStateOf("") }
    var isLinkingChat by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setCustomProfilePhoto(it, context)
            Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
        }
    }

    var upToDateToastShownFor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(otaState) {
        val s = otaState
        if (s is OtaUpdateState.UpToDate) {
            val id = s.hashCode()
            if (upToDateToastShownFor != id) {
                upToDateToastShownFor = id
                Toast.makeText(context, "TeleDrive is up to date!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Modal Update Dialog
    UpdateDialog(
        state = otaState,
        currentVersion = viewModel.currentVersionName,
        onStartDownload = { info -> viewModel.startDownload(info) },
        onInstall = { apkFile -> viewModel.installUpdate(apkFile) },
        onCancelDownload = { viewModel.cancelDownload() },
        onDismiss = { viewModel.dismissUpdate() },
        onRetry = { viewModel.checkForUpdates() }
    )

    // Edit Update URL Dialog
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            containerColor = GoogleDarkSurface,
            titleContentColor = GoogleOnDarkText,
            textContentColor = GoogleOnDarkTextMuted,
            title = { Text("Update Source URL", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        text = "Enter a GitHub Releases API endpoint or direct update.json URL:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GoogleOnDarkTextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customUrlInput,
                        onValueChange = { customUrlInput = it },
                        placeholder = { Text("https://api.github.com/repos/.../releases/latest", color = GoogleOnDarkTextSubtle) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GoogleOnDarkText,
                            unfocusedTextColor = GoogleOnDarkText,
                            focusedBorderColor = GooglePrimaryAccent,
                            unfocusedBorderColor = Color(0xFF333640)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    if (customUrlInput.isNotBlank()) {
                        viewModel.setOtaUpdateUrl(customUrlInput)
                        Toast.makeText(context, "Update URL saved.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save", color = GooglePrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel", color = GoogleOnDarkTextMuted)
                }
            }
        )
    }

    // Edit Display Name Dialog
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = GoogleDarkSurface,
            titleContentColor = GoogleOnDarkText,
            textContentColor = GoogleOnDarkTextMuted,
            title = { Text("Edit Display Name", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        text = "Enter your profile display name:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GoogleOnDarkTextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNameInput,
                        onValueChange = { editNameInput = it },
                        placeholder = { Text("Your Name", color = GoogleOnDarkTextSubtle) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GoogleOnDarkText,
                            unfocusedTextColor = GoogleOnDarkText,
                            focusedBorderColor = GooglePrimaryAccent,
                            unfocusedBorderColor = Color(0xFF333640)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showEditNameDialog = false
                    if (editNameInput.isNotBlank()) {
                        viewModel.setDisplayName(editNameInput)
                        Toast.makeText(context, "Display name updated", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save", color = GooglePrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel", color = GoogleOnDarkTextMuted)
                }
            }
        )
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = GoogleDarkSurface,
            titleContentColor = GoogleOnDarkText,
            textContentColor = GoogleOnDarkTextMuted,
            title = { Text("Sign Out", fontWeight = FontWeight.SemiBold) },
            text = { Text("Are you sure you want to sign out? Local cache and temporary data will be cleared.", color = GoogleOnDarkTextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout {
                        onLogout()
                    }
                }) {
                    Text("Sign Out", color = GoogleDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = GoogleOnDarkTextMuted)
                }
            }
        )
    }

    // Select Storage Chat Bottom Sheet
    if (showSelectChatSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // Trigger real-time debounced server search whenever user types
        LaunchedEffect(chatSearchQuery) {
            if (chatSearchQuery.trim().length >= 2) {
                viewModel.searchChats(chatSearchQuery.trim())
            }
        }

        val channelsCount = remember(uiState.availableChats) {
            uiState.availableChats.count { it.typeDescription.contains("Channel", ignoreCase = true) }
        }
        val groupsCount = remember(uiState.availableChats) {
            uiState.availableChats.count { it.typeDescription.contains("Group", ignoreCase = true) || it.typeDescription.contains("Supergroup", ignoreCase = true) }
        }
        val savedCount = remember(uiState.availableChats) {
            uiState.availableChats.count { it.isSavedMessages }
        }
        val directCount = remember(uiState.availableChats) {
            uiState.availableChats.count { it.typeDescription.contains("Private", ignoreCase = true) || it.typeDescription.contains("Personal", ignoreCase = true) }
        }

        val filteredByType = remember(uiState.availableChats, chatFilter) {
            when (chatFilter) {
                "channels" -> uiState.availableChats.filter { it.typeDescription.contains("Channel", ignoreCase = true) }
                "groups" -> uiState.availableChats.filter { it.typeDescription.contains("Group", ignoreCase = true) || it.typeDescription.contains("Supergroup", ignoreCase = true) }
                "saved" -> uiState.availableChats.filter { it.isSavedMessages }
                "direct" -> uiState.availableChats.filter { it.typeDescription.contains("Private", ignoreCase = true) || it.typeDescription.contains("Personal", ignoreCase = true) }
                else -> uiState.availableChats
            }
        }

        val filteredChats = remember(filteredByType, chatSearchQuery) {
            if (chatSearchQuery.isBlank()) {
                filteredByType
            } else {
                val q = chatSearchQuery.trim()
                filteredByType.filter {
                    it.title.contains(q, ignoreCase = true) ||
                    it.typeDescription.contains(q, ignoreCase = true) ||
                    (it.username != null && it.username.contains(q, ignoreCase = true)) ||
                    it.id.toString().contains(q)
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showSelectChatSheet = false },
            sheetState = sheetState,
            containerColor = GoogleDarkSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = GoogleOnDarkTextSubtle) },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Storage Destination",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = GoogleOnDarkText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (uiState.isLoadingChats) "Loading all chats & channels..." else "${uiState.availableChats.size} chats loaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = GoogleOnDarkTextSubtle
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.loadAvailableChats() },
                            enabled = !uiState.isLoadingChats
                        ) {
                            if (uiState.isLoadingChats) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = GooglePrimaryAccent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload Chats",
                                    tint = GooglePrimaryAccent
                                )
                            }
                        }

                        IconButton(onClick = { showSelectChatSheet = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = GoogleOnDarkTextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isBotMode) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF261D0F)),
                        border = BorderStroke(1.dp, Color(0xFF6B4D16)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Bot Mode Active", fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D), fontSize = 12.sp)
                                Text(
                                    "Bots can only access chats they are added to as Admin. To load all personal account chats & Saved Messages, log in via phone number.",
                                    color = GoogleOnDarkTextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (chatFilter == "all") GooglePrimaryAccent else Color(0xFF1E2128),
                        border = BorderStroke(1.dp, if (chatFilter == "all") GooglePrimaryAccent else Color(0xFF333640)),
                        modifier = Modifier.clickable { chatFilter = "all" }
                    ) {
                        Text(
                            text = "All (${uiState.availableChats.size})",
                            fontSize = 12.sp,
                            fontWeight = if (chatFilter == "all") FontWeight.Bold else FontWeight.Medium,
                            color = if (chatFilter == "all") Color(0xFF003063) else GoogleOnDarkText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }

                    if (channelsCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (chatFilter == "channels") GooglePrimaryAccent else Color(0xFF1E2128),
                            border = BorderStroke(1.dp, if (chatFilter == "channels") GooglePrimaryAccent else Color(0xFF333640)),
                            modifier = Modifier.clickable { chatFilter = "channels" }
                        ) {
                            Text(
                                text = "Channels ($channelsCount)",
                                fontSize = 12.sp,
                                fontWeight = if (chatFilter == "channels") FontWeight.Bold else FontWeight.Medium,
                                color = if (chatFilter == "channels") Color(0xFF003063) else GoogleOnDarkText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }

                    if (groupsCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (chatFilter == "groups") GooglePrimaryAccent else Color(0xFF1E2128),
                            border = BorderStroke(1.dp, if (chatFilter == "groups") GooglePrimaryAccent else Color(0xFF333640)),
                            modifier = Modifier.clickable { chatFilter = "groups" }
                        ) {
                            Text(
                                text = "Groups ($groupsCount)",
                                fontSize = 12.sp,
                                fontWeight = if (chatFilter == "groups") FontWeight.Bold else FontWeight.Medium,
                                color = if (chatFilter == "groups") Color(0xFF003063) else GoogleOnDarkText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }

                    if (savedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (chatFilter == "saved") GooglePrimaryAccent else Color(0xFF1E2128),
                            border = BorderStroke(1.dp, if (chatFilter == "saved") GooglePrimaryAccent else Color(0xFF333640)),
                            modifier = Modifier.clickable { chatFilter = "saved" }
                        ) {
                            Text(
                                text = "Saved Messages",
                                fontSize = 12.sp,
                                fontWeight = if (chatFilter == "saved") FontWeight.Bold else FontWeight.Medium,
                                color = if (chatFilter == "saved") Color(0xFF003063) else GoogleOnDarkText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }

                    if (directCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (chatFilter == "direct") GooglePrimaryAccent else Color(0xFF1E2128),
                            border = BorderStroke(1.dp, if (chatFilter == "direct") GooglePrimaryAccent else Color(0xFF333640)),
                            modifier = Modifier.clickable { chatFilter = "direct" }
                        ) {
                            Text(
                                text = "Direct ($directCount)",
                                fontSize = 12.sp,
                                fontWeight = if (chatFilter == "direct") FontWeight.Bold else FontWeight.Medium,
                                color = if (chatFilter == "direct") Color(0xFF003063) else GoogleOnDarkText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Box
                OutlinedTextField(
                    value = chatSearchQuery,
                    onValueChange = { chatSearchQuery = it },
                    placeholder = { Text("Search by name, @username, link, or ID...", color = GoogleOnDarkTextSubtle, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = GoogleOnDarkTextSubtle
                        )
                    },
                    trailingIcon = {
                        if (chatSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { chatSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = GoogleOnDarkTextSubtle,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        chatSearchQuery = clip.trim()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste",
                                    tint = GooglePrimaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GoogleOnDarkText,
                        unfocusedTextColor = GoogleOnDarkText,
                        focusedBorderColor = GooglePrimaryAccent,
                        unfocusedBorderColor = Color(0xFF333640),
                        focusedContainerColor = GoogleDarkBackground,
                        unfocusedContainerColor = GoogleDarkBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Link Tile if Search Query looks like a link or ID
                val isSearchLinkOrId = remember(chatSearchQuery) {
                    val q = chatSearchQuery.trim()
                    q.contains("t.me/") || q.startsWith("@") || q.startsWith("-100") || q.startsWith("+")
                }

                if (isSearchLinkOrId) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2C3F)),
                        border = BorderStroke(1.dp, GooglePrimaryAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isLinkingChat = true
                                viewModel.linkTelegramChat(chatSearchQuery) { success, msg ->
                                    isLinkingChat = false
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    if (success) {
                                        chatSearchQuery = ""
                                        showSelectChatSheet = false
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = GooglePrimaryAccent)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Link: \"${chatSearchQuery.trim()}\"",
                                    color = GoogleOnDarkText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Tap to resolve and set as active storage destination",
                                    color = GoogleOnDarkTextSubtle,
                                    fontSize = 11.sp
                                )
                            }
                            if (isLinkingChat) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = GooglePrimaryAccent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = GooglePrimaryAccent)
                            }
                        }
                    }
                }

                // Collapsible manual link option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (filteredChats.isNotEmpty()) "${filteredChats.size} result${if (filteredChats.size != 1) "s" else ""}" else "",
                        fontSize = 11.sp,
                        color = GoogleOnDarkTextSubtle
                    )
                    Text(
                        text = if (showDirectLinkCard) "Hide link form" else "+ Link chat by URL/ID",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GooglePrimaryAccent,
                        modifier = Modifier
                            .clickable { showDirectLinkCard = !showDirectLinkCard }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                }

                if (showDirectLinkCard) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF16181D)),
                        border = BorderStroke(1.dp, Color(0xFF2E313A))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Link via custom link, @username, or chat ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = GoogleOnDarkTextSubtle
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = linkChatInput,
                                    onValueChange = { linkChatInput = it },
                                    placeholder = { Text("https://t.me/..., @channel, ID", color = GoogleOnDarkTextSubtle, fontSize = 12.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = GoogleOnDarkText,
                                        unfocusedTextColor = GoogleOnDarkText,
                                        focusedBorderColor = GooglePrimaryAccent,
                                        unfocusedBorderColor = Color(0xFF333640),
                                        focusedContainerColor = GoogleDarkBackground,
                                        unfocusedContainerColor = GoogleDarkBackground
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (linkChatInput.isNotBlank()) {
                                            isLinkingChat = true
                                            viewModel.linkTelegramChat(linkChatInput) { success, msg ->
                                                isLinkingChat = false
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                if (success) {
                                                    linkChatInput = ""
                                                    showSelectChatSheet = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = linkChatInput.isNotBlank() && !isLinkingChat,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GooglePrimaryAccent,
                                        contentColor = Color(0xFF003063)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    if (isLinkingChat) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF003063),
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("Link", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Chat List with weight(1f)
                if (uiState.isLoadingChats && uiState.availableChats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GooglePrimaryAccent)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Loading all Telegram chats and channels...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GoogleOnDarkTextMuted
                            )
                        }
                    }
                } else if (filteredChats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (chatSearchQuery.isBlank()) "No chats found matching filter" else "No chats matching \"$chatSearchQuery\"",
                                color = GoogleOnDarkTextMuted
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(onClick = { chatFilter = "all"; chatSearchQuery = "" }) {
                                Text("Reset filters", color = GooglePrimaryAccent)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredChats.size) { index ->
                            val chat = filteredChats[index]
                            val isSelected = chat.id == uiState.storageChatId
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFF1E2838) else Color(0xFF181B22),
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) GooglePrimaryAccent else Color(0xFF262933)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectStorageChat(chat)
                                        showSelectChatSheet = false
                                        Toast.makeText(
                                            context,
                                            "Storage set to \"${chat.title}\". Sync started.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Circle icon badge
                                    Surface(
                                        shape = CircleShape,
                                        color = when {
                                            chat.isSavedMessages -> Color(0xFF00384D)
                                            chat.typeDescription.contains("Channel", ignoreCase = true) -> Color(0xFF102847)
                                            chat.typeDescription.contains("Group", ignoreCase = true) || chat.typeDescription.contains("Supergroup", ignoreCase = true) -> Color(0xFF2A1C47)
                                            else -> Color(0xFF2E2416)
                                        },
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = when {
                                                    chat.isSavedMessages -> Icons.Default.Bookmark
                                                    chat.typeDescription.contains("Channel", ignoreCase = true) -> Icons.Default.Campaign
                                                    chat.typeDescription.contains("Group", ignoreCase = true) || chat.typeDescription.contains("Supergroup", ignoreCase = true) -> Icons.Default.Group
                                                    else -> Icons.Default.Forum
                                                },
                                                contentDescription = null,
                                                tint = when {
                                                    chat.isSavedMessages -> Color(0xFF00E5FF)
                                                    chat.typeDescription.contains("Channel", ignoreCase = true) -> Color(0xFF70B6F6)
                                                    chat.typeDescription.contains("Group", ignoreCase = true) || chat.typeDescription.contains("Supergroup", ignoreCase = true) -> Color(0xFFCFBCFF)
                                                    else -> Color(0xFFFFB74D)
                                                },
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = chat.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = GoogleOnDarkText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = when {
                                                    chat.isSavedMessages -> Color(0xFF00384D)
                                                    chat.typeDescription.contains("Channel", ignoreCase = true) -> Color(0xFF102847)
                                                    chat.typeDescription.contains("Group", ignoreCase = true) || chat.typeDescription.contains("Supergroup", ignoreCase = true) -> Color(0xFF2A1C47)
                                                    else -> Color(0xFF25272E)
                                                }
                                            ) {
                                                Text(
                                                    text = when {
                                                        chat.isSavedMessages -> "SAVED MESSAGES"
                                                        chat.typeDescription.contains("Channel", ignoreCase = true) -> "CHANNEL"
                                                        chat.typeDescription.contains("Supergroup", ignoreCase = true) -> "SUPERGROUP"
                                                        chat.typeDescription.contains("Group", ignoreCase = true) -> "GROUP"
                                                        else -> "DIRECT CHAT"
                                                    },
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        chat.isSavedMessages -> Color(0xFF00E5FF)
                                                        chat.typeDescription.contains("Channel", ignoreCase = true) -> Color(0xFF70B6F6)
                                                        chat.typeDescription.contains("Group", ignoreCase = true) || chat.typeDescription.contains("Supergroup", ignoreCase = true) -> Color(0xFFCFBCFF)
                                                        else -> GoogleOnDarkTextSubtle
                                                    },
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Text(
                                                text = if (chat.username != null) "@${chat.username}" else "ID: ${chat.id}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp,
                                                color = GoogleOnDarkTextSubtle,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Surface(
                                            shape = CircleShape,
                                            color = GooglePrimaryAccent,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color(0xFF003063),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

    Scaffold(
        containerColor = GoogleDarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = GoogleOnDarkText
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = GoogleOnDarkText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GoogleDarkBackground,
                    titleContentColor = GoogleOnDarkText,
                    navigationIconContentColor = GoogleOnDarkText
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(GoogleDarkBackground)
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Google Account / Profile Section ──
            item {
                SectionTitle("Account")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = GoogleDarkCard),
                    border = BorderStroke(1.dp, Color(0xFF25272E))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Google 4-Color Gradient Avatar Ring
                        val googleColors = remember {
                            listOf(
                                Color(0xFF4285F4),
                                Color(0xFFEA4335),
                                Color(0xFFFBBC05),
                                Color(0xFF34A853),
                                Color(0xFF4285F4)
                            )
                        }
                        val googleRingBrush = remember(googleColors) {
                            Brush.sweepGradient(googleColors)
                        }

                        Box(
                            modifier = Modifier.size(92.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = GoogleDarkSurface,
                                border = BorderStroke(3.dp, googleRingBrush),
                                modifier = Modifier
                                    .size(86.dp)
                                    .clickable { photoPickerLauncher.launch("image/*") }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val hasPhoto = remember(uiState.profilePhotoPath) {
                                        try {
                                            val f = uiState.profilePhotoPath?.let { File(it) }
                                            f != null && f.exists() && f.length() > 0
                                        } catch (_: Exception) { false }
                                    }

                                    if (hasPhoto) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(File(uiState.profilePhotoPath!!))
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = "Profile Photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(GooglePrimaryAccent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = uiState.userDisplayName.take(1).uppercase().ifEmpty { "U" },
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF003063)
                                            )
                                        }
                                    }
                                }
                            }

                            // Camera / Edit Badge
                            Surface(
                                shape = CircleShape,
                                color = GoogleDarkSurface,
                                border = BorderStroke(1.5.dp, Color(0xFF2E313A)),
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.BottomEnd)
                                    .clickable { photoPickerLauncher.launch("image/*") }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = "Change photo",
                                        tint = GooglePrimaryAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // User Display Name & Edit Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = uiState.userDisplayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = GoogleOnDarkText
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    editNameInput = uiState.userDisplayName
                                    showEditNameDialog = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit name",
                                    tint = GoogleOnDarkTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = uiState.phoneNumber.ifBlank { "Telegram Cloud Active" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = GoogleOnDarkTextMuted
                        )

                        if (uiState.username.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.username.startsWith("@")) uiState.username else "@${uiState.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GooglePrimaryAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { photoPickerLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Color(0xFF333640)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleOnDarkText)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Change Photo", fontSize = 12.sp)
                            }

                            if (uiState.profilePhotoPath != null) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.removeCustomProfilePhoto(context)
                                        Toast.makeText(context, "Photo removed", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, Color(0xFF333640)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleOnDarkTextMuted)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remove photo",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { showLogoutDialog = true },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Color(0xFF5C2424)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleDanger)
                            ) {
                                Text("Sign Out", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = onNavigateToAuth,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E2838),
                                contentColor = GooglePrimaryAccent
                            ),
                            border = BorderStroke(1.dp, GooglePrimaryAccent.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.SyncAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = GooglePrimaryAccent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.loginType == "local") "Connect Telegram Account" else "Switch / Link Telegram Account",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Storage Section ──
            item {
                SectionTitle("Cloud & Local Storage")
                StorageStatsCard(stats = uiState.storageStats)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Telegram Storage Destination Section ──
            item {
                SectionTitle("Telegram Storage Destination")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = GoogleDarkCard),
                    border = BorderStroke(1.dp, Color(0xFF25272E))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF003063),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (uiState.storageChatTitle.contains("Saved Messages", ignoreCase = true)) {
                                            Icons.Default.Bookmark
                                        } else {
                                            Icons.Default.Campaign
                                        },
                                        contentDescription = null,
                                        tint = GooglePrimaryAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uiState.storageChatTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GoogleOnDarkText
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (uiState.storageChatId != 0L) "Chat ID: ${uiState.storageChatId}" else "Default storage chat",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GoogleOnDarkTextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Choose which Telegram chat, channel, or group TeleDrive uses to store, sync, and backup your photos and files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GoogleOnDarkTextSubtle
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    showSelectChatSheet = true
                                    chatSearchQuery = ""
                                    viewModel.loadAvailableChats()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GooglePrimaryAccent)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = Color(0xFF003063),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Change Chat",
                                    color = Color(0xFF003063),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.triggerSyncNow()
                                    Toast.makeText(context, "Syncing files from Telegram...", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !uiState.isSyncing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF333640)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleOnDarkText)
                            ) {
                                if (uiState.isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = GooglePrimaryAccent
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Syncing...", fontSize = 13.sp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = GooglePrimaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sync Now", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Appearance Section ──
            item {
                SectionTitle("Appearance")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = GoogleDarkCard),
                    border = BorderStroke(1.dp, Color(0xFF25272E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val themeOptions = listOf(
                            Triple("dark", "Dark theme (AMOLED)", "Pure deepest #000000 black, maximum battery efficiency"),
                            Triple("light", "Light theme", "Clean bright Google theme"),
                            Triple("system", "System default", "Automatically matches your device settings")
                        )
                        themeOptions.forEachIndexed { index, (mode, label, desc) ->
                            val selected = uiState.themeMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setThemeMode(mode) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = GooglePrimaryAccent,
                                        unselectedColor = GoogleOnDarkTextSubtle
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) GoogleOnDarkText else GoogleOnDarkTextMuted
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GoogleOnDarkTextSubtle
                                    )
                                }
                            }
                            if (index < themeOptions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFF202228),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── OTA Updates Section ──
            item {
                SectionTitle("Updates")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = GoogleDarkCard),
                    border = BorderStroke(1.dp, Color(0xFF25272E))
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        // Check for Updates Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (otaState !is OtaUpdateState.Checking && otaState !is OtaUpdateState.Downloading) {
                                        viewModel.checkForUpdates()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = GooglePrimaryAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Check for updates",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = GoogleOnDarkText
                                    )
                                    Text(
                                        text = when (otaState) {
                                            is OtaUpdateState.Checking -> "Checking for updates…"
                                            is OtaUpdateState.UpdateAvailable -> "New version available!"
                                            is OtaUpdateState.Downloading -> "Downloading update…"
                                            is OtaUpdateState.ReadyToInstall -> "Ready to install"
                                            is OtaUpdateState.UpToDate -> "TeleDrive is up to date"
                                            is OtaUpdateState.Error -> "Check failed (tap to retry)"
                                            else -> "Current: v${viewModel.currentVersionName} (Build ${viewModel.currentVersionCode})"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (otaState) {
                                            is OtaUpdateState.UpdateAvailable -> GooglePrimaryAccent
                                            is OtaUpdateState.ReadyToInstall -> Color(0xFF4CAF50)
                                            is OtaUpdateState.Error -> GoogleDanger
                                            else -> GoogleOnDarkTextMuted
                                        }
                                    )
                                }
                            }

                            if (otaState is OtaUpdateState.Checking || otaState is OtaUpdateState.Downloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = GooglePrimaryAccent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = GoogleOnDarkTextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = Color(0xFF202228),
                            thickness = 0.5.dp
                        )

                        // Auto-check Switch Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setAutoCheckUpdates(!autoCheck) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-check on startup",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = GoogleOnDarkText
                                )
                                Text(
                                    text = "Periodically check for new releases in background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GoogleOnDarkTextSubtle
                                )
                            }
                            Switch(
                                checked = autoCheck,
                                onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GooglePrimaryAccent,
                                    checkedTrackColor = Color(0xFF004A77)
                                )
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = Color(0xFF202228),
                            thickness = 0.5.dp
                        )

                        // Update Server URL Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    customUrlInput = otaUrl
                                    showUrlDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Update Source URL",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = GoogleOnDarkText
                                )
                                Text(
                                    text = otaUrl.ifBlank { "Default GitHub Releases" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GoogleOnDarkTextSubtle,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit URL",
                                tint = GoogleOnDarkTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── About Section ──
            item {
                SectionTitle("About TeleDrive")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = GoogleDarkCard),
                    border = BorderStroke(1.dp, Color(0xFF25272E))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF003063),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = GooglePrimaryAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "TeleDrive Photos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GoogleOnDarkText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "v${viewModel.currentVersionName} (Build ${viewModel.currentVersionCode})",
                            style = MaterialTheme.typography.labelMedium,
                            color = GooglePrimaryAccent
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Unlimited Telegram cloud storage client for Android. Built with Google Photos-grade performance, AMOLED deepest black styling, and zero jitter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GoogleOnDarkTextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = GooglePrimaryAccent,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}
