package com.teledrive.app.ui.collections

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teledrive.app.data.repository.DeviceAlbum
import com.teledrive.app.data.repository.UnifiedMediaItem
import com.teledrive.app.ui.components.TelegramThumbnail
import com.teledrive.app.ui.theme.GoogleDarkBackground

@Composable
fun CollectionsScreen(
    deviceAlbums: List<DeviceAlbum> = emptyList(),
    unifiedMedia: List<UnifiedMediaItem> = emptyList(),
    peopleClusters: List<com.teledrive.app.data.repository.PersonCluster> = emptyList(),
    onOpenAlbums: () -> Unit,
    onOpenDeviceMedia: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onAlbumClick: (DeviceAlbum) -> Unit,
    onTrashClick: () -> Unit = {},
    onSmartCleanerClick: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        onRefresh()
    }

    // Prepare preview images for categories
    val albumCovers = remember(deviceAlbums) {
        deviceAlbums.map { it.coverUri }.take(4)
    }

    val onDeviceMediaUris = remember(unifiedMedia) {
        unifiedMedia.filter { it.localUri != null }.mapNotNull { it.localUri }.take(4)
    }

    val peopleMediaAvatars = remember(peopleClusters, unifiedMedia) {
        if (peopleClusters.isNotEmpty()) {
            peopleClusters.map { it.coverFacePath }.take(4)
        } else {
            val candidates = unifiedMedia.filter {
                val name = it.displayName.lowercase()
                val bucket = it.bucketName?.lowercase() ?: ""
                bucket == "camera" || bucket == "restored" || name.contains("face") || name.contains("portrait") || name.contains("img_")
            }.mapNotNull { it.localUri ?: it.cloudFile }
            if (candidates.size >= 4) candidates.take(4)
            else unifiedMedia.mapNotNull { it.localUri ?: it.cloudFile }.take(4)
        }
    }

    val documentMediaUris = remember(unifiedMedia) {
        val docs = unifiedMedia.filter {
            val name = it.displayName.lowercase()
            val bucket = it.bucketName?.lowercase() ?: ""
            bucket.contains("document") || name.contains("doc") || name.contains("pdf") ||
            name.contains("receipt") || name.contains("bill") || name.contains("id") ||
            name.contains("form") || name.contains("page") || name.contains("sheet")
        }.mapNotNull { it.localUri ?: it.cloudFile }
        if (docs.size >= 4) docs.take(4)
        else unifiedMedia.filter { (it.bucketName?.lowercase() ?: "").contains("document") || (it.bucketName?.lowercase() ?: "").contains("new folder") }
            .mapNotNull { it.localUri ?: it.cloudFile }.take(4)
    }

    val stickerMediaUris = remember(unifiedMedia) {
        val stickers = unifiedMedia.filter {
            it.mimeType.contains("png") || (it.bucketName?.lowercase() ?: "").contains("sticker") ||
            (it.bucketName?.lowercase() ?: "").contains("new folder") || (it.bucketName?.lowercase() ?: "").contains("whatsapp")
        }.mapNotNull { it.localUri ?: it.cloudFile }
        stickers.take(4)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(GoogleDarkBackground)
    ) {
        // Quick Action Chips Section (2x2)
        item(span = { GridItemSpan(2) }) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionChip(
                        icon = Icons.Default.Star,
                        title = "Favorites",
                        onClick = { onCategoryClick("Favorites") },
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionChip(
                        icon = Icons.Default.Delete,
                        title = "Trash",
                        onClick = onTrashClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionChip(
                        icon = Icons.Default.PhoneAndroid,
                        title = "Screenshots",
                        onClick = {
                            val screenshotsAlbum = deviceAlbums.find { it.name.equals("Screenshots", ignoreCase = true) }
                            if (screenshotsAlbum != null) {
                                onAlbumClick(screenshotsAlbum)
                            } else {
                                onCategoryClick("Screenshots")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionChip(
                        icon = Icons.Default.Archive,
                        title = "Archive",
                        onClick = { onCategoryClick("Archive") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Smart Cleaner Promo Card
        item(span = { GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B24)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSmartCleanerClick)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF388E3C).copy(alpha = 0.2f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = "Smart Cleaner",
                                tint = Color(0xFF81C784),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Storage Cleaner",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Clean duplicate photos, large videos & old media",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 1. Albums Card (4-quadrant)
        item {
            QuadrantCategoryCard(
                title = "Albums",
                items = albumCovers,
                onClick = onOpenAlbums
            )
        }

        // 2. On this device Card (4-quadrant)
        item {
            QuadrantCategoryCard(
                title = "On this device",
                items = onDeviceMediaUris,
                onClick = onOpenDeviceMedia
            )
        }

        // 3. People Card (4-circle avatars)
        item {
            PeopleCategoryCard(
                title = "People",
                items = peopleMediaAvatars,
                onClick = { onCategoryClick("People") }
            )
        }

        // 4. Documents Card (4-quadrant)
        item {
            QuadrantCategoryCard(
                title = "Documents",
                items = documentMediaUris,
                onClick = { onCategoryClick("Documents") }
            )
        }

        // 5. Places Card (Map Styling)
        item {
            PlacesMapCategoryCard(
                title = "Places",
                onClick = { onCategoryClick("Places") }
            )
        }

        // 6. Stickers Card (4-quadrant)
        item {
            QuadrantCategoryCard(
                title = "Stickers",
                items = stickerMediaUris,
                onClick = { onCategoryClick("Stickers") }
            )
        }
    }
}

@Composable
fun QuickActionChip(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF1E1E26),
        modifier = modifier
            .height(56.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun QuadrantCategoryCard(
    title: String,
    items: List<Any>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E1E26))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    QuadrantCell(item = items.getOrNull(0), modifier = Modifier.weight(1f).fillMaxHeight())
                    Spacer(modifier = Modifier.width(1.5.dp))
                    QuadrantCell(item = items.getOrNull(1), modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(modifier = Modifier.height(1.5.dp))
                Row(modifier = Modifier.weight(1f)) {
                    QuadrantCell(item = items.getOrNull(2), modifier = Modifier.weight(1f).fillMaxHeight())
                    Spacer(modifier = Modifier.width(1.5.dp))
                    QuadrantCell(item = items.getOrNull(3), modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuadrantCell(
    item: Any?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color(0xFF282834)),
        contentAlignment = Alignment.Center
    ) {
        if (item is Uri) {
            AsyncImage(
                model = item,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item is com.teledrive.app.data.db.entity.FileEntity) {
            TelegramThumbnail(
                file = item,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun PeopleCategoryCard(
    title: String,
    items: List<Any>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E1E26))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    PeopleAvatarCell(item = items.getOrNull(0), modifier = Modifier.weight(1f).fillMaxHeight())
                    Spacer(modifier = Modifier.width(6.dp))
                    PeopleAvatarCell(item = items.getOrNull(1), modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.weight(1f)) {
                    PeopleAvatarCell(item = items.getOrNull(2), modifier = Modifier.weight(1f).fillMaxHeight())
                    Spacer(modifier = Modifier.width(6.dp))
                    PeopleAvatarCell(item = items.getOrNull(3), modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PeopleAvatarCell(
    item: Any?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF282834)),
        contentAlignment = Alignment.Center
    ) {
        if (item is Uri) {
            AsyncImage(
                model = item,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item is String) {
            AsyncImage(
                model = java.io.File(item),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item is java.io.File) {
            AsyncImage(
                model = item,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (item is com.teledrive.app.data.db.entity.FileEntity) {
            TelegramThumbnail(
                file = item,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun PlacesMapCategoryCard(
    title: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF132034))
        ) {
            // Stylized vector map background
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "105", color = Color(0xFFFFD54F), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(text = "205", color = Color(0xFFFFD54F), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Shimla", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(2.dp)
                ) {
                    Surface(shape = CircleShape, color = Color(0xFF2E384D), modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Chandigarh", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Solan", color = Color(0xFFFFD54F), fontSize = 9.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
