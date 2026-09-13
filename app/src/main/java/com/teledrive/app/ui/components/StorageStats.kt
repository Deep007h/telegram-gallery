package com.teledrive.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.teledrive.app.core.toFormattedSize
import com.teledrive.app.data.repository.StorageStats

@Composable
fun StorageStatsCard(
    stats: StorageStats,
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        Pair("Images", Color(0xFF4285F4)),
        Pair("Videos", Color(0xFFEA4335)),
        Pair("Audio", Color(0xFFFBBC05)),
        Pair("Documents", Color(0xFF34A853)),
        Pair("Other", Color(0xFF80868B))
    )

    val counts = listOf(
        Pair("Images", stats.imageCount),
        Pair("Videos", stats.videoCount),
        Pair("Audio", stats.audioCount),
        Pair("Documents", stats.documentCount),
        Pair("Other", stats.otherCount)
    )

    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = com.teledrive.app.ui.theme.GoogleDarkCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "${stats.totalFiles} files · ${stats.totalSize.toFormattedSize()}",
                style = MaterialTheme.typography.titleMedium,
                color = com.teledrive.app.ui.theme.GoogleOnDarkText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
            ) {
                var currentX = 0f
                val canvasWidth = size.width
                val canvasHeight = size.height

                if (stats.totalFiles > 0) {
                    counts.forEachIndexed { index, (_, count) ->
                        val weight = count.toFloat() / stats.totalFiles.toFloat()
                        val sectionWidth = canvasWidth * weight
                        val sectionColor = colors[index].second

                        if (sectionWidth > 0) {
                            drawLine(
                                color = sectionColor,
                                start = Offset(x = currentX, y = canvasHeight / 2),
                                end = Offset(x = currentX + sectionWidth, y = canvasHeight / 2),
                                strokeWidth = canvasHeight,
                                cap = StrokeCap.Butt
                            )
                            currentX += sectionWidth
                        }
                    }
                } else {
                    drawLine(
                        color = Color.LightGray,
                        start = Offset(x = 0f, y = canvasHeight / 2),
                        end = Offset(x = canvasWidth, y = canvasHeight / 2),
                        strokeWidth = canvasHeight,
                        cap = StrokeCap.Butt
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column {
                counts.forEachIndexed { index, (label, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colors[index].second)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "$count files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
