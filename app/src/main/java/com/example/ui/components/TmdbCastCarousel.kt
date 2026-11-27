package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.api.TmdbCastMember
import com.example.ui.theme.*

/**
 * Displays verified cast members with circular actor portrait photos (and graceful initials
 * avatars when an actor has no portrait on TMDb) plus Director attribution.
 * Tapping an actor card opens their localized TMDb biography when [onActorClick] is provided.
 */
@Composable
fun TmdbCastCarouselSection(
    castMembers: List<TmdbCastMember>,
    fallbackCastCsv: String,
    director: String,
    isArabic: Boolean,
    onActorClick: ((TmdbCastMember) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val effectiveMembers = remember(castMembers, fallbackCastCsv) {
        if (castMembers.isNotEmpty()) {
            castMembers
        } else {
            fallbackCastCsv
                .split(",", "،")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(10)
                .mapIndexed { idx, name -> TmdbCastMember(id = -(idx + 1), name = name) }
        }
    }

    if (director.isBlank() && effectiveMembers.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("details_cast_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (director.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isArabic) "الإخراج:" else "Director:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = director,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("details_director_text")
                )
            }
        }

        if (effectiveMembers.isNotEmpty()) {
            Text(
                text = if (isArabic) "طاقم التمثيل" else "Cast",
                color = Color.White,
                fontSize = 15.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details_cast_carousel")
            ) {
                items(effectiveMembers, key = { "${it.id}_${it.name}" }) { member ->
                    CastActorCard(
                        member = member,
                        onClick = if (onActorClick != null && member.id > 0) {
                            { onActorClick(member) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CastActorCard(
    member: TmdbCastMember,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var photoFailed by remember(member.profileUrl) { mutableStateOf(false) }
    val showPhoto = member.profileUrl.isNotBlank() && !photoFailed
    val initials = remember(member.name) {
        member.name
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
    }

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .testTag("cast_actor_card_${member.id}")
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF24242E),
                            KrasRedDark.copy(alpha = 0.45f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (showPhoto) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(member.profileUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    onError = { photoFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = initials,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 16.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = member.name,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp
        )

        if (member.character.isNotBlank()) {
            Text(
                text = member.character,
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
