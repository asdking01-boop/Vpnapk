package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FullscreenOrientation
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextSecondary

/**
 * Shared floating frosted-glass Screen Rotation selector overlay used across
 * Live TV, Movies, and TV Series & Episodes players.
 *
 * Displays two options:
 * - Landscape – Horizontal Fullscreen
 * - Portrait – Vertical Fullscreen
 */
@Composable
fun ScreenRotationSelectorOverlay(
    visible: Boolean,
    selectedOrientation: FullscreenOrientation,
    onSelectOrientation: (FullscreenOrientation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.93f, animationSpec = tween(200, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                scaleOut(targetScale = 0.93f, animationSpec = tween(160, easing = FastOutSlowInEasing)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(18.dp)
                .testTag("player_rotation_selector_overlay"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 390.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black,
                        spotColor = KrasRedBright
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xF5111522), Color(0xFA080A10))
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                KrasRedBright.copy(alpha = 0.50f),
                                Color.White.copy(alpha = 0.16f)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(KrasRed.copy(alpha = 0.22f))
                                .border(1.dp, KrasRedBright.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = null,
                                tint = KrasRedBright,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Screen Rotation",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Switch orientation without leaving fullscreen",
                                color = TextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Rotation Menu",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Option 1: Landscape – Horizontal Fullscreen
                RotationOptionRow(
                    title = FullscreenOrientation.LANDSCAPE.label,
                    subtitle = "Immersive edge-to-edge widescreen • Fit, Fill & Zoom",
                    icon = Icons.Default.ScreenLockLandscape,
                    isSelected = selectedOrientation == FullscreenOrientation.LANDSCAPE,
                    onClick = {
                        onSelectOrientation(FullscreenOrientation.LANDSCAPE)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("rotation_option_landscape")
                )

                // Option 2: Portrait – Vertical Fullscreen
                RotationOptionRow(
                    title = FullscreenOrientation.PORTRAIT.label,
                    subtitle = "Centered vertical fullscreen • Original aspect ratio & letterboxing",
                    icon = Icons.Default.ScreenLockPortrait,
                    isSelected = selectedOrientation == FullscreenOrientation.PORTRAIT,
                    onClick = {
                        onSelectOrientation(FullscreenOrientation.PORTRAIT)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("rotation_option_portrait")
                )
            }
        }
    }
}

@Composable
private fun RotationOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            KrasRed.copy(alpha = 0.28f),
                            Color(0xEB18090E),
                            Color(0xEB0E111B)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.04f)
                        )
                    )
                }
            )
            .border(
                width = if (isSelected) 1.4.dp else 1.dp,
                color = if (isSelected) KrasRedBright else Color.White.copy(alpha = 0.12f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isSelected) KrasRed.copy(alpha = 0.28f)
                        else Color.White.copy(alpha = 0.07f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) KrasRedBright.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(11.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) KrasRedBright else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = if (isSelected) Color.White.copy(alpha = 0.82f) else TextSecondary,
                    fontSize = 10.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(KrasRedBright),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Subtle floating feedback pill shown briefly when switching between
 * Landscape – Horizontal Fullscreen and Portrait – Vertical Fullscreen.
 */
@Composable
fun OrientationTransitionBadge(
    text: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.90f, animationSpec = tween(160)),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)),
        modifier = modifier
    ) {
        if (text != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xE60B0E17))
                    .border(1.dp, KrasRedBright.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .testTag("player_orientation_feedback_badge"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenRotation,
                    contentDescription = null,
                    tint = KrasRedBright,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
