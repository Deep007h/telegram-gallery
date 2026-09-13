package com.teledrive.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.app.ui.theme.GoogleDarkCard
import com.teledrive.app.ui.theme.GoogleDarkCardElevated
import com.teledrive.app.ui.theme.GoogleDarkSurface
import com.teledrive.app.ui.theme.GoogleOnDarkText
import com.teledrive.app.ui.theme.GoogleOnDarkTextMuted
import com.teledrive.app.ui.theme.GoogleOnDarkTextSubtle
import com.teledrive.app.ui.theme.GooglePrimaryAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GoogleDarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = GoogleOnDarkTextSubtle) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onQueryChange(it)
                },
                placeholder = { Text("Ask Photos… (e.g. 'Photos at Naina Devi', 'Videos')", color = GoogleOnDarkTextMuted, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = GooglePrimaryAccent) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            onQueryChange("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = GoogleOnDarkText)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GoogleDarkCard,
                    unfocusedContainerColor = GoogleDarkCard,
                    focusedBorderColor = GooglePrimaryAccent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = GoogleOnDarkText,
                    unfocusedTextColor = GoogleOnDarkText,
                    cursorColor = GooglePrimaryAccent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = GooglePrimaryAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Try searching for",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GoogleOnDarkText
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val suggestions = remember {
                listOf("People & Pets", "Recent Moments", "Screenshots", "Videos", "Documents", "Favorites")
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 240.dp)
            ) {
                items(
                    suggestions.size,
                    key = { suggestions[it] },
                    contentType = { "suggestion" }
                ) { index ->
                    val prompt = suggestions[index]
                    Surface(
                        onClick = {
                            searchQuery = prompt
                            onQueryChange(prompt)
                        },
                        color = GoogleDarkCard,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = GooglePrimaryAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = prompt,
                                color = GoogleOnDarkText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Categories & Media Types",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoogleOnDarkText
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // Chips overflow on 360dp screens (4 fixed chips > width) leaving
                // the last one clipped with no way to reach it.
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = searchQuery.isEmpty(),
                    onClick = {
                        searchQuery = ""
                        onQueryChange("")
                    },
                    label = { Text("All Media") }
                )
                FilterChip(
                    selected = searchQuery == "Photos",
                    onClick = {
                        searchQuery = "Photos"
                        onQueryChange("Photos")
                    },
                    label = { Text("Photos") }
                )
                FilterChip(
                    selected = searchQuery == "Videos",
                    onClick = {
                        searchQuery = "Videos"
                        onQueryChange("Videos")
                    },
                    label = { Text("Videos") }
                )
                FilterChip(
                    selected = searchQuery == "Documents",
                    onClick = {
                        searchQuery = "Documents"
                        onQueryChange("Documents")
                    },
                    label = { Text("Documents") }
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
