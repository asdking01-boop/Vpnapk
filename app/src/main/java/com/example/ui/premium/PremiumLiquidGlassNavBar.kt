package com.example.ui.premium

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LiveTvCustomIcon
import com.example.ui.components.MovieCustomIcon
import com.example.ui.components.NavScreen
import com.example.ui.components.SeriesCustomIcon
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextSecondary

/**
 * KRAS TV — Cinematic Premium 2.0 Floating Liquid Glass Navigation Bar.
 *
 * Features:
 * - Translucent glass pill dock with RenderEffect blur on API 31+ (skipped in Battery Saving Mode)
 * - Soft specular top reflections, angled refraction border, and theme-matched ambient glow
 * - Smooth animated sliding capsule indicator synced to the active destination
 * - Elegant GPU-layer icon transitions (60Hz / 120Hz smooth)
 * - Optional subtle haptic feedback
 * - Edge-to-edge WindowInsets.navigationBars safe padding and >= 48.dp touch targets
 * - Preserves all Classic destinations and conditionally includes Movie Reels when enabled
 */
@Composable
fun PremiumLiquidGlassNavBar(
    currentScreen: NavScreen,
    onScreenSelected: (NavScreen) -> Unit,
    showReelsTab: Boolean,
    isArabic: Boolean,
    modifier: Modifier = Modifier
) {
    val experience = LocalPremiumExperienceState.current
    val palette = experience.palette
    val view = LocalView.current

    val destinations = remember(showReelsTab) {
        buildList {
            add(NavScreen.HOME)
            add(NavScreen.MOVIES)
            add(NavScreen.SERIES)
            if (showReelsTab) add(NavScreen.REELS)
            add(NavScreen.LIVE_TV)
            add(NavScreen.SETTINGS)
        }
    }

    val selectedIndex = destinations.indexOf(currentScreen).coerceAtLeast(0)
    val useRealBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !experience.batterySavingActive

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("premium_liquid_glass_nav_bar"),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 580.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(34.dp))
        ) {
            val dockWidth = maxWidth
            val horizontalPad = 6.dp
            val innerWidth = (dockWidth - horizontalPad * 2).coerceAtLeast(1.dp)
            val slotWidth = innerWidth / destinations.size

            val indicatorOffset by animateDpAsState(
                targetValue = slotWidth * selectedIndex,
                animationSpec = if (experience.animationsEnabled) {
                    spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                } else {
                    snap()
                },
                label = "glass_nav_indicator_offset"
            )

            // 1. Translucent Frosted Glass Backdrop Layer (with real blur on Android 12+)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (useRealBlur) {
                            Modifier.blur(20.dp)
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                palette.glassTint.copy(alpha = if (palette.isPureOled) 0.90f else 0.80f),
                                palette.background.copy(alpha = if (palette.isPureOled) 0.95f else 0.90f)
                            )
                        )
                    )
            )

            // 2. Specular Refraction Border, Top Glass Reflection & Subtle Theme Glow
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val radius = size.height / 2f
                        val corner = CornerRadius(radius, radius)
                        val strokePx = 1.1.dp.toPx()

                        val rimBrush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.52f),
                                palette.glassBorder,
                                palette.primary.copy(alpha = 0.36f),
                                Color.White.copy(alpha = 0.24f)
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                        val topHighlight = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.62f),
                                Color.Transparent
                            )
                        )
                        val bottomSheen = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                palette.primaryBright.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                        onDrawBehind {
                            drawLine(
                                brush = topHighlight,
                                start = Offset(radius * 0.65f, strokePx),
                                end = Offset(size.width - radius * 0.65f, strokePx),
                                strokeWidth = strokePx * 1.15f
                            )
                            drawLine(
                                brush = bottomSheen,
                                start = Offset(radius * 0.8f, size.height - strokePx),
                                end = Offset(size.width - radius * 0.8f, size.height - strokePx),
                                strokeWidth = strokePx
                            )
                            drawRoundRect(
                                brush = rimBrush,
                                cornerRadius = corner,
                                style = Stroke(width = strokePx)
                            )
                        }
                    }
            )

            // 3. Sliding Translucent Active Selection Capsule
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = horizontalPad, vertical = 5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(slotWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(28.dp))
                        .drawWithCache {
                            val pillCorner = CornerRadius(size.height / 2f, size.height / 2f)
                            val fillBrush = Brush.verticalGradient(
                                colors = listOf(
                                    palette.primary.copy(alpha = 0.36f),
                                    palette.primaryDark.copy(alpha = 0.16f)
                                )
                            )
                            val borderBrush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.55f),
                                    palette.primaryBright.copy(alpha = 0.58f),
                                    Color.White.copy(alpha = 0.18f)
                                )
                            )
                            onDrawBehind {
                                drawRoundRect(brush = fillBrush, cornerRadius = pillCorner)
                                drawRoundRect(
                                    brush = borderBrush,
                                    cornerRadius = pillCorner,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }
                )
            }

            // 4. Navigation Items Row
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { screen ->
                    val isSelected = screen == currentScreen
                    val label = navLabel(screen, isArabic)
                    PremiumNavSlot(
                        screen = screen,
                        label = label,
                        isSelected = isSelected,
                        accentColor = palette.navIndicator,
                        animationsEnabled = experience.animationsEnabled,
                        onClick = {
                            if (experience.hapticsEnabled && !isSelected) {
                                view.performHapticFeedback(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        HapticFeedbackConstants.CLOCK_TICK
                                    } else {
                                        HapticFeedbackConstants.KEYBOARD_TAP
                                    }
                                )
                            }
                            onScreenSelected(screen)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumNavSlot(
    screen: NavScreen,
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color.White.copy(alpha = 0.72f),
        animationSpec = if (animationsEnabled) tween(200) else snap(),
        label = "premium_nav_icon_color"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else TextSecondary,
        animationSpec = if (animationsEnabled) tween(200) else snap(),
        label = "premium_nav_label_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.07f else 0.96f,
        animationSpec = if (animationsEnabled) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            snap()
        },
        label = "premium_nav_scale"
    )

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .testTag("nav_${screen.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (screen) {
            NavScreen.HOME -> Icon(
                imageVector = if (isSelected) Icons.Filled.Home else Icons.Outlined.Home,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            NavScreen.MOVIES -> MovieCustomIcon(
                color = iconColor,
                isSelected = isSelected,
                modifier = Modifier.size(22.dp)
            )
            NavScreen.SERIES -> SeriesCustomIcon(
                color = iconColor,
                isSelected = isSelected,
                modifier = Modifier.size(22.dp)
            )
            NavScreen.REELS -> Icon(
                imageVector = if (isSelected) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            NavScreen.LIVE_TV -> LiveTvCustomIcon(
                color = iconColor,
                isSelected = isSelected,
                modifier = Modifier.size(22.dp)
            )
            NavScreen.SETTINGS -> Icon(
                imageVector = if (isSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (isSelected) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 2.5.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
    }
}

private fun navLabel(screen: NavScreen, isArabic: Boolean): String = if (isArabic) {
    when (screen) {
        NavScreen.HOME -> "الرئيسية"
        NavScreen.MOVIES -> "الأفلام"
        NavScreen.SERIES -> "المسلسلات"
        NavScreen.REELS -> "ريلز"
        NavScreen.LIVE_TV -> "مباشر"
        NavScreen.SETTINGS -> "الإعدادات"
    }
} else {
    when (screen) {
        NavScreen.HOME -> "Home"
        NavScreen.MOVIES -> "Movies"
        NavScreen.SERIES -> "Series"
        NavScreen.REELS -> "Reels"
        NavScreen.LIVE_TV -> "Live TV"
        NavScreen.SETTINGS -> "Settings"
    }
}
