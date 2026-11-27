package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TmdbMetadataLanguage
import com.example.ui.theme.*

/**
 * Provides the active resolved TMDb metadata localization preference (default true = Arabic ar-SA)
 * across Home, Movies, Series, Search, Movie Reels, and content details without prop-drilling.
 */
val LocalPreferArabicMetadata = compositionLocalOf { true }

/**
 * Settings -> TMDb -> Metadata Language selector card.
 *
 * Allows choosing between:
 * - العربية (Default, requests ar-SA with automatic English fallback)
 * - English (requests en-US)
 * - Follow App Language (tracks the app UI language)
 *
 * Switching metadata language updates displayed metadata from separate ar-SA / en-US local caches
 * and refreshes on demand without triggering a full IPTV synchronization.
 */
@Composable
fun TmdbMetadataLanguageCard(
    selectedLanguage: TmdbMetadataLanguage,
    appLanguageIsArabic: Boolean,
    isUiArabic: Boolean,
    onSelectLanguage: (TmdbMetadataLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val layoutDirection = if (isUiArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    val effectiveFollowTag = if (appLanguageIsArabic) "العربية (ar-SA)" else "English (en-US)"

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 20.dp, accentGlow = AccentGold)
                .testTag("tmdb_metadata_language_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentGold.copy(alpha = 0.16f))
                            .border(1.dp, AccentGold.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = if (isUiArabic) "لغة بيانات TMDb" else "TMDb Metadata Language",
                            tint = AccentGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isUiArabic) "لغة بيانات TMDb" else "Metadata Language",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isUiArabic) {
                                "الأوصاف، التصنيفات، المواسم والسير الذاتية • تبقى العناوين وأسماء الممثلين أصلية"
                            } else {
                                "Synopses, genres, seasons & bios • Original titles & actor names preserved"
                            },
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // Option 1: العربية — Default
                MetadataLanguageOptionRow(
                    title = "العربية",
                    badgeText = if (isUiArabic) "الافتراضي" else "Default",
                    subtitle = if (isUiArabic) {
                        "طلب ar-SA مع الرجوع التلقائي للإنجليزية عند عدم توفر الوصف العربي"
                    } else {
                        "Requests ar-SA • Automatic English fallback when Arabic is unavailable"
                    },
                    selected = selectedLanguage == TmdbMetadataLanguage.ARABIC,
                    tag = "tmdb_meta_lang_arabic",
                    onClick = { onSelectLanguage(TmdbMetadataLanguage.ARABIC) }
                )

                // Option 2: English
                MetadataLanguageOptionRow(
                    title = "English",
                    badgeText = "en-US",
                    subtitle = if (isUiArabic) {
                        "عرض أوصاف الأفلام والمسلسلات والتصنيفات والسير الذاتية بالإنجليزية"
                    } else {
                        "Display movie, series, season & episode synopses, genres and bios in English"
                    },
                    selected = selectedLanguage == TmdbMetadataLanguage.ENGLISH,
                    tag = "tmdb_meta_lang_english",
                    onClick = { onSelectLanguage(TmdbMetadataLanguage.ENGLISH) }
                )

                // Option 3: Follow App Language
                MetadataLanguageOptionRow(
                    title = if (isUiArabic) "مطابقة لغة التطبيق" else "Follow App Language",
                    badgeText = null,
                    subtitle = if (isUiArabic) {
                        "الحالية: $effectiveFollowTag"
                    } else {
                        "Currently: $effectiveFollowTag"
                    },
                    selected = selectedLanguage == TmdbMetadataLanguage.FOLLOW_APP,
                    tag = "tmdb_meta_lang_follow_app",
                    onClick = { onSelectLanguage(TmdbMetadataLanguage.FOLLOW_APP) }
                )
            }
        }
    }
}

@Composable
private fun MetadataLanguageOptionRow(
    title: String,
    badgeText: String?,
    subtitle: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val borderColor = if (selected) AccentGold.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.12f)
    val bgColor = if (selected) AccentGold.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = title,
            tint = if (selected) AccentGold else TextMuted,
            modifier = Modifier.size(20.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!badgeText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) AccentGold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                            .border(
                                1.dp,
                                if (selected) AccentGold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.15f),
                                CircleShape
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = if (selected) AccentGold else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
