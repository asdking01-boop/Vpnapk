package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.data.api.TmdbMetadataMatcher
import com.example.ui.theme.KrasRedBright
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

/**
 * Reusable Overview section for Movie and TV Series Details screens.
 *
 * Behavior:
 * - Displays the verified real plot synopsis with a Read More / Read Less toggle for long descriptions.
 * - Automatically applies proper RTL layout & text alignment whenever the synopsis is in Arabic.
 * - Shows a subtle loading indicator while fetching details if no verified plot is available yet.
 * - Never displays invented or generic placeholder text; shows "Story synopsis not available"
 *   (Arabic: "ملخص القصة غير متوفر") when no verified synopsis exists.
 */
@Composable
fun DetailsOverviewSection(
    itemKey: String,
    mediaTitle: String,
    sectionTitle: String,
    plot: String,
    isLoading: Boolean,
    isArabic: Boolean,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 4
) {
    val verifiedPlot = remember(plot, mediaTitle) {
        TmdbMetadataMatcher.sanitizeSynopsis(plot, mediaTitle)
    }
    val plotIsArabic = remember(verifiedPlot) {
        TmdbMetadataMatcher.containsArabicScript(verifiedPlot)
    }

    var isExpanded by remember(itemKey) { mutableStateOf(false) }
    var hasOverflow by remember(itemKey, verifiedPlot) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Text(
            text = sectionTitle,
            color = Color.White,
            fontSize = 16.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        when {
            verifiedPlot.isNotEmpty() -> {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (plotIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = verifiedPlot,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            style = TextStyle(
                                textDirection = if (plotIsArabic) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            onTextLayout = { layoutResult ->
                                if (!isExpanded) {
                                    hasOverflow = layoutResult.hasVisualOverflow || layoutResult.lineCount > collapsedMaxLines
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overview_text")
                        )

                        val showReadMoreToggle = hasOverflow || isExpanded || verifiedPlot.length > 260
                        if (showReadMoreToggle) {
                            Row(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(vertical = 4.dp)
                                    .testTag("overview_read_more_button"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isExpanded) {
                                        if (isArabic || plotIsArabic) "عرض أقل" else "Read Less"
                                    } else {
                                        if (isArabic || plotIsArabic) "عرض المزيد" else "Read More"
                                    },
                                    color = KrasRedBright,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) {
                                        if (isArabic || plotIsArabic) "عرض أقل" else "Read Less"
                                    } else {
                                        if (isArabic || plotIsArabic) "عرض المزيد" else "Read More"
                                    },
                                    tint = KrasRedBright,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            isLoading -> {
                Row(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .testTag("overview_loading_indicator"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = KrasRedBright,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isArabic) "جاري تحميل ملخص القصة..." else "Loading story synopsis...",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }

            else -> {
                Text(
                    text = if (isArabic) "ملخص القصة غير متوفر" else "Story synopsis not available",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 20.sp,
                    modifier = Modifier.testTag("overview_unavailable_text")
                )
            }
        }
    }
}
