package com.teledrive.app.ui.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.repository.PersonCluster
import com.teledrive.app.ui.theme.GoogleDarkBackground
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    peopleClusters: List<PersonCluster>,
    isScanning: Boolean,
    onPersonClick: (PersonCluster) -> Unit,
    onRenamePerson: (String, String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    var renamingPerson by remember { mutableStateOf<PersonCluster?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "People & Pets",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isScanning) "Scanning faces in cloud media..." else "${peopleClusters.size} people identified",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF191922))
            )
        },
        containerColor = GoogleDarkBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GoogleDarkBackground)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF2B2B38)
                    )
                }

                if (peopleClusters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF22222E),
                                modifier = Modifier.size(96.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Face,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Finding People in Cloud Media",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Faces from your Telegram Cloud photos and videos are automatically identified and grouped together.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onRescan,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Face Scan")
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(peopleClusters, key = { it.personId }, contentType = { "person" }) { person ->
                            PersonGridItem(
                                person = person,
                                onClick = { onPersonClick(person) },
                                onEditName = {
                                    renamingPerson = person
                                    renameInputText = person.name ?: ""
                                }
                            )
                        }
                    }
                }
            }

            // Rename Person Dialog
            if (renamingPerson != null) {
                val targetPerson = renamingPerson!!
                AlertDialog(
                    onDismissRequest = { renamingPerson = null },
                    title = {
                        Text(
                            text = if (targetPerson.name.isNullOrBlank()) "Add Name" else "Edit Name",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "Who is this person? This name will be used across Telegram cloud searches and albums.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = renameInputText,
                                onValueChange = { renameInputText = it },
                                placeholder = { Text("Enter name (e.g. Harshdeep, Dad, Mom)", color = Color.White.copy(alpha = 0.4f)) },
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
                                onRenamePerson(targetPerson.personId, renameInputText)
                                renamingPerson = null
                            }
                        ) {
                            Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { renamingPerson = null }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                        }
                    },
                    containerColor = Color(0xFF22222E),
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
    }
}

@Composable
fun PersonGridItem(
    person: PersonCluster,
    onClick: () -> Unit,
    onEditName: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // Circular Face Avatar. No shadow(): per-cell drop shadows force an
        // offscreen render pass per avatar and stutter the 3-col grid.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color(0xFF252533))
                .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val avatarExists = remember(person.coverFacePath) {
                try {
                    person.coverFacePath.isNotEmpty() && File(person.coverFacePath).exists() && File(person.coverFacePath).length() > 0
                } catch (_: Exception) { false }
            }
            if (avatarExists) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val req = remember(person.coverFacePath) {
                    coil.request.ImageRequest.Builder(ctx)
                        .data(File(person.coverFacePath))
                        .size(192)
                        .memoryCacheKey("person_${person.personId}")
                        .crossfade(false)
                        .allowHardware(true)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = person.name ?: "Identified Person",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Name & Edit
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text = person.name ?: "Add name",
                color = if (person.name != null) Color.White else MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = if (person.name != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )

            IconButton(
                onClick = onEditName,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit name",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Count of photos / videos
        Text(
            text = "${person.faceCount} ${if (person.faceCount == 1) "photo" else "photos"}",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}
