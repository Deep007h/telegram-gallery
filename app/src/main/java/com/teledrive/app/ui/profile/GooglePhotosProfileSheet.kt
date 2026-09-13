package com.teledrive.app.ui.profile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teledrive.app.ui.theme.GoogleDarkCard
import com.teledrive.app.ui.theme.GoogleDarkCardElevated
import com.teledrive.app.ui.theme.GoogleDarkSurface
import com.teledrive.app.ui.theme.GoogleOnDarkText
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import com.teledrive.app.ui.theme.GoogleOnDarkTextSubtle
import com.teledrive.app.ui.theme.GooglePrimaryAccent
import com.teledrive.app.ui.theme.GoogleTertiaryAccent
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

enum class ProfileDialogType {
    STORAGE_INFO,
    FREE_SPACE,
    AI_PLAN,
    PRIVACY,
    HELP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePhotosProfileSheet(
    userDisplayName: String,
    phoneNumber: String,
    profilePhotoPath: String? = null,
    totalCount: Int,
    syncedCount: Int,
    totalSizeBytes: Long,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onTriggerBackup: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onCheckUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<ProfileDialogType?>(null) }

    val rawName = userDisplayName.ifBlank { "Telegram User" }
    val initialLetter = rawName.take(1).uppercase().ifEmpty { "T" }
    val phoneText = phoneNumber.ifEmpty { "Connected via Telegram Cloud" }

    val photoFile = remember(profilePhotoPath) {
        profilePhotoPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
    }

