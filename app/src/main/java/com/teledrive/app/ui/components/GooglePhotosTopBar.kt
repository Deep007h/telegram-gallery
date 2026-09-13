package com.teledrive.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.teledrive.app.ui.theme.GoogleDarkBackground
import com.teledrive.app.ui.theme.GoogleOnDarkText
import com.teledrive.app.ui.theme.GoogleOnDarkTextSubtle
import com.teledrive.app.ui.theme.GooglePrimaryAccent
import com.teledrive.app.ui.theme.GoogleTertiaryAccent
import androidx.compose.material.icons.filled.Checklist
import java.io.File

@Composable
fun GooglePhotosTopBar(
    title: String = "Photos",
    isBackingUp: Boolean = false,
    isBackupComplete: Boolean = false,
    backupProgress: Float = 0f,
    backupStatusText: String = "",
    backupSubText: String = "",
    userDisplayName: String = "Cloud",
    profilePhotoPath: String? = null,
    onBackupClick: (() -> Unit)? = null,
    onSelectClick: (() -> Unit)? = null,
    onAddClick: () -> Unit,
    onNotificationClick: () -> Unit,
    onAvatarClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GoogleDarkBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left brand block: title when idle, or circular progress + cloud status during backup
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AnimatedContent(
                    targetState = isBackingUp || isBackupComplete,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(250)) + slideInHorizontally { -it / 3 })
                            .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutHorizontally { -it / 3 })
                    },
                    label = "TopBarBackupState"
                ) { showBackup ->
                    if (showBackup) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(enabled = onBackupClick != null) { onBackupClick?.invoke() }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // 1. Circular progress bar around the green cloud circular enclosure
                                if (isBackingUp) {
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = backupProgress,
                                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                                        label = "BackupProgress"
                                    )
                                    if (backupProgress > 0.01f) {
                                        CircularProgressIndicator(
                                            progress = animatedProgress,
                                            modifier = Modifier.size(38.dp),
                                            strokeWidth = 2.5.dp,
                                            color = Color(0xFF34A853),
                                            trackColor = Color(0xFF34A853).copy(alpha = 0.2f),
                                            strokeCap = StrokeCap.Round
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(38.dp),
                                            strokeWidth = 2.5.dp,
                                            color = Color(0xFF34A853),
                                            trackColor = Color(0xFF34A853).copy(alpha = 0.2f),
                                            strokeCap = StrokeCap.Round
                                        )
                                    }
                                } else if (isBackupComplete) {
                                    CircularProgressIndicator(
                                        progress = 1f,
                                        modifier = Modifier.size(38.dp),
                                        strokeWidth = 2.5.dp,
                                        color = Color(0xFF34A853),
                                        trackColor = Color(0xFF34A853).copy(alpha = 0.2f),
                                        strokeCap = StrokeCap.Round
                                    )
                                }

                                // 2. Green cloud circular enclosure inside
                                Surface(
                                    shape = CircleShape,
                                    color = GooglePrimaryAccent.copy(alpha = 0.16f),
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isBackupComplete) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                            contentDescription = null,
                                            tint = Color(0xFF34A853),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = backupStatusText.ifEmpty { if (isBackupComplete) "Backup complete" else "Backing up…" },
                                    color = GoogleOnDarkText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (backupSubText.isNotEmpty()) {
                                    Text(
                                        text = backupSubText,
                                        color = GoogleOnDarkTextSubtle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = title,
                            color = GoogleOnDarkText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            // Right actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSelectClick != null) {
                    IconButton(
                        onClick = onSelectClick,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Checklist,
                            contentDescription = "Select",
                            tint = GoogleOnDarkText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onAddClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = GoogleOnDarkText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = onNotificationClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = GoogleOnDarkText,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onAvatarClick)
                        .padding(2.dp)
                ) {
                    val hasPhoto = remember(profilePhotoPath) {
                        try {
                            val f = profilePhotoPath?.let { File(it) }
                            f != null && f.exists() && f.length() > 0
                        } catch (_: Exception) { false }
                    }
                    val googleColors = remember {
                        listOf(
                            Color(0xFF4285F4),
                            Color(0xFFEA4335),
                            Color(0xFFFBBC05),
                            Color(0xFF34A853),
                            Color(0xFF4285F4)
                        )
                    }
                    val googleRing = remember(googleColors) {
                        Brush.sweepGradient(googleColors)
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (hasPhoto) Color.Transparent else GooglePrimaryAccent,
                        border = BorderStroke(2.dp, googleRing),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (hasPhoto) {
                                val ctx = LocalContext.current
                                val file = remember(profilePhotoPath) { File(profilePhotoPath!!) }
                                val lastMod = remember(profilePhotoPath) {
                                    try { file.lastModified() } catch (_: Exception) { 0L }
                                }
                                val req = remember(profilePhotoPath, lastMod) {
                                    ImageRequest.Builder(ctx)
                                        .data(file)
                                        .size(128)
                                        .memoryCacheKey("avatar_${profilePhotoPath}_$lastMod")
                                        .diskCacheKey("avatar_${profilePhotoPath}_$lastMod")
                                        .crossfade(true)
                                        .allowHardware(true)
                                        .build()
                                }
                                AsyncImage(
                                    model = req,
                                    contentDescription = userDisplayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                val ctx = LocalContext.current
                                val resId = remember {
                                    ctx.resources.getIdentifier("telegram_avatar_default", "drawable", ctx.packageName)
                                }
                                if (resId != 0) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(ctx)
                                            .data(resId)
                                            .size(128)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = userDisplayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Text(
                                        text = userDisplayName.take(1).uppercase().ifEmpty { "U" },
                                        color = Color(0xFF003063),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
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
