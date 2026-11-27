package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import com.example.data.api.TmdbDiagnosticCheck
import com.example.data.api.TmdbDiagnosticStatus
import com.example.data.api.TmdbDiagnosticsReport
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings -> TMDb -> Connection Diagnostics card.
 *
 * Clearly distinguishes metadata API authorization (Direct API Key / Proxy) from TMDb user
 * account authentication, runs live Movie/TV/Image verification checks, and never displays
 * credentials, API keys, or session tokens.
 */
@Composable
fun TmdbDiagnosticsCard(
    report: TmdbDiagnosticsReport,
    isArabic: Boolean,
    onTestConnectionClick: () -> Unit,
    onRefreshMetadataClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    val lastSuccessFormatted = remember(report.lastSuccessfulRequestMs, isArabic) {
        if (report.lastSuccessfulRequestMs <= 0L) {
            if (isArabic) "لا يوجد بعد" else "Never"
        } else {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(report.lastSuccessfulRequestMs))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 20.dp, accentGlow = AccentCyan)
                .testTag("tmdb_diagnostics_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentCyan.copy(alpha = 0.16f))
                                .border(1.dp, AccentCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = if (isArabic) "تشخيص اتصال TMDb" else "TMDb Connection Diagnostics",
                                tint = AccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isArabic) "تشخيص اتصال TMDb" else "TMDb Connection Diagnostics",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isArabic) {
                                    "فحص حالة API والبروكسي والبحث وتحميل الصور"
                                } else {
                                    "Inspect API, proxy, search, and image CDN health"
                                },
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Cache & Last Successful Request Summary Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isArabic) "آخر طلب ناجح" else "Last Successful API Request",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Text(
                            text = lastSuccessFormatted,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("tmdb_diag_last_success")
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isArabic) "البيانات المخزنة" else "Cached / Manual",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "${report.cachedMetadataCount} / ${report.manualMatchCount}",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("tmdb_diag_cache_counts")
                        )
                    }
                }

                // Diagnostic Rows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticStatusRow(
                        label = if (isArabic) "اتصال TMDb API المباشر" else "API Connection Status",
                        check = report.apiConnection,
                        isArabic = isArabic,
                        tag = "tmdb_diag_api_status"
                    )
                    DiagnosticStatusRow(
                        label = if (isArabic) "اتصال بروكسي Cloudflare" else "Proxy Connection Status",
                        check = report.proxyConnection,
                        isArabic = isArabic,
                        tag = "tmdb_diag_proxy_status"
                    )
                    DiagnosticStatusRow(
                        label = if (isArabic) "مصادقة حساب TMDb" else "Account Authentication Status",
                        check = report.accountAuth,
                        isArabic = isArabic,
                        tag = "tmdb_diag_account_status"
                    )
                    DiagnosticStatusRow(
                        label = if (isArabic) "اختبار بحث الأفلام" else "Movie Search Test",
                        check = report.movieSearchTest,
                        isArabic = isArabic,
                        tag = "tmdb_diag_movie_search"
                    )
                    DiagnosticStatusRow(
                        label = if (isArabic) "اختبار بحث المسلسلات" else "TV Series Search Test",
                        check = report.tvSearchTest,
                        isArabic = isArabic,
                        tag = "tmdb_diag_tv_search"
                    )
                    DiagnosticStatusRow(
                        label = if (isArabic) "اختبار تحميل الصور" else "Image Loading Test",
                        check = report.imageLoadingTest,
                        isArabic = isArabic,
                        tag = "tmdb_diag_image_test"
                    )
                }

                // Sanitized API Error Message (if any)
                if (report.lastErrorMessage.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(KrasRedDark.copy(alpha = 0.25f))
                            .border(1.dp, KrasRedBright.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("tmdb_diag_error_box"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = if (isArabic) "خطأ API" else "API Error",
                            tint = Color(0xFFFFB4AB),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = report.lastErrorMessage,
                            color = Color(0xFFFFDAD6),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Refresh feedback message
                if (report.refreshFeedbackMessage.isNotBlank()) {
                    Text(
                        text = report.refreshFeedbackMessage,
                        color = AccentEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("tmdb_diag_refresh_feedback")
                    )
                }

                // Action Buttons: Test Connection & Refresh Metadata
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTestConnectionClick,
                        enabled = !report.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("tmdb_diagnostics_test_button")
                    ) {
                        if (report.isRunning) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "جاري الفحص..." else "Testing...",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = if (isArabic) "اختبار الاتصال" else "Test Connection",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "اختبار الاتصال" else "Test Connection",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onRefreshMetadataClick,
                        enabled = !report.isRefreshingMetadata,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("tmdb_diagnostics_refresh_button")
                    ) {
                        if (report.isRefreshingMetadata) {
                            CircularProgressIndicator(
                                color = AccentCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "جاري التحديث..." else "Refreshing...",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (isArabic) "تحديث البيانات" else "Refresh Metadata",
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "تحديث البيانات" else "Refresh Metadata",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusRow(
    label: String,
    check: TmdbDiagnosticCheck,
    isArabic: Boolean,
    tag: String
) {
    val (dotColor, badgeText) = when (check.status) {
        TmdbDiagnosticStatus.PASSED -> AccentEmerald to if (isArabic) "ناجح" else "OK"
        TmdbDiagnosticStatus.WARNING -> AccentGold to if (isArabic) "تحذير" else "Partial"
        TmdbDiagnosticStatus.FAILED -> KrasRedBright to if (isArabic) "فشل" else "Failed"
        TmdbDiagnosticStatus.RUNNING -> AccentCyan to if (isArabic) "فحص..." else "Running"
        TmdbDiagnosticStatus.NOT_CONFIGURED -> TextMuted to if (isArabic) "اختياري" else "Optional"
        TmdbDiagnosticStatus.IDLE -> TextMuted to if (isArabic) "غير مفحوص" else "Not tested"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (check.summary.isNotBlank()) {
                Text(
                    text = check.summary,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(dotColor.copy(alpha = 0.16f))
                .border(1.dp, dotColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = badgeText,
                color = dotColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
