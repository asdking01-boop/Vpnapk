package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.api.TmdbSearchCandidate
import com.example.ui.theme.*
import java.util.Locale

/**
 * Interactive Manual TMDb Match Picker Dialog.
 * Allows users to search TMDb (English, Arabic, or original title) and bind the exact
 * movie or TV series when automatic matching fails or picks an incorrect match.
 */
@Composable
fun TmdbManualMatchDialog(
    initialTitle: String,
    initialYear: String,
    isSeries: Boolean,
    candidates: List<TmdbSearchCandidate>,
    isSearching: Boolean,
    isArabic: Boolean,
    onSearch: (query: String, year: String) -> Unit,
    onSelectCandidate: (TmdbSearchCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val cleanedInitial = remember(initialTitle) {
        TmdbMetadataMatcher.cleanIptvTitle(initialTitle)
    }
    var queryText by remember(initialTitle) {
        mutableStateOf(cleanedInitial.cleanTitle.ifBlank { initialTitle })
    }
    var yearText by remember(initialTitle, initialYear) {
        mutableStateOf(initialYear.filter { it.isDigit() }.take(4).ifBlank { cleanedInitial.extractedYear })
    }

    LaunchedEffect(initialTitle, isSeries) {
        if (queryText.isNotBlank()) {
            onSearch(queryText, yearText)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF712121A))
                .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
                .testTag("tmdb_manual_match_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isArabic) "مطابقة TMDb يدوياً" else "Match TMDb Manually",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isArabic) {
                                "ابحث بالعنوان الأصلي أو الإنجليزي أو العربي واختر المطابقة الصحيحة"
                            } else {
                                "Search by original, English, or Arabic title to bind the exact match"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("tmdb_manual_match_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (isArabic) "إغلاق" else "Close",
                            tint = Color.White
                        )
                    }
                }

                // Search Inputs Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        label = { Text(if (isArabic) "العنوان" else "Title", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(queryText, yearText) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KrasRedBright,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = KrasRedBright,
                            unfocusedLabelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tmdb_manual_match_query_input")
                    )

                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { if (it.length <= 4) yearText = it.filter(Char::isDigit) },
                        label = { Text(if (isArabic) "السنة" else "Year", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(queryText, yearText) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KrasRedBright,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = KrasRedBright,
                            unfocusedLabelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .width(84.dp)
                            .testTag("tmdb_manual_match_year_input")
                    )
                }

                Button(
                    onClick = { onSearch(queryText, yearText) },
                    enabled = !isSearching && queryText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("tmdb_manual_match_search_button")
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "جاري البحث في TMDb..." else "Searching TMDb...",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "بحث في TMDb" else "Search TMDb",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Candidate Results
                if (!isSearching && candidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic) {
                                "لم يتم العثور على نتائج. جرّب البحث بالعنوان الإنجليزي أو إزالة السنة."
                            } else {
                                "No results found. Try searching with the original title or clearing the year."
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .testTag("tmdb_manual_match_results_list")
                    ) {
                        items(candidates, key = { it.tmdbId }) { cand ->
                            ManualMatchCandidateRow(
                                candidate = cand,
                                isArabic = isArabic,
                                onClick = { onSelectCandidate(cand) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualMatchCandidateRow(
    candidate: TmdbSearchCandidate,
    isArabic: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("tmdb_candidate_${candidate.tmdbId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1D1D28)),
            contentAlignment = Alignment.Center
        ) {
            if (candidate.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(candidate.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = candidate.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (candidate.originalTitle.isNotBlank() && !candidate.originalTitle.equals(candidate.title, ignoreCase = true)) {
                Text(
                    text = candidate.originalTitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (candidate.releaseYear.isNotBlank()) {
                    Text(
                        text = candidate.releaseYear,
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (candidate.rating > 0.0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = AccentGold, modifier = Modifier.size(12.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f", candidate.rating),
                            color = AccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "ID ${candidate.tmdbId}",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
            if (candidate.overview.isNotBlank()) {
                Text(
                    text = candidate.overview,
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }

        Text(
            text = if (isArabic) "اختيار" else "Select",
            color = KrasRedBright,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
