package com.teledrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class GooglePhotosTab(val title: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Default.PhotoLibrary),
    COLLECTIONS("Collections", Icons.Default.Folder),
    SEARCH("Search", Icons.Default.Search),
    FILES("Files", Icons.AutoMirrored.Filled.InsertDriveFile)
}

private enum class BarMode { EXPANDED, COMPACT, ICON_ONLY }

private val BarBg = Color(0xF0202124)
private val PillBg = Color(0xFF004A77)
private val TextActive = Color(0xFFD3E3FD)
private val TextInactive = Color(0xFFC4C7C5)
private val IconActive = Color(0xFFD3E3FD)
private val IconInactive = Color(0xFFC4C7C5)

@Composable
fun GooglePhotosBottomNav(
    selectedTab: GooglePhotosTab,
    onTabSelected: (GooglePhotosTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    val mode = remember(screenWidthDp) {
        when {
            screenWidthDp >= 600.dp -> BarMode.EXPANDED
            screenWidthDp >= 400.dp -> BarMode.COMPACT
            else -> BarMode.ICON_ONLY
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BarBg,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .heightIn(min = 52.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GooglePhotosTab.values().forEach { tab ->
                    TabBarItem(
                        tab = tab,
                        isSelected = selectedTab == tab,
                        mode = mode,
                        onClick = { onTabSelected(tab) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBarItem(
    tab: GooglePhotosTab,
    isSelected: Boolean,
    mode: BarMode,
    onClick: () -> Unit
) {
    val showLabel = when (mode) {
        BarMode.EXPANDED -> true
        BarMode.COMPACT -> isSelected
        BarMode.ICON_ONLY -> false
    }

    val horizontalPadding = 12.dp
    val endPadding: Dp = if (isSelected && mode == BarMode.EXPANDED) 14.dp else horizontalPadding

    val pillBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) PillBg else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "pillBgColor"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) TextActive else TextInactive,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "textColor"
    )
    val iconColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) IconActive else IconInactive,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "iconColor"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(pillBgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(
                    bounded = true,
                    color = Color(0xFFA8C7FA)
                ),
                onClick = onClick
            )
            .padding(start = horizontalPadding, end = endPadding, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = if (!showLabel) tab.title else null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = showLabel,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                    androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)) +
                    androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(100))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(if (isSelected) 6.dp else 4.dp))
                Text(
                    text = tab.title,
                    color = textColor,
                    fontSize = 13.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
