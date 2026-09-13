package com.teledrive.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.PersonCluster
import com.teledrive.app.ui.photos.GoogleMediaTile
import com.teledrive.app.ui.theme.GoogleDarkBackground
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    person: PersonCluster,
    onPhotoClick: (FileEntity) -> Unit,
    onRenamePerson: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember(person.name) { mutableStateOf(person.name ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = person.name ?: "Person",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Name", tint = Color.White)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF191922))
            )
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GoogleDarkBackground)
        ) {
            // Person Header Hero
            Surface(
                color = Color(0xFF1F1F2B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF252533))
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val heroExists = remember(person.coverFacePath) {
                            try {
                                person.coverFacePath.isNotEmpty() && File(person.coverFacePath).exists()
                            } catch (_: Exception) { false }
                        }
                        if (heroExists) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val req = remember(person.coverFacePath) {
                                coil.request.ImageRequest.Builder(ctx)
                                    .data(File(person.coverFacePath))
                                    .size(144)
                                    .crossfade(false)
                                    .allowHardware(true)
                                    .build()
                            }
                            AsyncImage(
                                model = req,
                                contentDescription = person.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showRenameDialog = true }
                        ) {
                            Text(
                                text = person.name ?: "Add name",
                                color = if (person.name != null) Color.White else MaterialTheme.colorScheme.primary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit name",
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${person.cloudFiles.size} cloud photos & videos",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Photos Grid
            val personGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LazyVerticalGrid(
                state = personGridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 90.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(person.cloudFiles, key = { it.fileId }, contentType = { "media" }) { cloudFile ->
                    GoogleMediaTile(
                        item = cloudFile,
                        onClick = { onPhotoClick(cloudFile) }
                    )
                }
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = {
                    Text(
                        text = if (person.name.isNullOrBlank()) "Add Name" else "Edit Name",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Enter a name for this person in your Telegram Cloud library.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = renameInputText,
                            onValueChange = { renameInputText = it },
                            placeholder = { Text("Name", color = Color.White.copy(alpha = 0.4f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenamePerson(person.personId, renameInputText)
                            showRenameDialog = false
                        }
                    ) {
                        Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF22222E),
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
