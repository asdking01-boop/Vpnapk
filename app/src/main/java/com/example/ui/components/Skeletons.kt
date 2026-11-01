package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CardStyle
import com.example.ui.theme.*

fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = listOf(
        Color(0xFF181818),
        Color(0xFF282828),
        Color(0xFF181818)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 600f, translateAnim - 600f),
        end = Offset(translateAnim, translateAnim)
    )

    background(brush)
}

@Composable
fun HeroBannerSkeleton(
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
            .background(CinemaBlack)
            .testTag("hero_banner_skeleton")
    ) {
        // Shimmering background simulating backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shimmerEffect()
        )

        // Gradient overlay identical to HeroBanner
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.75f),
                        0.25f to Color.Transparent,
                        0.65f to Color.Black.copy(alpha = 0.6f),
                        0.88f to CinemaBlack.copy(alpha = 0.95f),
                        1.0f to CinemaBlack
                    )
                )
        )

        // Top Navigation Header with Kras TV Brand Logo (Pinned with statusBarsPadding)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(KrasRed)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "K",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Column {
                    Text(
                        text = "KRAS TV",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "PREMIUM CINEMA",
                        color = KrasRedBright,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Top Action Icons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(GlassBackground)
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable(onClick = onSearchClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(KrasRed)
                        .clickable(onClick = onAccountClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Account Switcher",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Bottom Skeleton Info (Title, Badges, Buttons)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Badges shimmer
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Title skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Synopsis lines skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Buttons skeleton
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
            }
        }
    }
}

@Composable
fun MediaCardSkeleton(
    cardStyle: CardStyle = CardStyle.POSTER,
    modifier: Modifier = Modifier
) {
    val (width, height, radius) = when (cardStyle) {
        CardStyle.POSTER -> Triple(135.dp, 205.dp, 12.dp)
        CardStyle.WIDE -> Triple(230.dp, 140.dp, 12.dp)
        CardStyle.COMPACT -> Triple(0.dp, 90.dp, 12.dp)
        CardStyle.BILLBOARD -> Triple(280.dp, 170.dp, 14.dp)
    }

    val boxModifier = if (cardStyle == CardStyle.COMPACT) {
        modifier
            .fillMaxWidth()
            .height(height)
    } else {
        modifier
            .width(width)
            .height(height)
    }

    Box(
        modifier = boxModifier
            .clip(RoundedCornerShape(radius))
            .background(CinemaCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(radius))
            .shimmerEffect()
    )
}

@Composable
fun MediaRowSkeleton(
    title: String,
    cardStyle: CardStyle = CardStyle.POSTER,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("media_row_skeleton_${title.lowercase().replace(" ", "_")}")
    ) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(4) {
                MediaCardSkeleton(cardStyle = cardStyle)
            }
        }
    }
}