    val isAllSynced = syncedCount >= totalCount && totalCount > 0
    val formattedSize = formatBytes(totalSizeBytes)
    val progressVal = if (totalCount > 0) (syncedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f

    val googleColors = remember {
        listOf(
            Color(0xFF4285F4), // Google Blue
            Color(0xFFEA4335), // Google Red
            Color(0xFFFBBC05), // Google Yellow
            Color(0xFF34A853), // Google Green
            Color(0xFF4285F4)  // Google Blue
        )
    }
    val googleRingBrush = remember(googleColors) {
        androidx.compose.ui.graphics.Brush.sweepGradient(googleColors)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GoogleDarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = GoogleOnDarkTextSubtle) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header: Close Button & Google Photos Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = GoogleOnDarkText
                    )
                }

                Text(
                    text = "TeleDrive Photos",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoogleOnDarkText
                )

                Spacer(modifier = Modifier.size(36.dp))
            }

            val scope = rememberCoroutineScope()
            val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    val app = com.teledrive.app.TeleDriveApplication.instance
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            context.filesDir.listFiles { f -> f.name.startsWith("custom_avatar") }?.forEach { it.delete() }
                            val file = File(context.filesDir, "custom_avatar_${System.currentTimeMillis()}.jpg")
                            context.contentResolver.openInputStream(it)?.use { input ->
                                java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
                            }
                            if (file.exists() && file.length() > 0) {
                                app.preferences.setCustomProfilePhotoPath(file.absolutePath)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            // Google 4-Color Gradient Avatar Ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(86.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = GoogleDarkSurface,
                    border = androidx.compose.foundation.BorderStroke(3.dp, googleRingBrush),
                    modifier = Modifier
                        .size(82.dp)
                        .clickable { photoPickerLauncher.launch("image/*") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (photoFile != null) {
                            val lastMod = remember(photoFile) {
                                try { photoFile.lastModified() } catch (_: Exception) { 0L }
                            }
                            val req = remember(photoFile, lastMod) {
                                ImageRequest.Builder(context)
                                    .data(photoFile)
                                    .size(256)
                                    .memoryCacheKey("profile_sheet_avatar_${photoFile.absolutePath}_$lastMod")
                                    .diskCacheKey("profile_sheet_avatar_${photoFile.absolutePath}_$lastMod")
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = req,
                                contentDescription = rawName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            val resId = remember {
                                context.resources.getIdentifier("telegram_avatar_default", "drawable", context.packageName)
                            }
                            if (resId != 0) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(resId)
                                        .size(256)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = rawName,
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
                                        text = initialLetter,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF003063)
                                    )
                                }
                            }
                        }
                    }
                }

                // Camera Badge
                Surface(
                    shape = CircleShape,
                    color = GoogleDarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF2E313A)),
                    modifier = Modifier
                        .size(26.dp)
                        .align(Alignment.BottomEnd)
                        .clickable { photoPickerLauncher.launch("image/*") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Change photo",
                            tint = GooglePrimaryAccent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = rawName,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoogleOnDarkText
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = phoneText,
                fontSize = 13.sp,
                color = GoogleOnDarkTextMuted
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org"))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Opening Telegram Account Manager…", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleOnDarkText),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
            ) {
                Text(
                    text = "Manage your Telegram Account",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Switch account / Sign out Card
            Surface(
                onClick = {
                    onDismiss()
                    onLogout()
                },
                shape = RoundedCornerShape(20.dp),
                color = GoogleDarkCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Switch account / Sign out",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = GoogleOnDarkText
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (photoFile != null) Color.Transparent else GooglePrimaryAccent,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (photoFile != null) {
                                    AsyncImage(
                                        model = photoFile,
                                        contentDescription = rawName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Text(initialLetter, color = Color(0xFF003063), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Switch",
                            tint = GoogleOnDarkText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Google Photos Style Backup & Storage Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = GoogleDarkCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isAllSynced) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                contentDescription = "Storage",
                                tint = if (isAllSynced) Color(0xFF34A853) else GooglePrimaryAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isAllSynced) "Backup complete" else if (totalCount == 0) "Telegram Cloud" else "Backing up media…",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GoogleOnDarkText
                                )
                                Text(
                                    text = if (totalCount == 0) "Unlimited Storage" else "$syncedCount of $totalCount items synced",
                                    fontSize = 12.sp,
                                    color = GoogleOnDarkTextMuted
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GooglePrimaryAccent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Unlimited",
                                color = GooglePrimaryAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LinearProgressIndicator(
                        progress = { if (totalCount > 0) progressVal else 0.05f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isAllSynced) Color(0xFF34A853) else GooglePrimaryAccent,
                        trackColor = GoogleDarkCardElevated
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (totalCount > 0) "$formattedSize of Unlimited Telegram Cloud used" else "No photos or videos backed up yet",
                        fontSize = 12.sp,
                        color = GoogleOnDarkTextMuted
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { activeDialog = ProfileDialogType.STORAGE_INFO }) {
                            Text("Details", color = GooglePrimaryAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { activeDialog = ProfileDialogType.FREE_SPACE }) {
                            Text("Clean up space", color = GooglePrimaryAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "More from Photos",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoogleOnDarkTextSubtle,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(start = 6.dp, bottom = 10.dp)
            )

            ProfileOptionRow(
                icon = Icons.Default.CloudUpload,
                title = "Backup",
                subtitle = if (syncedCount >= totalCount && totalCount > 0) "Backup complete • All items synced" else "Backing up • $syncedCount/$totalCount synced",
                onClick = {
                    onDismiss()
                    onTriggerBackup()
                    Toast.makeText(context, "Scanning local media for backup…", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.AutoAwesome,
                title = "Get a Telegram AI plan",
                subtitle = "Unlimited cloud & AI features",
                onClick = { activeDialog = ProfileDialogType.AI_PLAN }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.DeleteSweep,
                title = "Free up space on this device",
                subtitle = "Safely remove backed up media",
                onClick = { activeDialog = ProfileDialogType.FREE_SPACE }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.Shield,
                title = "Your data in Telegram Gallery",
                subtitle = "Encrypted private channel storage",
                onClick = { activeDialog = ProfileDialogType.PRIVACY }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.Settings,
                title = "Gallery settings",
                subtitle = "Backup, network & channel controls",
                onClick = {
                    onDismiss()
                    onOpenSettings()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.SystemUpdate,
                title = "Check for updates",
                subtitle = "Over-the-air app updates",
                onClick = {
                    onDismiss()
                    onCheckUpdates()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                title = "Help & feedback",
                subtitle = "FAQs & Telegram storage guide",
                onClick = { activeDialog = ProfileDialogType.HELP }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProfileOptionRow(
                icon = Icons.Default.BugReport,
                title = "App debug logs",
                subtitle = "View live logs, TDLib events & copy path",
                onClick = {
                    onDismiss()
                    onOpenLogs()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Privacy Policy",
                    fontSize = 11.sp,
                    color = GoogleOnDarkTextSubtle,
                    modifier = Modifier.clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://telegram.org/privacy")))
                        } catch (_: Exception) {}
                    }
                )
                Text(
                    text = " • ",
                    fontSize = 11.sp,
                    color = GoogleOnDarkTextSubtle
                )
                Text(
                    text = "Terms of Service",
                    fontSize = 11.sp,
                    color = GoogleOnDarkTextSubtle,
                    modifier = Modifier.clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://telegram.org/tos")))
                        } catch (_: Exception) {}
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "TeleDrive v1.0 • Powered by Telegram MTProto",
                fontSize = 11.sp,
                color = GoogleOnDarkTextSubtle.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    activeDialog?.let { dialogType ->
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = GoogleDarkCard,
            title = {
                Text(
                    text = when (dialogType) {
                        ProfileDialogType.STORAGE_INFO -> "Unlimited Telegram Storage"
                        ProfileDialogType.FREE_SPACE -> "Free Up Device Space"
                        ProfileDialogType.AI_PLAN -> "Telegram AI Plan"
                        ProfileDialogType.PRIVACY -> "Data Security & Privacy"
                        ProfileDialogType.HELP -> "Help & Support"
                    },
                    color = GoogleOnDarkText,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = when (dialogType) {
                        ProfileDialogType.STORAGE_INFO ->
                            "Your photos and videos are backed up to Telegram cloud storage. Telegram provides unlimited storage for all uploaded media items without compression limits."
                        ProfileDialogType.FREE_SPACE ->
                            "$syncedCount items have been safely backed up to Telegram Cloud. You can safely remove original local copies to free up local disk space."
                        ProfileDialogType.AI_PLAN ->
                            "Telegram AI features include smart search, automatic album organization, photo enhancement, and high-speed multi-part uploads."
                        ProfileDialogType.PRIVACY ->
                            "All backed-up media is stored in your private Telegram channel or Saved Messages. Files are encrypted with end-to-end envelope keys."
                        ProfileDialogType.HELP ->
                            "Telegram Gallery / TeleDrive v1.0\n• Cloud Backend: Telegram MTProto\n• Support: Open Settings for channel and network controls."
                    },
                    color = GoogleOnDarkTextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text("OK", color = GooglePrimaryAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
fun ProfileOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = GoogleDarkCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = GoogleOnDarkText,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = GoogleOnDarkText
                )
                subtitle?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = GoogleOnDarkTextMuted
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}
