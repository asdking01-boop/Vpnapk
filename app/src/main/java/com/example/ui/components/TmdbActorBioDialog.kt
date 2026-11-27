package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.api.TmdbPersonBiography
import com.example.ui.theme.*

/**
 * Modal dialog presenting an actor's localized TMDb biography (ar-SA with automatic en-US fallback)
 * while strictly preserving the actor's original name and rendering Arabic biographies with proper RTL alignment.
 */
@Composable
fun TmdbActorBioDialog(
    biography: TmdbPersonBiography,
    isLoading: Boolean,
    isUiArabic: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var photoFailed by remember(biography.profileUrl) { mutableStateOf(false) }
    val showPhoto = biography.profileUrl.isNotBlank() && !photoFailed
    val initials = remember(biography.name) {
        biography.name
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
    }
    val bioIsArabic = remember(biography.biography) {
        TmdbMetadataMatcher.containsArabicScript(biography.biography)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 620.dp)
                .liquidGlassPanel(cornerRadius = 24.dp, accentGlow = KrasRedBright)
                .testTag("tmdb_actor_bio_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header row with portrait, original actor name, role, and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF24242E), KrasRedDark.copy(alpha = 0.5f))
                                )
                            )
                            .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showPhoto) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(biography.profileUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = biography.name,
                                contentScale = ContentScale.Crop,
                                onError = { photoFailed = true },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = initials,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        // Original actor name always preserved
                        Text(
                            text = biography.name,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("actor_bio_name")
                        )
                        if (biography.character.isNotBlank()) {
                            Text(
                                text = biography.character,
                                color = AccentGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (biography.biography.isNotBlank()) {
                            val badgeLabel = if (bioIsArabic) "العربية • ar-SA" else "English • en-US"
                            Text(
                                text = badgeLabel,
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("actor_bio_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (isUiArabic) "إغلاق" else "Close",
                            tint = Color.White
                        )
                    }
                }

                // Birthday & Place of birth metadata chips
                if (biography.birthday.isNotBlank() || biography.placeOfBirth.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (biography.birthday.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cake,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = biography.birthday,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        if (biography.placeOfBirth.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = biography.placeOfBirth,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Scrollable Biography body with automatic RTL alignment when Arabic
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    val bioDirection = if (bioIsArabic || (biography.biography.isBlank() && isUiArabic)) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides bioDirection) {
                        when {
                            biography.biography.isNotBlank() -> {
                                Text(
                                    text = biography.biography,
                                    color = TextSecondary,
                                    fontSize = 13.5.sp,
                                    lineHeight = 22.sp,
                                    textAlign = TextAlign.Start,
                                    style = TextStyle(
                                        textDirection = if (bioIsArabic) TextDirection.Rtl else TextDirection.Ltr
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("actor_bio_text")
                                )
                            }

                            isLoading -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = KrasRedBright,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (isUiArabic) "جاري تحميل السيرة الذاتية..." else "Loading biography...",
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            else -> {
                                Text(
                                    text = if (isUiArabic) "السيرة الذاتية غير متوفرة حالياً" else "Biography is not available.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .testTag("actor_bio_unavailable")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
