package com.teledrive.app.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teledrive.app.data.repository.DeviceAlbum
import com.teledrive.app.ui.theme.GoogleDarkBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAlbumsGridScreen(
    title: String = "Albums on this device",
    albums: List<DeviceAlbum>,
    onAlbumClick: (DeviceAlbum) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${albums.size} media folders found",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GoogleDarkBackground
                )
            )
        },
        containerColor = GoogleDarkBackground
    ) { paddingValues ->
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        val defaultFling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
        val halfSpeedFling = remember(defaultFling) {
            object : androidx.compose.foundation.gestures.FlingBehavior {
                override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
                    return with(defaultFling) {
                        performFling(initialVelocity * 0.65f)
                    }
                }
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            flingBehavior = halfSpeedFling,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(albums, key = { it.bucketId + "_" + it.name }, contentType = { "album" }) { album ->
                DeviceAlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album) }
                )
            }
        }
    }
}

@Composable
fun DeviceAlbumCard(
    album: DeviceAlbum,
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
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF262632))
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val req = remember(album.coverUri) {
                coil.request.ImageRequest.Builder(context)
                    .data(album.coverUri)
                    .size(480)
                    .memoryCacheKey("album_${album.bucketId}")
                    .crossfade(false)
                    .allowHardware(true)
                    .build()
            }
            AsyncImage(
                model = req,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (album.videoCount > 0 && album.photoCount == 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Videos",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = album.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val countText = if (album.videoCount > 0 && album.photoCount == 0) {
            "${album.videoCount} videos"
        } else if (album.videoCount > 0) {
            "${album.itemCount} items (${album.photoCount} photos, ${album.videoCount} videos)"
        } else {
            "${album.itemCount} items"
        }

        Text(
            text = countText,
            color = Color.Gray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
