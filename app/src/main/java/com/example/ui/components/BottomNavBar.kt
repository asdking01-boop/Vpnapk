package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextSecondary

enum class NavScreen {
    HOME, MOVIES, SERIES, LIVE_TV, SETTINGS
}

@Composable
fun BottomNavBar(
    currentScreen: NavScreen,
    onScreenSelected: (NavScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // iOS 26 Frosted Liquid Glass Navigation Dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(32.dp))
                .drawWithCache {
                    val radiusPx = size.height / 2f
                    val corner = CornerRadius(radiusPx, radiusPx)
                    val strokePx = 1.dp.toPx()

                    val glassSurfaceBrush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xDC1A1E2C),
                            Color(0xEB0B0D14)
                        )
                    )
                    val topSpecularBrush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.48f),
                            Color.Transparent
                        )
                    )
                    val rimBorderBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.25f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )

                    onDrawBehind {
                        drawRoundRect(brush = glassSurfaceBrush, cornerRadius = corner)
                        drawLine(
                            brush = topSpecularBrush,
                            start = Offset(radiusPx * 0.7f, strokePx),
                            end = Offset(size.width - radiusPx * 0.7f, strokePx),
                            strokeWidth = strokePx * 1.1f
                        )
                        drawRoundRect(
                            brush = rimBorderBrush,
                            cornerRadius = corner,
                            style = Stroke(width = strokePx)
                        )
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                label = "Home",
                isSelected = currentScreen == NavScreen.HOME,
                onClick = { onScreenSelected(NavScreen.HOME) },
                icon = { _, color ->
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = color,
                        modifier = Modifier.size(22.dp)
                    )
                }
            )

            NavItem(
                label = "Movies",
                isSelected = currentScreen == NavScreen.MOVIES,
                onClick = { onScreenSelected(NavScreen.MOVIES) },
                icon = { isSel, color ->
                    MovieCustomIcon(color = color, isSelected = isSel)
                }
            )

            NavItem(
                label = "Series",
                isSelected = currentScreen == NavScreen.SERIES,
                onClick = { onScreenSelected(NavScreen.SERIES) },
                icon = { isSel, color ->
                    SeriesCustomIcon(color = color, isSelected = isSel)
                }
            )

            NavItem(
                label = "Live TV",
                isSelected = currentScreen == NavScreen.LIVE_TV,
                onClick = { onScreenSelected(NavScreen.LIVE_TV) },
                icon = { isSel, color ->
                    LiveTvCustomIcon(color = color, isSelected = isSel)
                }
            )

            NavItem(
                label = "Settings",
                isSelected = currentScreen == NavScreen.SETTINGS,
                onClick = { onScreenSelected(NavScreen.SETTINGS) },
                icon = { _, color ->
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = color,
                        modifier = Modifier.size(22.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (isSelected: Boolean, color: Color) -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val targetColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else TextSecondary,
        animationSpec = tween(200),
        label = "nav_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "nav_scale_$label"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(220),
        label = "nav_pill_$label"
    )

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(22.dp))
            .drawWithCache {
                val pillCorner = CornerRadius(size.height / 2f, size.height / 2f)
                val pillBrush = Brush.linearGradient(
                    colors = listOf(
                        activeColor.copy(alpha = 0.34f * pillAlpha),
                        activeColor.copy(alpha = 0.12f * pillAlpha)
                    )
                )
                val pillBorder = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.42f * pillAlpha),
                        activeColor.copy(alpha = 0.5f * pillAlpha)
                    )
                )
                onDrawBehind {
                    if (pillAlpha > 0.01f) {
                        drawRoundRect(brush = pillBrush, cornerRadius = pillCorner)
                        drawRoundRect(
                            brush = pillBorder,
                            cornerRadius = pillCorner,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("nav_${label.lowercase().replace(" ", "_")}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon(isSelected, if (isSelected) activeColor else targetColor)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = targetColor
        )
        if (isSelected) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(activeColor)
            )
        }
    }
}

// Custom Film Clapperboard / Reel Icon for Movies
@Composable
fun MovieCustomIcon(color: Color, isSelected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height

        // Film body
        drawRoundRect(
            color = color,
            topLeft = Offset(2.dp.toPx(), 7.dp.toPx()),
            size = Size(w - 4.dp.toPx(), h - 9.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = if (isSelected) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = 1.8.dp.toPx())
        )

        // Film sprocket perforations
        val sprocColor = if (isSelected) Color.Black else color
        val sprocSize = 2.dp.toPx()
        for (i in 0..3) {
            val x = 4.dp.toPx() + i * 5.dp.toPx()
            drawCircle(sprocColor, radius = sprocSize / 2, center = Offset(x, 11.dp.toPx()))
            drawCircle(sprocColor, radius = sprocSize / 2, center = Offset(x, h - 5.dp.toPx()))
        }

        // Top clapper stripe
        val clapperPath = Path().apply {
            moveTo(2.dp.toPx(), 6.dp.toPx())
            lineTo(w - 2.dp.toPx(), 4.dp.toPx())
            lineTo(w - 2.dp.toPx(), 7.dp.toPx())
            lineTo(2.dp.toPx(), 9.dp.toPx())
            close()
        }
        drawPath(clapperPath, color = color)
    }
}

// Custom Dual Screen / Staggered Episode Stack for Series
@Composable
fun SeriesCustomIcon(color: Color, isSelected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height

        // Background screen (offset top right)
        drawRoundRect(
            color = color.copy(alpha = 0.5f),
            topLeft = Offset(6.dp.toPx(), 2.dp.toPx()),
            size = Size(w - 8.dp.toPx(), h - 9.dp.toPx()),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(width = 1.4.dp.toPx())
        )

        // Foreground primary screen
        drawRoundRect(
            color = color,
            topLeft = Offset(2.dp.toPx(), 6.dp.toPx()),
            size = Size(w - 8.dp.toPx(), h - 9.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = if (isSelected) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = 1.8.dp.toPx())
        )

        // Play triangle inside foreground screen
        val playColor = if (isSelected) Color.Black else color
        val playPath = Path().apply {
            val cx = (w - 8.dp.toPx()) / 2 + 2.dp.toPx()
            val cy = (h - 9.dp.toPx()) / 2 + 6.dp.toPx()
            moveTo(cx - 2.dp.toPx(), cy - 3.5.dp.toPx())
            lineTo(cx + 3.5.dp.toPx(), cy)
            lineTo(cx - 2.dp.toPx(), cy + 3.5.dp.toPx())
            close()
        }
        drawPath(playPath, color = playColor)

        // TV Stand base
        drawLine(
            color = color,
            start = Offset(5.dp.toPx(), h - 2.dp.toPx()),
            end = Offset(13.dp.toPx(), h - 2.dp.toPx()),
            strokeWidth = 1.8.dp.toPx()
        )
    }
}

// Custom Live Broadcast Antenna / Signal Wave for Live TV
@Composable
fun LiveTvCustomIcon(color: Color, isSelected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2 + 2.dp.toPx()

        // Center transmitter beacon
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = Offset(cx, cy)
        )

        // Outer radiating broadcast waves (left and right)
        drawArc(
            color = color,
            startAngle = 210f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(cx - 7.dp.toPx(), cy - 7.dp.toPx()),
            size = Size(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(width = 1.6.dp.toPx())
        )

        drawArc(
            color = color.copy(alpha = if (isSelected) 1f else 0.6f),
            startAngle = 220f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(cx - 10.dp.toPx(), cy - 10.dp.toPx()),
            size = Size(20.dp.toPx(), 20.dp.toPx()),
            style = Stroke(width = 1.6.dp.toPx())
        )

        // Antenna tower base
        drawLine(
            color = color,
            start = Offset(cx, cy + 3.dp.toPx()),
            end = Offset(cx, h - 2.dp.toPx()),
            strokeWidth = 1.8.dp.toPx()
        )
        drawLine(
            color = color,
            start = Offset(cx - 4.dp.toPx(), h - 2.dp.toPx()),
            end = Offset(cx + 4.dp.toPx(), h - 2.dp.toPx()),
            strokeWidth = 1.8.dp.toPx()
        )
    }
}
